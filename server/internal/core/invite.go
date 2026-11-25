package core

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/FenOps/immcloudscale/server/internal/state"
)

// Invites let an admin bring someone in from anywhere: a link (single use, valid for PairingTTL) that also
// carries how to reach this server through Tailscale, so a phone away from home can pair through it.

// Invite makes a pairing code for [name], creating the person first if they're new (created says which).
// Admins aren't invited this way: a forwarded link would hand out an admin's phone.
func (s *Server) Invite(ctx context.Context, name, email, by string) (p state.Pairing, created bool, err error) {
	name = strings.ToLower(strings.TrimSpace(name))
	st := s.Store.Read()
	if u := st.FindUser(name); u == nil {
		if _, err := s.AddUser(ctx, name, email, false); err != nil {
			return state.Pairing{}, false, err
		}
		created = true
	} else if u.IsAdmin {
		return state.Pairing{}, false, fmt.Errorf("%s is an admin; pair their phone at home (Members, the QR button)", name)
	}
	p, err = s.StartPairingBy(name, by)
	return p, created, err
}

// Invites lists the pairing codes that are still valid, newest last.
func (s *Server) Invites() []state.Pairing {
	out := []state.Pairing{}
	for _, p := range s.Store.Read().Pairings {
		if p.Expires.After(s.Now()) {
			out = append(out, p)
		}
	}
	return out
}

// CancelInvite makes a pairing code unusable before it expires, also while someone is redeeming it.
func (s *Server) CancelInvite(code string) error {
	code = normCode(code)
	found := false
	err := s.Store.Update(func(st *state.State) error {
		kept := st.Pairings[:0]
		for _, p := range st.Pairings {
			if p.Code == code && p.Expires.After(s.Now()) {
				found = true
				continue
			}
			kept = append(kept, p)
		}
		st.Pairings = kept
		return nil
	})
	if err != nil {
		return err
	}
	if !found && !s.cancelInFlight(code) {
		return errors.New("that invite was already used or has expired")
	}
	return nil
}

// codeInFlight marks a code as being redeemed (on) or done (off); when done it reports whether it was cancelled.
func (s *Server) codeInFlight(code string, on bool) (cancelled bool) {
	s.codesMu.Lock()
	defer s.codesMu.Unlock()
	if s.inflight == nil {
		s.inflight, s.cancelled = map[string]bool{}, map[string]bool{}
	}
	if on {
		s.inflight[code] = true
		return false
	}
	cancelled = s.cancelled[code]
	delete(s.inflight, code)
	delete(s.cancelled, code)
	return cancelled
}

// cancelInFlight cancels a code that is being redeemed right now, so a failed pairing won't give it back.
func (s *Server) cancelInFlight(code string) bool {
	s.codesMu.Lock()
	defer s.codesMu.Unlock()
	if !s.inflight[code] {
		return false
	}
	s.cancelled[code] = true
	return true
}

// SentInvites lists invites admins sent from the app (not codes from the console, the CLI or approvals).
func (s *Server) SentInvites() []state.Pairing {
	out := []state.Pairing{}
	for _, p := range s.Invites() {
		if p.CreatedBy != "" {
			out = append(out, p)
		}
	}
	return out
}

// TailscaleJoin is what an invite link carries for reaching this server through Tailscale (nil when it isn't on one).
func (s *Server) TailscaleJoin() *TailscaleInfo { return s.tailscaleInfo() }
