package state

import (
	"path/filepath"
	"testing"
)

func TestTwoProcessesSeeEachOther(t *testing.T) {
	p := filepath.Join(t.TempDir(), "state.json")
	daemon, _ := Open(p)
	cli, _ := Open(p)
	if err := cli.Update(func(s *State) error { s.Users = append(s.Users, User{Name: "alex"}); return nil }); err != nil {
		t.Fatal(err)
	}
	// The daemon must see the CLI's user, and its own write must keep it.
	if err := daemon.Update(func(s *State) error { s.Users = append(s.Users, User{Name: "sam"}); return nil }); err != nil {
		t.Fatal(err)
	}
	got := cli.Read()
	if len(got.Users) != 2 || got.Users[0].Name != "alex" || got.Users[1].Name != "sam" {
		t.Fatalf("%+v", got.Users)
	}
}

func TestFailedUpdateChangesNothing(t *testing.T) {
	st, _ := Open(filepath.Join(t.TempDir(), "s.json"))
	_ = st.Update(func(s *State) error { s.Config.WGPort = 1; return nil })
	_ = st.Update(func(s *State) error { s.Config.WGPort = 2; return ErrNotSetUp })
	if st.Read().Config.WGPort != 1 {
		t.Fatal("failed update leaked")
	}
}

func TestPairCode(t *testing.T) {
	c := PairCode()
	if len(c) != 9 || c[4] != '-' {
		t.Fatal(c)
	}
}

func TestFailedInPlaceFilterDoesNotLeak(t *testing.T) {
	st, _ := Open(filepath.Join(t.TempDir(), "s.json"))
	_ = st.Update(func(s *State) error { s.Devices = []Device{{ID: "a"}, {ID: "b"}}; return nil })
	_ = st.Update(func(s *State) error {
		out := s.Devices[:0]
		for _, d := range s.Devices {
			if d.ID != "a" {
				out = append(out, d)
			}
		}
		s.Devices = out
		return ErrNotSetUp
	})
	if d := st.Read().Devices; len(d) != 2 || d[0].ID != "a" {
		t.Fatalf("%+v", d)
	}
}
