package core

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestIPhoneSetup(t *testing.T) {
	s, _, _ := newTestServer(t)
	s.TailscaleIP = func() string { return "100.81.254.96" }
	st, err := s.IPhoneSetup(context.Background(), "camille")
	if err != nil {
		t.Fatal(err)
	}
	if st.PhotosHome != "http://192.168.1.50:2283" || st.PhotosAway != "http://100.81.254.96:2283" || st.Email != "camille@example.com" || st.Password == "" {
		t.Fatalf("%+v", st)
	}
	if !strings.HasPrefix(st.FilesLink, "nc://login/user:camille&password:") || !strings.HasSuffix(st.FilesLink, "&server:http://100.81.254.96:8080") {
		t.Fatalf("link %s", st.FilesLink)
	}
	if strings.Contains(st.FilesLink, st.Password) {
		t.Fatal("the Files link carries the person's password instead of an app password")
	}
	if !strings.Contains(strings.Join(s.Docker.(*fakeDocker).execs, "\n"), "trusted_domains 9 --value=100.81.254.96") {
		t.Fatal("Files wasn't told to answer at the Tailscale address")
	}
	execs := len(s.Docker.(*fakeDocker).execs)
	if _, err := s.IPhoneSetup(context.Background(), "camille"); err != nil {
		t.Fatal(err)
	}
	if len(s.Docker.(*fakeDocker).execs) != execs {
		t.Fatal("Files was told about the same Tailscale address again")
	}
	// Without Tailscale: home addresses only.
	s.TailscaleIP = func() string { return "" }
	st, _ = s.IPhoneSetup(context.Background(), "camille")
	if st.PhotosAway != "" || !strings.HasSuffix(st.FilesLink, "&server:http://192.168.1.50:8080") {
		t.Fatalf("%+v", st)
	}
	if _, err := s.IPhoneSetup(context.Background(), "nobody"); !errors.Is(err, ErrNoPerson) {
		t.Fatalf("someone who doesn't exist: %v", err)
	}
}

func TestNextcloudLoginLinkEscapes(t *testing.T) {
	got := NextcloudLoginLink("anne marie", "a/b&c", "http://nas:8080")
	if got != "nc://login/user:anne%20marie&password:a%2Fb%26c&server:http://nas:8080" {
		t.Fatal(got)
	}
}

func TestAnIPhoneHasOneNamedFilesPassword(t *testing.T) {
	s, f, _ := newTestServer(t)
	iphone := func() []string {
		f.mu.Lock()
		defer f.mu.Unlock()
		var live []string
		for ap := range f.appPass {
			if f.labels[ap] == "ImmCloudScale iPhone (camille)" {
				live = append(live, ap)
			}
		}
		return live
	}
	for i := 0; i < 3; i++ { // opened three times while helping someone
		if _, err := s.IPhoneSetup(context.Background(), "camille"); err != nil {
			t.Fatal(err)
		}
	}
	live := iphone()
	if len(live) != 1 {
		t.Fatalf("%d iPhone passwords still work", len(live))
	}
	st := s.Store.Read()
	if st.FindUser("camille").IPhoneNCPassword != live[0] {
		t.Fatal("the stored iPhone password isn't the working one")
	}
}
