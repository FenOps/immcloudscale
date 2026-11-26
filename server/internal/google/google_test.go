package google

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
	"testing"
)

func TestTheSignInAddressAsksForReadOnlyDrive(t *testing.T) {
	l := NewLogin("client-1")
	u, _ := url.Parse(l.URL)
	q := u.Query()
	if q.Get("client_id") != "client-1" || q.Get("redirect_uri") != RedirectURI || q.Get("access_type") != "offline" {
		t.Fatalf("got %s", l.URL)
	}
	if !strings.Contains(q.Get("scope"), "drive.readonly") || strings.Contains(q.Get("scope"), "auth/drive ") {
		t.Fatalf("scope %q", q.Get("scope"))
	}
	if q.Get("state") != l.State || q.Get("code_challenge") == "" || l.Verifier == "" {
		t.Fatal("missing state or PKCE")
	}
}

func TestTheCodeComesFromThePastedAddress(t *testing.T) {
	code, err := CodeFrom("  http://127.0.0.1:53682/?state=abc&code=4%2F0Ab&scope=x  ", "abc")
	if err != nil || code != "4/0Ab" {
		t.Fatalf("got %q %v", code, err)
	}
	for _, bad := range []string{"", "4/0Ab", "http://127.0.0.1:53682/?state=other&code=x", "http://127.0.0.1:53682/?state=abc&error=access_denied"} {
		if _, err := CodeFrom(bad, "abc"); err == nil {
			t.Errorf("%q: want an error", bad)
		}
	}
}

func idToken(claims map[string]any) string {
	b, _ := json.Marshal(claims)
	return "h." + base64.RawURLEncoding.EncodeToString(b) + ".s"
}

func TestExchangeGivesRclonesTokenAndTheEmail(t *testing.T) {
	g := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		if r.Form.Get("code") != "the-code" || r.Form.Get("code_verifier") != "v" || r.Form.Get("client_secret") != "s" {
			w.WriteHeader(400)
			json.NewEncoder(w).Encode(map[string]string{"error": "invalid_grant"})
			return
		}
		json.NewEncoder(w).Encode(map[string]any{
			"access_token": "at", "refresh_token": "rt", "expires_in": 3599, "token_type": "Bearer",
			"id_token": idToken(map[string]any{"email": "Camille@Gmail.com", "email_verified": true}),
		})
	}))
	defer g.Close()
	tok, email, err := Exchange(context.Background(), g.URL, "c", "s", "the-code", "v")
	if err != nil || email != "camille@gmail.com" {
		t.Fatalf("got %q %v", email, err)
	}
	var tk Token
	if json.Unmarshal([]byte(tok), &tk) != nil || tk.RefreshToken != "rt" || tk.AccessToken != "at" || tk.Expiry.IsZero() {
		t.Fatalf("token %s", tok)
	}
	if _, _, err := Exchange(context.Background(), g.URL, "c", "s", "used-code", "v"); err == nil || !strings.Contains(err.Error(), "already used") {
		t.Fatalf("a used code: %v", err)
	}
}

func TestAnUnverifiedEmailIsNotTrusted(t *testing.T) {
	if emailFromIDToken(idToken(map[string]any{"email": "x@y.z", "email_verified": false})) != "" {
		t.Fatal("unverified email accepted")
	}
}

func TestParseImmichGosReport(t *testing.T) {
	out := `Asset Tracking Report:
Total Assets:          5  (8.9 KB)
  Errors:              1  (0 B)
Asset Lifecycle (PROCESSED):
  uploaded successfully              :       3  (7.1 KB)
Asset Lifecycle (DISCARDED):
  server has duplicate               :       4  (7.1 KB)
  discarded local duplicate          :       1  (1.8 KB)
`
	if r := ParseReport(out); r != (Report{Imported: 3, Duplicates: 5, Errors: 1}) {
		t.Fatalf("got %+v", r)
	}
}

// toolsDir is where rclone and immich-go are for the tests that run them (ICS_TOOLS), or "" to skip those.
func toolsDir(t *testing.T) string {
	d := os.Getenv("ICS_TOOLS")
	if d == "" {
		t.Skip("set ICS_TOOLS to a folder with rclone and immich-go")
	}
	return d
}

func TestSyncDriveCopiesAddsAndSkips(t *testing.T) {
	tools := &Tools{Dir: toolsDir(t), Run: run, Backend: "local", Root: t.TempDir()}
	write := func(p, s string) {
		os.MkdirAll(filepath.Dir(filepath.Join(tools.Root, p)), 0o755)
		os.WriteFile(filepath.Join(tools.Root, p), []byte(s), 0o644)
	}
	write("Notes/a.txt", "hello")
	write("Takeout/takeout-1.zip", "zip")
	write("Takeout/takeout-2.zip", "zip2")
	dest := t.TempDir()
	tok, files, size, err := tools.SyncDrive(context.Background(), DriveAuth{"c", "s", `{"access_token":"x"}`}, dest, []string{"Takeout/takeout-1.zip"})
	if err != nil {
		t.Fatal(err)
	}
	if files != 2 || size != int64(len("hello")+len("zip2")) || tok != `{"access_token":"x"}` {
		t.Fatalf("files %d size %d token %s", files, size, tok)
	}
	if _, err := os.Stat(filepath.Join(dest, "Takeout/takeout-1.zip")); err == nil {
		t.Fatal("a skipped archive was copied")
	}
	// Deleted in Google: kept here. New in Google: added.
	os.Remove(filepath.Join(tools.Root, "Notes/a.txt"))
	write("Notes/b.txt", "new")
	if _, files, _, err = tools.SyncDrive(context.Background(), DriveAuth{"c", "s", "{}"}, dest, nil); err != nil || files != 4 {
		t.Fatalf("second sync: %d files, %v", files, err)
	}
}

// TestImportTakeout imports a small Takeout into a live Immich (ICS_TEST_IMMICH and ICS_TEST_IMMICH_KEY) twice:
// the second time everything is a duplicate.
func TestImportTakeout(t *testing.T) {
	dir := toolsDir(t)
	server, key, zip := os.Getenv("ICS_TEST_IMMICH"), os.Getenv("ICS_TEST_IMMICH_KEY"), os.Getenv("ICS_TEST_TAKEOUT")
	if server == "" || key == "" || zip == "" {
		t.Skip("set ICS_TEST_IMMICH, ICS_TEST_IMMICH_KEY and ICS_TEST_TAKEOUT")
	}
	tools := &Tools{Dir: dir, Run: run}
	first, err := tools.ImportTakeout(context.Background(), server, key, "From Google/test", []string{zip})
	if err != nil {
		t.Fatal(err)
	}
	again, err := tools.ImportTakeout(context.Background(), server, key, "From Google/test", []string{zip})
	if err != nil {
		t.Fatal(err)
	}
	if again.Imported != 0 || again.Duplicates < first.Imported+first.Duplicates {
		t.Fatalf("first %+v, again %+v", first, again)
	}
}
