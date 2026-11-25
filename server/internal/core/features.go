package core

import (
	"bufio"
	"context"
	"errors"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
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

// UserSession signs in to Immich as the person (for the locked folder, which Immich opens only for a session
// unlocked with the person's PIN, never for an API key).
func (s *Server) UserSession(ctx context.Context, name string) (string, error) {
	st := s.Store.Read()
	u := st.FindUser(name)
	if u == nil {
		return "", ErrUserGone
	}
	return s.userToken(ctx, st.Config, u)
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
