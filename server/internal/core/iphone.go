package core

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"

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
		Person: u.Name, Email: u.Email, Password: u.Password,
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
