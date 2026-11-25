package core

import (
	"context"
	"errors"

	"github.com/FenOps/immcloudscale/server/internal/state"
)

// Invites let an admin bring someone in from anywhere: a link (single use, valid for PairingTTL) that also
// carries how to reach this server through Tailscale, so a phone away from home can pair through it.

// Invite makes a pairing code for [name], creating the person first if they're new.
func (s *Server) Invite(ctx context.Context, name, email, by string) (state.Pairing, error) {
	if st := s.Store.Read(); st.FindUser(name) == nil {
		u, err := s.AddUser(ctx, name, email, false)
		if err != nil {
			return state.Pairing{}, err
		}
		name = u.Name // normalised (lower case)
	}
	return s.StartPairingBy(name, by)
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

// CancelInvite makes a pairing code unusable before it expires.
func (s *Server) CancelInvite(code string) error {
	found := false
	err := s.Store.Update(func(st *state.State) error {
		kept := st.Pairings[:0]
		for _, p := range st.Pairings {
			if p.Code == code {
				found = true
				continue
			}
			kept = append(kept, p)
		}
		st.Pairings = kept
		return nil
	})
	if err == nil && !found {
		err = errors.New("that invite was already used or has expired")
	}
	return err
}

// TailscaleJoin is what an invite link carries for reaching this server through Tailscale (nil when it isn't on one).
func (s *Server) TailscaleJoin() *TailscaleInfo { return s.tailscaleInfo() }
