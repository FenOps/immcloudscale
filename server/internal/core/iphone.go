package core

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/immich"
	"github.com/FenOps/immcloudscale/server/internal/state"
)

// iPhones use the official apps: Immich for photos and backup, Nextcloud for files, and Tailscale away from home.

// ncTrustedTailscale is the slot of Nextcloud's trusted_domains kept for this machine's Tailscale address (the
// addresses setup trusts come first).
const ncTrustedTailscale = 9

// IPhoneSetup is what someone types (or scans) to use the server from an iPhone or iPad.
type IPhoneSetup struct {
	Person   string `json:"person"`
	Email    string `json:"email"`
	Password string `json:"password"`
	// They chose their own Photos password (iPhone invite); Password then only signs in to Files.
	OwnPhotosPassword bool `json:"ownPhotosPassword,omitempty"`
	// At home, and through Tailscale (empty when this machine isn't on Tailscale).
	PhotosHome string `json:"photosHome"`
	FilesHome  string `json:"filesHome"`
	PhotosAway string `json:"photosAway,omitempty"`
	FilesAway  string `json:"filesAway,omitempty"`
	// Scanned in the Nextcloud app ("Log in with QR code"): signs in with an app password of its own.
	FilesLink string `json:"filesLink"`
}

// ErrNoPerson means there's no such person on the server.
var ErrNoPerson = errors.New("no such person")

// IPhoneSetup gathers [name]'s sign-in details for the iPhone apps and makes a Nextcloud app password for the
// iPhone, named "ImmCloudScale iPhone (name)" in their security settings. Setting it up again replaces it.
func (s *Server) IPhoneSetup(ctx context.Context, name string) (IPhoneSetup, error) {
	st := s.Store.Read()
	u := st.FindUser(name)
	if u == nil {
		return IPhoneSetup{}, fmt.Errorf("%w: %q", ErrNoPerson, name)
	}
	c := st.Config
	out := IPhoneSetup{
		Person: u.Name, Email: u.Email, Password: u.Password, OwnPhotosPassword: u.OwnImmichPassword,
		PhotosHome: fmt.Sprintf("http://%s:%d", c.LANAddress, c.ImmichPort),
		FilesHome:  fmt.Sprintf("http://%s:%d", c.LANAddress, c.NextcloudPort),
	}
	filesServer := out.FilesHome
	if ip := s.tailscaleIP(); ip != "" {
		out.PhotosAway = fmt.Sprintf("http://%s:%d", ip, c.ImmichPort)
		out.FilesAway = fmt.Sprintf("http://%s:%d", ip, c.NextcloudPort)
		filesServer = out.FilesAway // works at home too while Tailscale is on
		if c.NCTrustedTailscale != ip {
			s.trustTailscale(c, ip)
		}
	}
	nc := s.nextcloud(c)
	appPass, err := nc.AppPassword(ctx, u.Name, u.Password, "ImmCloudScale iPhone ("+u.Name+")")
	if err != nil {
		return IPhoneSetup{}, fmt.Errorf("creating a Files password for the iPhone: %w", err)
	}
	// One iPhone password per person: the one made last time (scanned or not) stops working.
	old := ""
	_ = s.Store.Update(func(st *state.State) error {
		if x := st.FindUser(u.Name); x != nil {
			old, x.IPhoneNCPassword = x.IPhoneNCPassword, appPass
		}
		return nil
	})
	if old != "" {
		if err := nc.RevokeAppPassword(ctx, u.Name, old); err != nil {
			s.Log.Printf("revoking %s's previous iPhone Files password: %v", u.Name, err)
		}
	}
	out.FilesLink = NextcloudLoginLink(u.Name, appPass, filesServer)
	return out, nil
}

// NextcloudLoginLink is the address the Nextcloud apps read from a QR code to sign in.
func NextcloudLoginLink(user, appPassword, server string) string {
	return "nc://login/user:" + linkEscape(user) + "&password:" + linkEscape(appPassword) + "&server:" + server
}

// linkEscape keeps a value from breaking the login link, whose parts are separated by "&" (the apps don't decode
// it: user names and Nextcloud's app passwords never need escaping, so in practice nothing changes).
func linkEscape(v string) string { return strings.ReplaceAll(url.PathEscape(v), "&", "%26") }

func (s *Server) tailscaleIP() string {
	if s.TailscaleIP == nil {
		return ""
	}
	return s.TailscaleIP()
}

// trustTailscale lets Nextcloud answer at this machine's Tailscale address (an iPhone away from home uses it).
func (s *Server) trustTailscale(c state.Config, ip string) {
	if _, err := s.Docker.Exec(c.StackDir, "www-data", "nextcloud",
		"php", "occ", "config:system:set", "trusted_domains", strconv.Itoa(ncTrustedTailscale), "--value="+ip); err != nil {
		s.Log.Printf("letting Files answer at %s: %v", ip, err)
		return
	}
	_ = s.Store.Update(func(st *state.State) error {
		st.Config.NCTrustedTailscale = ip
		return nil
	})
}

// IPhoneInviteTTL is how long the temporary password in an iPhone invite works.
const IPhoneInviteTTL = 15 * time.Minute

// IPhoneInvite is what goes into an invite to use Photos (Immich) on an iPhone or iPad.
type IPhoneInvite struct {
	Person       string    `json:"person"`
	Email        string    `json:"email"`
	TempPassword string    `json:"tempPassword"`
	Expires      time.Time `json:"expires"`
	PhotosHome   string    `json:"photosHome"`
	PhotosAway   string    `json:"photosAway,omitempty"`
}

// InviteToIPhone gives [name] a temporary Immich password that works for 15 minutes; at their first sign-in Immich
// asks them to choose their own. From then on the server uses an Immich key of its own for them (for imports and
// the like) and leaves their password alone.
func (s *Server) InviteToIPhone(ctx context.Context, name string) (IPhoneInvite, error) {
	s.iphoneMu.Lock() // one invite (or expiry) at a time, so none undoes another's password
	defer s.iphoneMu.Unlock()
	st := s.Store.Read()
	u := st.FindUser(name)
	if u == nil {
		return IPhoneInvite{}, fmt.Errorf("%w: %q", ErrNoPerson, name)
	}
	if u.ImmichID == "" {
		return IPhoneInvite{}, fmt.Errorf("%s has no Photos account yet", u.Name)
	}
	c := st.Config
	im := s.immich(c)
	admin, err := s.adminToken(ctx, c)
	if err != nil {
		return IPhoneInvite{}, err
	}
	temp := tempPassword()
	key := u.ImmichKey
	if key != "" {
		if _, err := im.Storage(ctx, immich.APIKeyPrefix+key); isStatus(err, 401) {
			key = "" // deleted in Immich's settings: make a new one
		}
	}
	if key == "" {
		if err := s.makeServerKey(ctx, c, u, admin, temp); err != nil {
			return IPhoneInvite{}, err
		}
	}
	// Marked first, so a pairing meanwhile uses the server's key instead of resetting the temporary password.
	if err := s.Store.Update(func(st *state.State) error {
		x := st.FindUser(u.Name)
		if x == nil {
			return ErrUserGone
		}
		x.OwnImmichPassword = true
		return nil
	}); err != nil {
		return IPhoneInvite{}, err
	}
	if err := im.TemporaryPassword(ctx, admin, u.ImmichID, temp); err != nil {
		return IPhoneInvite{}, fmt.Errorf("setting a temporary Photos password: %w", err)
	}
	until := s.Now().Add(IPhoneInviteTTL)
	if err := s.Store.Update(func(st *state.State) error {
		x := st.FindUser(u.Name)
		if x == nil {
			return ErrUserGone
		}
		x.IPhoneInviteUntil = until
		return nil
	}); err != nil {
		return IPhoneInvite{}, err
	}
	s.Log.Printf("iPhone invite for %s (temporary password until %s)", u.Name, until.Format(time.Kitchen))
	out := IPhoneInvite{
		Person: u.Name, Email: u.Email, TempPassword: temp, Expires: until.UTC(),
		PhotosHome: fmt.Sprintf("http://%s:%d", c.LANAddress, c.ImmichPort),
	}
	if ip := s.tailscaleIP(); ip != "" {
		out.PhotosAway = fmt.Sprintf("http://%s:%d", ip, c.ImmichPort)
	}
	return out, nil
}

// makeServerKey creates the server's own Immich key for [u] and stores it straight away, so a later failure
// doesn't leave a key nobody knows about. Once they've chosen their own password, it signs in once with [temp].
func (s *Server) makeServerKey(ctx context.Context, c state.Config, u *state.User, admin, temp string) error {
	im := s.immich(c)
	var tok string
	var err error
	if u.OwnImmichPassword {
		if err = im.TemporaryPassword(ctx, admin, u.ImmichID, temp); err != nil {
			return fmt.Errorf("setting a temporary Photos password: %w", err)
		}
		tok, err = im.Login(ctx, u.Email, temp)
	} else {
		tok, err = s.userToken(ctx, c, u)
	}
	if err != nil {
		return fmt.Errorf("signing in to Photos as %s: %w", u.Name, err)
	}
	k, err := im.CreateAPIKey(ctx, tok, "ImmCloudScale server")
	s.endSignIn(context.WithoutCancel(ctx), c, tok)
	if err != nil {
		return fmt.Errorf("creating the server's Photos key: %w", err)
	}
	err = s.Store.Update(func(st *state.State) error {
		x := st.FindUser(u.Name)
		if x == nil {
			return ErrUserGone
		}
		x.ImmichKey, x.ImmichKeyID = k.Secret, k.ID
		return nil
	})
	if err != nil {
		_ = im.DeleteAPIKey(context.WithoutCancel(ctx), immich.APIKeyPrefix+k.Secret, k.ID) // not saved: no use to anyone
	}
	return err
}

func isStatus(err error, status int) bool {
	var ae *immich.APIError
	return errors.As(err, &ae) && ae.Status == status
}

// expireIPhoneInvites ends invites whose 15 minutes are up without the person having chosen their own password:
// the temporary one in the message stops working.
func (s *Server) expireIPhoneInvites(ctx context.Context) {
	s.iphoneMu.Lock()
	defer s.iphoneMu.Unlock()
	st := s.Store.Read()
	im := s.immich(st.Config)
	admin := ""
	for _, u := range st.Users {
		if u.IPhoneInviteUntil.IsZero() || s.Now().Before(u.IPhoneInviteUntil) {
			continue
		}
		if u.ImmichID != "" {
			if admin == "" {
				var err error
				if admin, err = s.adminToken(ctx, st.Config); err != nil {
					return // tried again next minute
				}
			}
			must, err := im.MustChangePassword(ctx, admin, u.ImmichID)
			if err != nil && !isStatus(err, 404) {
				continue
			}
			if err == nil && must {
				if err := im.SetPassword(ctx, admin, u.ImmichID, tempPassword()+tempPassword()); err != nil {
					continue
				}
				s.Log.Printf("iPhone invite for %s expired unused", u.Name)
			}
		}
		_ = s.Store.Update(func(st *state.State) error {
			if x := st.FindUser(u.Name); x != nil && x.IPhoneInviteUntil.Equal(u.IPhoneInviteUntil) {
				x.IPhoneInviteUntil = time.Time{}
			}
			return nil
		})
	}
}

// IPhoneInviteWorker checks every minute for expired iPhone invites until ctx ends.
func (s *Server) IPhoneInviteWorker(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Minute):
		}
		s.expireIPhoneInvites(ctx)
	}
}

// tempPassword is easy to type on a phone: no look-alike characters, in groups of four.
func tempPassword() string {
	const chars = "abcdefghjkmnpqrstuvwxyz23456789"
	out := make([]byte, 0, 14)
	b := make([]byte, 1)
	for n := 0; n < 12; {
		_, _ = rand.Read(b)
		if int(b[0]) >= 256/len(chars)*len(chars) {
			continue // keeps every character equally likely
		}
		if n > 0 && n%4 == 0 {
			out = append(out, '-')
		}
		out = append(out, chars[int(b[0])%len(chars)])
		n++
	}
	return string(out)
}
