package core

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync/atomic"

	"github.com/FenOps/immcloudscale/server/internal/state"
)

// Changing the machine-learning setting re-runs setup (pulls or drops the ML container), which takes minutes.
var applyingFeatures atomic.Bool

// Features are the optional parts of the server an admin can switch from the app.
type Features struct {
	// MachineLearning powers face recognition, smart search and duplicate detection in Immich.
	MachineLearning bool    `json:"machineLearning"`
	MemoryGB        float64 `json:"memoryGB"`
	Applying        bool    `json:"applying"`
}

func (s *Server) Features() Features {
	return Features{s.Store.Read().Config.MachineLearning, memoryGB(), applyingFeatures.Load()}
}

// SetMachineLearning switches Immich's machine learning on or off in the background.
func (s *Server) SetMachineLearning(on bool) error {
	if s.Store.Read().Config.ImmichAdminPass == "" {
		return errors.New("the server isn't set up yet")
	}
	if !applyingFeatures.CompareAndSwap(false, true) {
		return errors.New("already applying a change; wait a few minutes")
	}
	go func() {
		defer applyingFeatures.Store(false)
		if err := s.Setup(context.Background(), SetupOptions{MachineLearning: &on}); err != nil {
			s.Log.Printf("switching machine learning %v: %v", on, err)
		} else {
			s.Log.Printf("Machine learning is now %v", map[bool]string{true: "on", false: "off"}[on])
		}
	}()
	return nil
}

// DeviceSession signs in to Immich as the phone's person, for the locked folder (which Immich opens only for a
// session unlocked with the person's PIN, never for an API key). The phone's previous session is signed out, and
// the new one is remembered so revoking the phone signs it out too. Unlike other sign-ins, a changed Immich
// password is not reset here: a phone mustn't be able to trigger that.
func (s *Server) DeviceSession(ctx context.Context, deviceID string) (string, error) {
	st := s.Store.Read()
	var dev *state.Device
	for i := range st.Devices {
		if st.Devices[i].ID == deviceID {
			dev = &st.Devices[i]
		}
	}
	if dev == nil {
		return "", ErrBadDevice
	}
	u := st.FindUser(dev.User)
	if u == nil {
		return "", ErrUserGone
	}
	im := s.immich(st.Config)
	tok, err := im.Login(ctx, u.Email, u.Password)
	if err != nil {
		return "", fmt.Errorf("signing in to Immich: %w", err)
	}
	id, err := im.CurrentSessionID(ctx, tok)
	if err != nil {
		_ = im.Logout(ctx, tok)
		return "", err
	}
	if dev.ImmichSessionID != "" && dev.ImmichSessionID != id {
		if err := im.DeleteSession(ctx, tok, dev.ImmichSessionID); err != nil {
			s.Log.Printf("signing out %s's previous locked-folder session: %v", dev.Name, err)
		}
	}
	err = s.Store.Update(func(st *state.State) error {
		for i := range st.Devices {
			if st.Devices[i].ID == deviceID {
				st.Devices[i].ImmichSessionID = id
			}
		}
		return nil
	})
	return tok, err
}

func memoryGB() float64 {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if rest, ok := strings.CutPrefix(sc.Text(), "MemTotal:"); ok {
			kb, _ := strconv.ParseFloat(strings.TrimSuffix(strings.TrimSpace(rest), " kB"), 64)
			return kb / 1024 / 1024
		}
	}
	return 0
}
