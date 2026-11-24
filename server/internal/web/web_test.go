package web

import (
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
	return New(s).Routes()
}

func do(h http.Handler, method, path, body string, auth bool) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.RemoteAddr = "192.168.1.20:5000"
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
	if w := do(h, "GET", "/api/admin/qr?code=ABCD-EFGH", "", true); w.Code != 200 || w.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("qr: %d", w.Code)
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
	u, _ := url.Parse(PairLink("http://192.168.1.50:8790", "ABCD-EFGH"))
	if u.Scheme != "immcloudscale" || u.Host != "pair" || u.Query().Get("code") != "ABCD-EFGH" || u.Query().Get("server") != "http://192.168.1.50:8790" {
		t.Fatal(u)
	}
	_ = time.Second
}
