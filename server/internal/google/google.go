// Package google moves a person's Google Drive and Google Photos onto the home server: Drive with rclone
// (read-only access, copied into their Nextcloud files and kept in sync), Photos from Google Takeout archives
// with immich-go (dates, places and albums kept, duplicates skipped).
//
// Both tools are built on the server from Go's module mirror the first time they're needed, at the versions
// pinned here; the mirror checks every download against the public checksum database.
package google

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	rcloneModule   = "github.com/rclone/rclone@v1.76.0"
	immichGoModule = "github.com/simulot/immich-go@v0.32.0"

	// Google's sign-in sends the browser here when done. Nothing listens on the phone, so the page doesn't load;
	// the person copies its address back into the app, which carries the code (the usual flow for apps without
	// a public web address).
	RedirectURI = "http://127.0.0.1:53682/"
	authURL     = "https://accounts.google.com/o/oauth2/v2/auth"
	TokenURL    = "https://oauth2.googleapis.com/token"
	scopes      = "openid email https://www.googleapis.com/auth/drive.readonly"
)

// Runner runs a command and returns its combined output.
type Runner func(ctx context.Context, env []string, name string, args ...string) ([]byte, error)

func run(ctx context.Context, env []string, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = append(os.Environ(), env...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return out, fmt.Errorf("%s: %v\n%s", filepath.Base(name), err, tail(out, 2000))
	}
	return out, nil
}

// Tools are rclone and immich-go, kept in Dir.
type Tools struct {
	Dir   string // e.g. /var/lib/immcloudscale/tools
	Cache string // Go's module and build cache while building them
	Run   Runner
	// Backend is rclone's kind of remote: "drive" (Google Drive), or "local" in tests with Root as the folder.
	Backend string
	Root    string
}

func NewTools(dir, cache string) *Tools {
	return &Tools{Dir: dir, Cache: cache, Run: run, Backend: "drive"}
}

func (t *Tools) path(name string) string { return filepath.Join(t.Dir, name) }

// Ready reports whether both tools are there.
func (t *Tools) Ready() bool {
	for _, n := range []string{"rclone", "immich-go"} {
		if fi, err := os.Stat(t.path(n)); err != nil || fi.Mode()&0o111 == 0 {
			return false
		}
	}
	return true
}

// Ensure builds whichever tool is missing. It takes a few minutes the first time (longer on a small NAS).
func (t *Tools) Ensure(ctx context.Context) error {
	if t.Ready() {
		return nil
	}
	gobin, err := goBinary()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(t.Dir, 0o755); err != nil {
		return err
	}
	env := []string{
		"GOBIN=" + t.Dir, "GOTOOLCHAIN=auto", "CGO_ENABLED=0", "GOFLAGS=-trimpath",
		"GOPATH=" + filepath.Join(t.Cache, "go"), "GOCACHE=" + filepath.Join(t.Cache, "build"), "HOME=" + t.Cache,
	}
	for _, m := range []string{rcloneModule, immichGoModule} {
		if _, err := t.Run(ctx, env, gobin, "install", "-ldflags=-s -w", m); err != nil {
			return fmt.Errorf("building %s: %w", strings.SplitN(m, "@", 2)[0], err)
		}
	}
	// The build cache is large and not needed once the tools exist.
	_ = os.RemoveAll(filepath.Join(t.Cache, "build"))
	return nil
}

func goBinary() (string, error) {
	if p, err := exec.LookPath("go"); err == nil {
		return p, nil
	}
	if _, err := os.Stat("/usr/local/go/bin/go"); err == nil {
		return "/usr/local/go/bin/go", nil
	}
	return "", errors.New("Go isn't installed on this server; run the installer again (it sets Go up)")
}

// ---- signing in to Google ----

// Login is one Google sign-in in progress.
type Login struct {
	State    string
	Verifier string
	URL      string
}

// NewLogin makes the sign-in address for [clientID], asking for read-only Drive access and the account's email.
func NewLogin(clientID string) Login {
	l := Login{State: random(16), Verifier: random(48)}
	sum := sha256.Sum256([]byte(l.Verifier))
	q := url.Values{
		"client_id": {clientID}, "redirect_uri": {RedirectURI}, "response_type": {"code"}, "scope": {scopes},
		"access_type": {"offline"}, "prompt": {"consent"}, "state": {l.State},
		"code_challenge": {base64.RawURLEncoding.EncodeToString(sum[:])}, "code_challenge_method": {"S256"},
	}
	l.URL = authURL + "?" + q.Encode()
	return l
}

func random(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// CodeFrom reads the sign-in code from the address the browser ended up on (pasted by the person).
func CodeFrom(pasted, state string) (string, error) {
	pasted = strings.TrimSpace(pasted)
	u, err := url.Parse(pasted)
	if err != nil || u.RawQuery == "" {
		return "", errors.New("paste the whole address from the browser's address bar (it starts with http://127.0.0.1)")
	}
	q := u.Query()
	if e := q.Get("error"); e != "" {
		if e == "access_denied" {
			return "", errors.New("Google says access wasn't allowed; try again and choose Allow")
		}
		return "", fmt.Errorf("Google answered: %s", e)
	}
	if q.Get("state") != state {
		return "", errors.New("that address belongs to another sign-in; start again")
	}
	if q.Get("code") == "" {
		return "", errors.New("that address has no sign-in code; paste the whole address")
	}
	return q.Get("code"), nil
}

// Token is how rclone stores Google's answer (the refresh token keeps working until access is removed).
type Token struct {
	AccessToken  string    `json:"access_token"`
	TokenType    string    `json:"token_type"`
	RefreshToken string    `json:"refresh_token"`
	Expiry       time.Time `json:"expiry"`
}

// Exchange trades the code for tokens and returns rclone's token JSON and the account's email.
func Exchange(ctx context.Context, tokenURL, clientID, secret, code, verifier string) (token, email string, err error) {
	form := url.Values{
		"client_id": {clientID}, "client_secret": {secret}, "code": {code}, "code_verifier": {verifier},
		"grant_type": {"authorization_code"}, "redirect_uri": {RedirectURI},
	}
	req, _ := http.NewRequestWithContext(ctx, "POST", tokenURL, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", "", fmt.Errorf("couldn't reach Google: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var r struct {
		AccessToken  string `json:"access_token"`
		TokenType    string `json:"token_type"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int    `json:"expires_in"`
		IDToken      string `json:"id_token"`
		Error        string `json:"error"`
		Description  string `json:"error_description"`
	}
	_ = json.Unmarshal(body, &r)
	if resp.StatusCode != 200 || r.Error != "" {
		if r.Error == "invalid_grant" {
			return "", "", errors.New("that sign-in code was already used or has expired; start again")
		}
		return "", "", fmt.Errorf("Google refused the sign-in: %s %s", r.Error, r.Description)
	}
	if r.RefreshToken == "" {
		return "", "", errors.New("Google didn't allow lasting access; start again and allow access")
	}
	email = emailFromIDToken(r.IDToken)
	if email == "" {
		return "", "", errors.New("Google didn't say which account this is; start again")
	}
	tok, _ := json.Marshal(Token{r.AccessToken, "Bearer", r.RefreshToken, time.Now().Add(time.Duration(r.ExpiresIn) * time.Second)})
	return string(tok), strings.ToLower(email), nil
}

// emailFromIDToken reads the email from Google's ID token. It came straight from Google over HTTPS in answer to
// this server's own request, so its signature isn't checked again.
func emailFromIDToken(t string) string {
	parts := strings.Split(t, ".")
	if len(parts) != 3 {
		return ""
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return ""
	}
	var c struct {
		Email    string `json:"email"`
		Verified bool   `json:"email_verified"`
	}
	if json.Unmarshal(payload, &c) != nil || !c.Verified {
		return ""
	}
	return c.Email
}

// ---- Drive ----

// DriveAuth is what rclone needs to read one Google account's Drive.
type DriveAuth struct{ ClientID, Secret, Token string }

// SyncDrive copies the Drive into dest. It only adds and updates: files deleted in Google stay on the server.
// Google Docs, Sheets and Slides arrive as Word, Excel and PowerPoint files. skip lists paths (relative to the
// Drive) not to fetch, e.g. Takeout archives already imported. It returns the token as rclone left it (Google
// may have renewed it) with what's now in dest.
func (t *Tools) SyncDrive(ctx context.Context, a DriveAuth, dest string, skip []string) (token string, files int, size int64, err error) {
	if err := os.MkdirAll(dest, 0o750); err != nil {
		return a.Token, 0, 0, err
	}
	work, err := os.MkdirTemp("", "ics-drive-*")
	if err != nil {
		return a.Token, 0, 0, err
	}
	defer os.RemoveAll(work)
	conf, excl := filepath.Join(work, "rclone.conf"), filepath.Join(work, "skip")
	var c strings.Builder
	fmt.Fprintf(&c, "[src]\ntype = %s\nclient_id = %s\nclient_secret = %s\nscope = drive.readonly\ntoken = %s\n", t.Backend, a.ClientID, a.Secret, a.Token)
	c.WriteString("export_formats = docx,xlsx,pptx,svg\nskip_shortcuts = true\nacknowledge_abuse = true\n")
	var x strings.Builder
	for _, p := range skip {
		fmt.Fprintln(&x, "/"+globEscape(strings.TrimPrefix(p, "/")))
	}
	if err := os.WriteFile(conf, []byte(c.String()), 0o600); err != nil {
		return a.Token, 0, 0, err
	}
	if err := os.WriteFile(excl, []byte(x.String()), 0o600); err != nil {
		return a.Token, 0, 0, err
	}
	env := []string{"RCLONE_CONFIG=" + conf}
	if _, err := t.Run(ctx, env, t.path("rclone"), "copy", "src:"+t.Root, dest, "--exclude-from", excl,
		"--transfers", "4", "--checkers", "8", "--retries", "3", "--stats", "0"); err != nil {
		return renewedToken(conf, a.Token), 0, 0, driveError(err)
	}
	out, err := t.Run(ctx, env, t.path("rclone"), "size", "--json", dest)
	if err != nil {
		return renewedToken(conf, a.Token), 0, 0, err
	}
	var sz struct {
		Count int   `json:"count"`
		Bytes int64 `json:"bytes"`
	}
	if err := json.Unmarshal(lastJSON(out), &sz); err != nil {
		return renewedToken(conf, a.Token), 0, 0, fmt.Errorf("reading the copied size: %w", err)
	}
	return renewedToken(conf, a.Token), sz.Count, sz.Bytes, nil
}

// renewedToken reads the token back from rclone's config, where it saves a renewed one.
func renewedToken(conf, old string) string {
	b, err := os.ReadFile(conf)
	if err != nil {
		return old
	}
	for _, line := range strings.Split(string(b), "\n") {
		if v, ok := strings.CutPrefix(strings.TrimSpace(line), "token = "); ok && strings.HasPrefix(v, "{") {
			return v
		}
	}
	return old
}

func driveError(err error) error {
	s := err.Error()
	switch {
	case strings.Contains(s, "invalid_grant"):
		return errors.New("Google no longer allows access to this account; remove it and connect it again")
	case strings.Contains(s, "storageQuotaExceeded") || strings.Contains(s, "no space left"):
		return errors.New("the server's disk is full")
	}
	return err
}

// globEscape keeps rclone's filter characters in file names literal.
func globEscape(s string) string {
	return regexp.MustCompile(`[\\*?\[\]{}]`).ReplaceAllString(s, `\$0`)
}

func lastJSON(out []byte) []byte {
	if i := bytes.LastIndexByte(out, '{'); i >= 0 {
		return out[i:]
	}
	return out
}

// ---- Photos (Takeout) ----

// Report is what an import did.
type Report struct {
	Imported   int `json:"imported"`
	Duplicates int `json:"duplicates"` // already on the server, or twice in the archives
	Errors     int `json:"errors"`
}

// ImportTakeout imports Google Takeout archives into Immich with the person's API key, tagging every photo
// with [tag] and recreating Google's albums.
func (t *Tools) ImportTakeout(ctx context.Context, server, apiKey, tag string, zips []string) (Report, error) {
	logFile, err := os.CreateTemp("", "ics-takeout-*.log")
	if err != nil {
		return Report{}, err
	}
	logFile.Close()
	defer os.Remove(logFile.Name())
	args := []string{
		"upload", "from-google-photos", "--server=" + server, "--no-ui", "--pause-immich-jobs=false",
		"--on-errors=continue", "--log-file=" + logFile.Name(), "--tag=" + tag,
	}
	// The key goes in the environment, where other users on the machine can't read it from the process list.
	out, err := t.Run(ctx, []string{"IMMICH_GO_UPLOAD_API_KEY=" + apiKey}, t.path("immich-go"), append(args, zips...)...)
	r := ParseReport(string(out))
	if err != nil {
		return r, err
	}
	return r, nil
}

// ParseReport reads immich-go's end-of-run report.
func ParseReport(out string) Report {
	count := func(label string) int {
		m := regexp.MustCompile(`(?m)^\s*` + regexp.QuoteMeta(label) + `\s*:\s*(\d+)`).FindStringSubmatch(out)
		if m == nil {
			return 0
		}
		n, _ := strconv.Atoi(m[1])
		return n
	}
	return Report{
		Imported:   count("uploaded successfully"),
		Duplicates: count("server has duplicate") + count("discarded local duplicate"),
		Errors:     count("Errors"),
	}
}

func tail(b []byte, n int) []byte {
	if len(b) > n {
		return b[len(b)-n:]
	}
	return b
}
