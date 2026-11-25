package web

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/core"
	"github.com/FenOps/immcloudscale/server/internal/state"
)

func newHandler(t *testing.T) http.Handler {
	_, h := newServerAndHandler(t)
	return h
}

func newServerAndHandler(t *testing.T) (*core.Server, http.Handler) {
	st, _ := state.Open(filepath.Join(t.TempDir(), "s.json"))
	st.Update(func(s *state.State) error {
		s.Config.AdminPassword = "pw"
		s.Config.LANAddress = "192.168.1.50"
		s.Config.AdminPort = 8790
		s.Users = []state.User{{Name: "camille"}}
		return nil
	})
	s := core.New(st)
	s.Log = log.New(io.Discard, "", 0)
	s.ImmichURL = func(state.Config) string { return "http://127.0.0.1:1" }
	s.NextcloudURL = func(state.Config) string { return "http://127.0.0.1:1" }
	s.TailscaleIP = nil // never the test machine's own Tailscale
	return s, New(s).Routes()
}

func do(h http.Handler, method, path, body string, auth bool) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.RemoteAddr = "192.168.1.20:5000"
	if body != "" {
		r.Header.Set("Content-Type", "application/json")
	}
	r.Header.Set("X-ICS-Request", "1")
	if auth {
		r.SetBasicAuth("admin", "pw")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestAdminNeedsPassword(t *testing.T) {
	h := newHandler(t)
	if w := do(h, "GET", "/", "", false); w.Code != 401 || w.Header().Get("WWW-Authenticate") == "" {
		t.Fatalf("console must ask for a password, got %d", w.Code)
	}
	if w := do(h, "POST", "/api/admin/pairings", `{"user":"camille"}`, false); w.Code != 401 {
		t.Fatal("pairing codes must need the admin password")
	}
	if w := do(h, "GET", "/", "", true); w.Code != 200 || !strings.Contains(w.Body.String(), "ImmCloudScale") {
		t.Fatalf("got %d", w.Code)
	}
	w := do(h, "POST", "/api/admin/pairings", `{"user":"camille"}`, true)
	if w.Code != 201 || !strings.Contains(w.Body.String(), "immcloudscale://pair?") {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	var p struct{ Code string }
	json.Unmarshal(w.Body.Bytes(), &p)
	if w := do(h, "GET", "/api/admin/qr?code="+p.Code, "", true); w.Code != 200 || w.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("qr: %d", w.Code)
	}
	if w := do(h, "GET", "/api/admin/qr?code=ABCD-EFGH", "", true); w.Code != 404 {
		t.Fatalf("a QR for a code that doesn't exist: %d", w.Code)
	}
}

func TestPublicEndpointsAndCodeRateLimit(t *testing.T) {
	h := newHandler(t)
	if w := do(h, "GET", "/api/v1/info", "", false); w.Code != 200 || !strings.Contains(w.Body.String(), `"product":"immcloudscale"`) {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	for i := 0; i < 10; i++ {
		if w := do(h, "POST", "/api/v1/pair", `{"code":"WRNG-CODE"}`, false); w.Code != 403 {
			t.Fatalf("attempt %d: %d", i, w.Code)
		}
	}
	if w := do(h, "POST", "/api/v1/pair", `{"code":"WRNG-CODE"}`, false); w.Code != 429 {
		t.Fatalf("guessing codes must be rate limited, got %d", w.Code)
	}
}

func TestPairLink(t *testing.T) {
	u, _ := url.Parse(PairLink("http://192.168.1.50:8790", "ABCD-EFGH", time.Time{}, nil))
	if u.Scheme != "immcloudscale" || u.Host != "pair" || u.Query().Get("code") != "ABCD-EFGH" || u.Query().Get("server") != "http://192.168.1.50:8790" {
		t.Fatal(u)
	}
	if u.Query().Has("exp") || u.Query().Has("ts") {
		t.Fatal("nothing extra without an expiry or Tailscale", u)
	}
	exp := time.Unix(1795620000, 0)
	u, _ = url.Parse(PairLink("http://192.168.1.50:8790", "ABCD-EFGH", exp, &core.TailscaleInfo{IP: "100.101.102.103", AuthKey: "tskey-auth-x"}))
	q := u.Query()
	if q.Get("exp") != "1795620000" || q.Get("ts") != "100.101.102.103" || q.Get("tskey") != "tskey-auth-x" || q.Has("control") {
		t.Fatal(u)
	}
}

func TestInvitesFromAnAdminPhone(t *testing.T) {
	s, h := newServerAndHandler(t)
	s.TailscaleIP = func() string { return "100.101.102.103" }
	s.Store.Update(func(st *state.State) error {
		st.Users = []state.User{{Name: "camille", IsAdmin: true}, {Name: "tom"}}
		st.Devices = []state.Device{{ID: "dev_a", User: "camille", TokenHash: hashForTest("a")}, {ID: "dev_t", User: "tom", TokenHash: hashForTest("t")}}
		return nil
	})
	call := func(method, path, body, secret string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		if body != "" {
			r.Header.Set("Content-Type", "application/json")
		}
		r.Header.Set("Authorization", "Device "+secret)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	// An existing person: no account to create, just a code (new people go through AddUser, covered in core).
	if w := call("POST", "/api/v1/manage/invites", `{"name":"tom"}`, "dev_t:t"); w.Code != 403 {
		t.Fatalf("non-admin: %d", w.Code)
	}
	w := call("POST", "/api/v1/manage/invites", `{"name":"tom"}`, "dev_a:a")
	if w.Code != 201 {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
	var inv struct{ Code, Link string }
	json.Unmarshal(w.Body.Bytes(), &inv)
	if !strings.Contains(inv.Link, "ts=100.101.102.103") || !strings.Contains(inv.Link, "exp=") {
		t.Fatal(inv.Link)
	}
	list := call("GET", "/api/v1/manage/invites", "", "dev_a:a").Body.String()
	if !strings.Contains(list, inv.Code) || !strings.Contains(list, `"createdBy":"camille"`) {
		t.Fatal(list)
	}
	if w := call("DELETE", "/api/v1/manage/invites/"+inv.Code, "", "dev_a:a"); w.Code != 204 {
		t.Fatalf("cancel: %d", w.Code)
	}
	if w := do(h, "POST", "/api/v1/pair", `{"code":"`+inv.Code+`"}`, false); w.Code != 403 {
		t.Fatalf("a cancelled invite still works: %d %s", w.Code, w.Body)
	}
}

func TestCrossSiteRequestsAreRefused(t *testing.T) {
	h := newHandler(t)
	// A form post from another site: right password (sent by the browser), but no console header.
	r := httptest.NewRequest("POST", "/api/admin/users", strings.NewReader(`{"name":"evil"}`))
	r.SetBasicAuth("admin", "pw")
	r.Header.Set("Content-Type", "text/plain")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatalf("cross-site form must be refused, got %d", w.Code)
	}
	r = httptest.NewRequest("GET", "/api/admin/status", nil)
	r.SetBasicAuth("admin", "pw")
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatalf("cross-site read must be refused, got %d", w.Code)
	}
	// The public pair endpoint only takes JSON.
	r = httptest.NewRequest("POST", "/api/v1/pair", strings.NewReader(`{"code":"x"}`))
	r.Header.Set("Content-Type", "text/plain")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 415 {
		t.Fatalf("got %d", w.Code)
	}
}

func TestInfoIsCheapAndHasServerID(t *testing.T) {
	h := newHandler(t)
	w := do(h, "GET", "/api/v1/info", "", false)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"id":`) {
		t.Fatalf("%d %s", w.Code, w.Body)
	}
}

func TestBadKeyIsAClientError(t *testing.T) {
	h := newHandler(t)
	if w := do(h, "POST", "/api/v1/pair", `{"code":"ABCD-EFGH","wgPublicKey":"not-a-key"}`, false); w.Code != 400 {
		t.Fatalf("got %d %s", w.Code, w.Body)
	}
}

func TestInfoGivesTheTailscaleAddressButNeverTheKey(t *testing.T) {
	s, h := newServerAndHandler(t)
	s.TailscaleIP = func() string { return "100.101.102.103" }
	if err := s.SetTailscaleKey("tskey-auth-example-0123456789", ""); err != nil {
		t.Fatal(err)
	}
	w := do(h, "GET", "/api/v1/info", "", false)
	if !strings.Contains(w.Body.String(), `"tailscaleIp":"100.101.102.103"`) || strings.Contains(w.Body.String(), "tskey") {
		t.Fatalf("%s", w.Body)
	}
}

func TestJoinEndpointsAndMemberManagementNeedAnAdminPhone(t *testing.T) {
	s, h := newServerAndHandler(t)
	// Not set up: requests are refused politely.
	if w := do(h, "POST", "/api/v1/join", `{"name":"dana"}`, false); w.Code != 400 {
		t.Fatalf("got %d %s", w.Code, w.Body)
	}
	s.Store.Update(func(st *state.State) error {
		st.Config.ImmichAdminPass = "x"
		st.Devices = []state.Device{{ID: "dev_a", User: "camille", TokenHash: hashForTest("secret")}}
		return nil
	})
	w := do(h, "POST", "/api/v1/join", `{"name":"dana","device":"Pixel"}`, false)
	if w.Code != 201 {
		t.Fatalf("got %d %s", w.Code, w.Body)
	}
	var created struct{ ID, Token string }
	json.Unmarshal(w.Body.Bytes(), &created)

	r := httptest.NewRequest("GET", "/api/v1/join/"+created.ID, nil)
	r.Header.Set("X-ICS-Token", created.Token)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, r)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"status":"pending"`) {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}

	manage := func(secret string) int {
		r := httptest.NewRequest("GET", "/api/v1/manage/requests", nil)
		r.RemoteAddr = "192.168.1.30:5000"
		r.Header.Set("Authorization", "Device dev_a:"+secret)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, r)
		return rec.Code
	}
	if c := manage("wrong"); c != 401 {
		t.Fatalf("wrong secret: %d", c)
	}
	if c := manage("secret"); c != 403 {
		t.Fatalf("not an admin yet: %d", c)
	}
	s.SetAdmin("camille", true)
	if c := manage("secret"); c != 200 {
		t.Fatalf("admin phone: %d", c)
	}
}

func hashForTest(t string) string {
	sum := sha256.Sum256([]byte(t))
	return hex.EncodeToString(sum[:])
}

func TestMembersListNeverIncludesPasswords(t *testing.T) {
	s, h := newServerAndHandler(t)
	s.Store.Update(func(st *state.State) error {
		st.Users = []state.User{{Name: "camille", Password: "web-secret-1", IsAdmin: true}, {Name: "tom", Password: "web-secret-2"}}
		st.Devices = []state.Device{{ID: "dev_a", User: "camille", TokenHash: hashForTest("secret")}}
		return nil
	})
	r := httptest.NewRequest("GET", "/api/v1/manage/users", nil)
	r.Header.Set("Authorization", "Device dev_a:secret")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, r)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"name":"tom"`) || strings.Contains(rec.Body.String(), "web-secret") {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
}
