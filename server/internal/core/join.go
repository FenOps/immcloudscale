package core

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"net/mail"
	"strings"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/state"
)

// Someone without an account asks for one from the app (on the home network); an admin approves it in the
// app or the console, and the requester's app then pairs by itself with the code that approval creates.

const (
	maxPendingRequests = 20
	requestTTL         = 7 * 24 * time.Hour
)

var (
	ErrBadRequestToken = errors.New("unknown request")
	ErrNotAdmin        = errors.New("only admins can do that")
	ErrBadDevice       = errors.New("this phone isn't recognised; pair it again")
)

func hashToken(t string) string {
	sum := sha256.Sum256([]byte(t))
	return hex.EncodeToString(sum[:])
}

func tokenMatches(hash, token string) bool {
	return hash != "" && subtle.ConstantTimeCompare([]byte(hash), []byte(hashToken(token))) == 1
}

// PublicRequest is a join request as admins see it.
type PublicRequest struct {
	ID      string    `json:"id"`
	Name    string    `json:"name"`
	Email   string    `json:"email"`
	Device  string    `json:"device"`
	Created time.Time `json:"created"`
	Status  string    `json:"status"`
	Code    string    `json:"code,omitempty"`
}

// RequestJoin records a request for an account and returns its ID and the secret the requester checks it with.
func (s *Server) RequestJoin(name, email, device string) (id, token string, err error) {
	name = strings.ToLower(strings.TrimSpace(name))
	if !userRe.MatchString(name) || name == ncAdmin {
		return "", "", fmt.Errorf("names are 2-32 lowercase letters, digits, '.', '_' or '-', starting with a letter")
	}
	email = strings.TrimSpace(email)
	if email != "" {
		addr, err := mail.ParseAddress(email)
		if err != nil || addr.Name != "" {
			return "", "", fmt.Errorf("invalid email %q", email)
		}
		email = strings.ToLower(addr.Address)
	}
	device = SanitizeDeviceName(device)
	id, token = "req_"+state.Secret(12), state.Secret(32)
	err = s.Store.Update(func(st *state.State) error {
		if st.Config.ImmichAdminPass == "" {
			return state.ErrNotSetUp
		}
		if st.FindUser(name) != nil {
			return fmt.Errorf("someone is already called %q; pick another name", name)
		}
		s.dropOldRequests(st)
		pending := 0
		for _, r := range st.Requests {
			if r.Status == "pending" {
				pending++
				if r.Name == name {
					return fmt.Errorf("there's already a request for %q waiting for an admin", name)
				}
			}
		}
		if pending >= maxPendingRequests {
			return errors.New("too many requests are waiting; ask an admin to look at them first")
		}
		st.Requests = append(st.Requests, state.JoinRequest{
			ID: id, Name: name, Email: email, Device: device, Created: s.Now(), Status: "pending", TokenHash: hashToken(token),
		})
		return nil
	})
	if err != nil {
		return "", "", err
	}
	s.Log.Printf("Join request from %q (%s)", name, device)
	return id, token, nil
}

// JoinStatus tells the requester how their request stands. Once approved it includes a pairing code (a fresh
// one if the earlier code has expired and the phone hasn't paired yet), so the requester's app can pair.
func (s *Server) JoinStatus(id, token string) (status, code string, err error) {
	st := s.Store.Read()
	var req *state.JoinRequest
	for i := range st.Requests {
		if st.Requests[i].ID == id {
			req = &st.Requests[i]
		}
	}
	if req == nil || !tokenMatches(req.TokenHash, token) || s.Now().Sub(req.Created) >= requestTTL {
		return "", "", ErrBadRequestToken
	}
	if req.Status != "approved" {
		return req.Status, "", nil
	}
	valid := false
	for _, p := range st.Pairings {
		if p.Code == req.Code && s.Now().Before(p.Expires) {
			valid = true
		}
	}
	paired := false
	for _, d := range st.Devices {
		if d.User == req.Name {
			paired = true
		}
	}
	if paired {
		return "approved", "", nil
	}
	if valid {
		return "approved", req.Code, nil
	}
	p, err := s.StartPairing(req.Name)
	if err != nil {
		return "", "", err
	}
	err = s.Store.Update(func(st *state.State) error {
		for i := range st.Requests {
			if st.Requests[i].ID == id {
				st.Requests[i].Code = p.Code
			}
		}
		return nil
	})
	return "approved", p.Code, err
}

// Requests lists requests waiting for an admin, and those decided in the last week.
func (s *Server) Requests() []PublicRequest {
	out := []PublicRequest{}
	for _, r := range s.Store.Read().Requests {
		if r.Status == "pending" || s.Now().Sub(r.Created) < requestTTL {
			out = append(out, PublicRequest{r.ID, r.Name, r.Email, r.Device, r.Created, r.Status, r.Code})
		}
	}
	return out
}

// ApproveJoin creates the person's accounts and a pairing code for their phone.
func (s *Server) ApproveJoin(ctx context.Context, id, by string) (PublicRequest, error) {
	var req state.JoinRequest
	for _, r := range s.Store.Read().Requests {
		if r.ID == id {
			req = r
		}
	}
	if req.ID == "" || req.Status != "pending" {
		return PublicRequest{}, errors.New("that request isn't waiting any more")
	}
	// An earlier approval may have created the accounts and then failed: carry on from there.
	if st := s.Store.Read(); st.FindUser(req.Name) == nil {
		if _, err := s.AddUser(ctx, req.Name, req.Email, false); err != nil {
			return PublicRequest{}, err
		}
	}
	p, err := s.StartPairing(req.Name)
	if err != nil {
		return PublicRequest{}, err
	}
	err = s.Store.Update(func(st *state.State) error {
		for i := range st.Requests {
			if st.Requests[i].ID == id {
				st.Requests[i].Status, st.Requests[i].Code, st.Requests[i].DecidedBy = "approved", p.Code, by
				req = st.Requests[i]
			}
		}
		return nil
	})
	s.Log.Printf("Join request from %q approved by %s", req.Name, by)
	return PublicRequest{req.ID, req.Name, req.Email, req.Device, req.Created, req.Status, req.Code}, err
}

func (s *Server) DeclineJoin(id, by string) error {
	found := false
	err := s.Store.Update(func(st *state.State) error {
		for i := range st.Requests {
			if st.Requests[i].ID == id && st.Requests[i].Status == "pending" {
				st.Requests[i].Status, st.Requests[i].DecidedBy = "declined", by
				found = true
			}
		}
		return nil
	})
	if err == nil && !found {
		err = errors.New("that request isn't waiting any more")
	}
	return err
}

// Member is a person as admins' phones see them: never their passwords.
type Member struct {
	Name    string `json:"name"`
	Email   string `json:"email"`
	IsAdmin bool   `json:"isAdmin"`
	Devices int    `json:"devices"`
}

// Members lists people from the stored state (no Docker or Immich calls).
func (s *Server) Members() []Member {
	st := s.Store.Read()
	count := map[string]int{}
	for _, d := range st.Devices {
		count[d.User]++
	}
	out := make([]Member, 0, len(st.Users))
	for _, u := range st.Users {
		out = append(out, Member{u.Name, u.Email, u.IsAdmin, count[u.Name]})
	}
	return out
}

// SetAdmin makes someone an admin or not. There's always at least one admin left.
func (s *Server) SetAdmin(name string, admin bool) error {
	return s.Store.Update(func(st *state.State) error {
		u := st.FindUser(name)
		if u == nil {
			return fmt.Errorf("no user %q", name)
		}
		if !admin && u.IsAdmin {
			admins := 0
			for _, x := range st.Users {
				if x.IsAdmin {
					admins++
				}
			}
			if admins <= 1 {
				return errors.New("there has to be at least one admin; make someone else admin first")
			}
		}
		u.IsAdmin = admin
		return nil
	})
}

// DeviceUser checks a phone's secret from pairing and returns its person.
func (s *Server) DeviceUser(id, token string) (state.User, error) {
	st := s.Store.Read()
	for _, d := range st.Devices {
		if d.ID == id && tokenMatches(d.TokenHash, token) {
			if u := st.FindUser(d.User); u != nil {
				return *u, nil
			}
		}
	}
	return state.User{}, ErrBadDevice
}

// dropOldRequests forgets decided requests after a week, and pending ones nobody looked at.
func (s *Server) dropOldRequests(st *state.State) {
	kept := st.Requests[:0]
	for _, r := range st.Requests {
		if s.Now().Sub(r.Created) < requestTTL {
			kept = append(kept, r)
		}
	}
	st.Requests = kept
}
