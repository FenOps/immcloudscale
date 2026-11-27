package core

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
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

func TestAnIPhoneInvitesTemporaryPasswordExpires(t *testing.T) {
	s, f, _ := newTestServer(t)
	c := withClock(s)
	ctx := context.Background()
	inv, err := s.InviteToIPhone(ctx, "camille")
	if err != nil {
		t.Fatal(err)
	}
	if len(inv.TempPassword) != 14 || inv.Expires.Sub(c.now) != IPhoneInviteTTL || inv.Email != "camille@example.com" {
		t.Fatalf("%+v", inv)
	}
	f.mu.Lock()
	works, must := f.users["camille@example.com"] == inv.TempPassword, f.mustChange["camille"]
	f.mu.Unlock()
	if !works || !must {
		t.Fatal("the temporary password isn't set, or Immich won't ask for a new one")
	}
	st := s.Store.Read()
	u := st.FindUser("camille")
	if !u.OwnImmichPassword || u.ImmichKey == "" {
		t.Fatalf("no server key: %+v", u)
	}
	// Not used within 15 minutes: the password in the message stops working.
	c.tick(16 * time.Minute)
	s.expireIPhoneInvites(ctx)
	f.mu.Lock()
	still := f.users["camille@example.com"] == inv.TempPassword
	f.mu.Unlock()
	if still {
		t.Fatal("the temporary password still works after 15 minutes")
	}
	st = s.Store.Read()
	if !st.FindUser("camille").IPhoneInviteUntil.IsZero() {
		t.Fatal("the invite wasn't closed")
	}
}

func TestAPasswordTheyChoseIsLeftAlone(t *testing.T) {
	s, f, _ := newTestServer(t)
	c := withClock(s)
	ctx := context.Background()
	if _, err := s.InviteToIPhone(ctx, "camille"); err != nil {
		t.Fatal(err)
	}
	// They sign in on the iPhone and choose their own password.
	f.mu.Lock()
	f.users["camille@example.com"], f.mustChange["camille"] = "their-own", false
	f.mu.Unlock()
	c.tick(16 * time.Minute)
	s.expireIPhoneInvites(ctx)
	st := s.Store.Read()
	u := st.FindUser("camille")
	tok, err := s.userToken(ctx, st.Config, u) // used for imports and the like
	if err != nil || !strings.HasPrefix(tok, "apikey:") {
		t.Fatalf("the server's own key wasn't used: %q %v", tok, err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.users["camille@example.com"] != "their-own" {
		t.Fatal("their own password was reset")
	}
}

func TestAfterAnIPhoneInviteTheirPhonesStillPairAndRevoke(t *testing.T) {
	s, f, _ := newTestServer(t)
	ctx := context.Background()
	if _, err := s.InviteToIPhone(ctx, "camille"); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	f.users["camille@example.com"], f.mustChange["camille"] = "their-own", false
	f.mu.Unlock()
	p, err := s.StartPairing("camille")
	if err != nil {
		t.Fatal(err)
	}
	res, err := s.Pair(ctx, PairRequest{Code: p.Code, DeviceName: "Pixel 9"})
	if err != nil {
		t.Fatalf("pairing after an iPhone invite: %v", err)
	}
	if err := s.Revoke(ctx, res.DeviceID); err != nil {
		t.Fatalf("revoking: %v", err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.keys) != 1 || f.users["camille@example.com"] != "their-own" {
		t.Fatalf("keys=%v, password reset=%v", f.keys, f.users["camille@example.com"] != "their-own")
	}
}

func TestAReInviteKeepsTheServersKeyAndANewInviteIsntUndone(t *testing.T) {
	s, f, _ := newTestServer(t)
	c := withClock(s)
	ctx := context.Background()
	if _, err := s.InviteToIPhone(ctx, "camille"); err != nil {
		t.Fatal(err)
	}
	c.tick(16 * time.Minute)
	second, err := s.InviteToIPhone(ctx, "camille") // before the worker noticed the first one ran out
	if err != nil {
		t.Fatal(err)
	}
	s.expireIPhoneInvites(ctx)
	f.mu.Lock()
	works, keys := f.users["camille@example.com"] == second.TempPassword, len(f.keys)
	f.mu.Unlock()
	if !works || keys != 1 {
		t.Fatalf("second invite works=%v, server keys=%d", works, keys)
	}
}

func TestADeletedServerKeyIsReplacedByTheNextInvite(t *testing.T) {
	s, f, _ := newTestServer(t)
	ctx := context.Background()
	if _, err := s.InviteToIPhone(ctx, "camille"); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	f.users["camille@example.com"], f.mustChange["camille"] = "their-own", false
	f.keys = map[string]string{} // they deleted it in Immich's settings
	f.mu.Unlock()
	inv, err := s.InviteToIPhone(ctx, "camille")
	if err != nil {
		t.Fatal(err)
	}
	st := s.Store.Read()
	u := st.FindUser("camille")
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.keys) != 1 || f.keys[u.ImmichKeyID] != "camille@example.com" || f.users["camille@example.com"] != inv.TempPassword || !f.mustChange["camille"] {
		t.Fatalf("keys=%v must=%v", f.keys, f.mustChange["camille"])
	}
}
