package core

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/google"
	"github.com/FenOps/immcloudscale/server/internal/state"
)

const testClientID = "123456789-abcdef.apps.googleusercontent.com"

// fakeTools stands in for rclone and immich-go: it records the calls and answers like them.
type fakeTools struct {
	mu    sync.Mutex
	calls []string
	envs  [][]string
	drive map[string]string // files "in Google Drive" copied on rclone copy
	token string            // what rclone leaves in its config (a renewed token)
}

func (f *fakeTools) run(_ context.Context, env []string, name string, args ...string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, filepath.Base(name)+" "+strings.Join(args, " "))
	f.envs = append(f.envs, env)
	switch {
	case filepath.Base(name) == "rclone" && args[0] == "copy":
		dest := args[2]
		skip := map[string]bool{}
		for i, a := range args {
			if a == "--exclude-from" {
				b, _ := os.ReadFile(args[i+1])
				for _, l := range strings.Split(strings.TrimSpace(string(b)), "\n") {
					skip[strings.TrimPrefix(l, "/")] = true
				}
			}
		}
		for p, body := range f.drive {
			if !skip[p] {
				os.MkdirAll(filepath.Dir(filepath.Join(dest, p)), 0o755)
				os.WriteFile(filepath.Join(dest, p), []byte(body), 0o644)
			}
		}
		if f.token != "" {
			for _, e := range env {
				if conf, ok := strings.CutPrefix(e, "RCLONE_CONFIG="); ok {
					b, _ := os.ReadFile(conf)
					lines := strings.Split(string(b), "\n")
					for i, l := range lines {
						if strings.HasPrefix(l, "token = ") {
							lines[i] = "token = " + f.token
						}
					}
					os.WriteFile(conf, []byte(strings.Join(lines, "\n")), 0o600)
				}
			}
		}
		return nil, nil
	case filepath.Base(name) == "rclone" && args[0] == "size":
		n, size := 0, 0
		filepath.Walk(args[2], func(_ string, fi os.FileInfo, err error) error {
			if err == nil && !fi.IsDir() {
				n++
				size += int(fi.Size())
			}
			return nil
		})
		return []byte(`{"count":` + itoa(n) + `,"bytes":` + itoa(size) + `,"sizeless":0}`), nil
	case filepath.Base(name) == "immich-go":
		return []byte("Asset Tracking Report:\n  Errors:              0  (0 B)\n  uploaded successfully              :       7  (1 KB)\n  server has duplicate               :       2  (1 KB)\n"), nil
	}
	return nil, nil
}

func itoa(n int) string { b, _ := json.Marshal(n); return string(b) }

func withGoogle(t *testing.T, s *Server) *fakeTools {
	t.Helper()
	dir := t.TempDir()
	for _, n := range []string{"rclone", "immich-go"} {
		os.WriteFile(filepath.Join(dir, n), []byte("#!/bin/sh\n"), 0o755)
	}
	f := &fakeTools{drive: map[string]string{}}
	s.Google = &google.Tools{Dir: dir, Run: f.run, Backend: "drive"}
	return f
}

func fakeGoogleToken(t *testing.T, email string) string {
	g := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		claims, _ := json.Marshal(map[string]any{"email": email, "email_verified": true})
		json.NewEncoder(w).Encode(map[string]any{
			"access_token": "at-" + r.Form.Get("code"), "refresh_token": "rt", "expires_in": 3600,
			"id_token": "h." + base64.RawURLEncoding.EncodeToString(claims) + ".s",
		})
	}))
	t.Cleanup(g.Close)
	return g.URL
}

// signIn goes through Google's sign-in as the app does: start, "allow" in the browser, paste the address back.
func signIn(t *testing.T, s *Server, person, owner string) (GoogleAccountInfo, error) {
	link, err := s.StartGoogleLogin(person, owner)
	if err != nil {
		return GoogleAccountInfo{}, err
	}
	u, _ := url.Parse(link)
	return s.FinishGoogleLogin(context.Background(), person, google.RedirectURI+"?code=c1&scope=x&state="+u.Query().Get("state"))
}

func TestGoogleNeedsAKeyThatLooksRight(t *testing.T) {
	s, _, _ := newTestServer(t)
	if _, err := s.StartGoogleLogin("camille", "camille"); err != ErrNoGoogleKey {
		t.Fatalf("got %v", err)
	}
	for _, id := range []string{"", "abc", "123.apps.googleusercontent.com"} {
		if s.SetGoogleKey(id, "GOCSPX-abcdefghijk") == nil {
			t.Errorf("%q accepted", id)
		}
	}
	if s.SetGoogleKey(testClientID, "short") == nil {
		t.Error("a short secret accepted")
	}
	if err := s.SetGoogleKey(" "+testClientID+" ", "GOCSPX-abcdefghijk"); err != nil {
		t.Fatal(err)
	}
	if !s.GoogleStatus("camille").KeySet {
		t.Fatal("key not shown as set")
	}
}

func TestConnectingAGoogleAccount(t *testing.T) {
	s, _, _ := newTestServer(t)
	s.SetGoogleKey(testClientID, "GOCSPX-abcdefghijk")
	s.GoogleTokenURL = fakeGoogleToken(t, "Camille@Gmail.com")
	a, err := signIn(t, s, "camille", "camille")
	if err != nil {
		t.Fatal(err)
	}
	if a.Email != "camille@gmail.com" || a.Folder != "Google Drive (camille@gmail.com)" {
		t.Fatalf("%+v", a)
	}
	st := s.Store.Read()
	if len(st.Google) != 1 || !strings.Contains(st.Google[0].Token, `"refresh_token":"rt"`) {
		t.Fatalf("%+v", st.Google)
	}
	if strings.Contains(mustJSON(s.GoogleStatus("camille")), "refresh_token") {
		t.Fatal("the token is shown to the app")
	}
	// The same account again (e.g. access was removed in Google): one entry, a fresh token.
	if _, err := signIn(t, s, "camille", "camille"); err != nil {
		t.Fatal(err)
	}
	if n := len(s.Store.Read().Google); n != 1 {
		t.Fatalf("%d accounts", n)
	}
}

func TestASignInWorksOnceAndOnlyForWhoStartedIt(t *testing.T) {
	s, _, _ := newTestServer(t)
	s.SetGoogleKey(testClientID, "GOCSPX-abcdefghijk")
	s.GoogleTokenURL = fakeGoogleToken(t, "x@gmail.com")
	s.AddUser(context.Background(), "nina", "", false)
	link, _ := s.StartGoogleLogin("camille", "camille")
	u, _ := url.Parse(link)
	pasted := google.RedirectURI + "?code=c&state=" + u.Query().Get("state")
	if _, err := s.FinishGoogleLogin(context.Background(), "nina", pasted); err == nil {
		t.Fatal("someone else finished camille's sign-in")
	}
	if _, err := s.FinishGoogleLogin(context.Background(), "camille", pasted); err == nil {
		t.Fatal("a sign-in tried by someone else should be spent")
	}
}

func TestAnAdminCanConnectAnAccountForSomeoneElse(t *testing.T) {
	s, _, _ := newTestServer(t)
	s.SetGoogleKey(testClientID, "GOCSPX-abcdefghijk")
	s.GoogleTokenURL = fakeGoogleToken(t, "nina@gmail.com")
	s.AddUser(context.Background(), "nina", "", false)
	if _, err := signIn(t, s, "camille", "nina"); err != nil {
		t.Fatal(err)
	}
	if got := s.GoogleStatus("nina").Accounts; len(got) != 1 || got[0].Person != "nina" {
		t.Fatalf("%+v", got)
	}
	if _, err := s.StartGoogleLogin("camille", "nobody"); err == nil {
		t.Fatal("connected for a person who doesn't exist")
	}
}

func mustJSON(v any) string { b, _ := json.Marshal(v); return string(b) }

func TestDriveIsCopiedIntoThePersonsFiles(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withGoogle(t, s)
	s.SetGoogleKey(testClientID, "GOCSPX-abcdefghijk")
	s.GoogleTokenURL = fakeGoogleToken(t, "camille@gmail.com")
	signIn(t, s, "camille", "camille")
	f.drive["Notes/plan.docx"] = "doc"
	f.token = `{"access_token":"renewed","refresh_token":"rt"}`
	s.googleRound(context.Background())

	c := s.Store.Read().Config
	if _, err := os.Stat(filepath.Join(c.DataDir, "files/camille/files/Google Drive (camille@gmail.com)/Notes/plan.docx")); err != nil {
		t.Fatal("not copied:", err)
	}
	a := s.Store.Read().Google[0]
	if a.Files != 1 || a.Synced.IsZero() || a.Error != "" || !strings.Contains(a.Token, "renewed") {
		t.Fatalf("%+v", a)
	}
	if !strings.Contains(strings.Join(s.Docker.(*fakeDocker).execs, "\n"), "files:scan --path=/camille/files/Google Drive (camille@gmail.com)") {
		t.Fatalf("Nextcloud wasn't told: %v", s.Docker.(*fakeDocker).execs)
	}
	// Synced a moment ago: not again until it's due (or asked for).
	n := len(f.calls)
	s.googleRound(context.Background())
	if len(f.calls) != n {
		t.Fatal("copied again too soon")
	}
	s.SyncGoogleNow("camille", a.ID)
	s.googleRound(context.Background())
	if len(f.calls) == n {
		t.Fatal("Sync now didn't copy")
	}
}

func touchOld(t *testing.T, p string, age time.Duration) {
	os.MkdirAll(filepath.Dir(p), 0o755)
	os.WriteFile(p, []byte("zip"), 0o644)
	old := time.Now().Add(-age)
	os.Chtimes(p, old, old)
}

func TestTakeoutExportsAreImportedWhenComplete(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withGoogle(t, s)
	root := filesRoot(s.Store.Read().Config, "camille")
	tk := filepath.Join(root, TakeoutFolder)
	touchOld(t, filepath.Join(tk, "takeout-20251126T101500Z-001.zip"), time.Hour)
	touchOld(t, filepath.Join(tk, "takeout-20251126T101500Z-002.zip"), 5*time.Minute) // still arriving
	touchOld(t, filepath.Join(tk, "work account", "takeout-20251120T080000Z-001.zip"), time.Hour)
	touchOld(t, filepath.Join(tk, "holiday.zip"), time.Hour) // not a Takeout archive

	s.googleRound(context.Background())
	imports := s.Store.Read().Imports
	if len(imports) != 1 || imports[0].Export != "takeout-20251120t080000z" || imports[0].Source != "work account" || imports[0].Imported != 7 || imports[0].Duplicates != 2 {
		t.Fatalf("%+v", imports)
	}
	if !strings.Contains(f.calls[len(f.calls)-1], "--tag=From Google/work account") {
		t.Fatalf("call %s", f.calls[len(f.calls)-1])
	}
	for _, e := range f.envs[len(f.envs)-1] {
		if strings.HasPrefix(e, "IMMICH_GO_UPLOAD_API_KEY=") && len(e) > len("IMMICH_GO_UPLOAD_API_KEY=") {
			goto keyOK
		}
	}
	t.Fatal("no API key given")
keyOK:
	if _, err := os.Stat(filepath.Join(tk, "work account", "takeout-20251120T080000Z-001.zip")); err == nil {
		t.Fatal("an imported archive was kept")
	}
	if _, err := os.Stat(filepath.Join(tk, "holiday.zip")); err != nil {
		t.Fatal("a zip that isn't from Takeout was touched")
	}

	// Once every part has settled, the whole export is imported in one go.
	touchOld(t, filepath.Join(tk, "takeout-20251126T101500Z-002.zip"), time.Hour)
	s.googleRound(context.Background())
	last := f.calls[len(f.calls)-1]
	if !strings.Contains(last, "-001.zip") || !strings.Contains(last, "-002.zip") {
		t.Fatalf("parts imported separately: %s", last)
	}
	if n := len(s.Store.Read().Imports); n != 2 {
		t.Fatalf("%d imports", n)
	}
	s.googleRound(context.Background())
	if n := len(s.Store.Read().Imports); n != 2 {
		t.Fatal("imported twice")
	}
}

func TestTakeoutDeliveredToDriveIsImportedAndNotFetchedAgain(t *testing.T) {
	s, _, _ := newTestServer(t)
	f := withGoogle(t, s)
	s.SetGoogleKey(testClientID, "GOCSPX-abcdefghijk")
	s.GoogleTokenURL = fakeGoogleToken(t, "camille@gmail.com")
	signIn(t, s, "camille", "camille")
	f.drive["Takeout/takeout-20251126T101500Z-001.zip"] = "zip"
	s.googleRound(context.Background()) // copies it; too fresh to import yet

	p := filepath.Join(filesRoot(s.Store.Read().Config, "camille"), "Google Drive (camille@gmail.com)/Takeout/takeout-20251126T101500Z-001.zip")
	old := time.Now().Add(-time.Hour)
	os.Chtimes(p, old, old)
	s.googleRound(context.Background())
	st := s.Store.Read()
	if len(st.Imports) != 1 || st.Imports[0].Source != "camille@gmail.com" {
		t.Fatalf("%+v", st.Imports)
	}
	if got := st.Google[0].SkipTakeout; len(got) != 1 || got[0] != "Takeout/takeout-20251126T101500Z-001.zip" {
		t.Fatalf("skip %v", got)
	}
	if _, err := os.Stat(p); err == nil {
		t.Fatal("the imported archive was kept")
	}
	s.SyncGoogleNow("camille", st.Google[0].ID)
	s.googleRound(context.Background())
	if _, err := os.Stat(p); err == nil {
		t.Fatal("the imported archive was copied from Drive again")
	}
}

func TestRemovingAnAccountKeepsTheFiles(t *testing.T) {
	s, _, _ := newTestServer(t)
	withGoogle(t, s)
	s.SetGoogleKey(testClientID, "GOCSPX-abcdefghijk")
	s.GoogleTokenURL = fakeGoogleToken(t, "camille@gmail.com")
	a, _ := signIn(t, s, "camille", "camille")
	s.AddUser(context.Background(), "nina", "", false)
	if s.RemoveGoogle("nina", a.ID, false) == nil {
		t.Fatal("someone else removed camille's account")
	}
	if err := s.RemoveGoogle("camille", a.ID, false); err != nil {
		t.Fatal(err)
	}
	if len(s.Store.Read().Google) != 0 {
		t.Fatal("still connected")
	}
	_ = state.State{}
}
