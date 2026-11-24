// Package web serves the phone pairing API and the admin console.
package web

import (
	"context"
	"crypto/subtle"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/FenOps/immcloudscale/server/internal/core"
)

//go:embed ui/index.html
var indexHTML []byte

type Handler struct {
	S       *core.Server
	limiter *limiter
}

func New(s *core.Server) *Handler { return &Handler{S: s, limiter: newLimiter(10, 10*time.Minute)} }

// PairLink is what the QR code holds; scanning it with the phone's camera opens the app.
func PairLink(serverURL, code string) string {
	return "immcloudscale://pair?" + url.Values{"server": {serverURL}, "code": {code}}.Encode()
}

func (h *Handler) baseURL() string {
	c := h.S.Store.Read().Config
	return fmt.Sprintf("http://%s:%d", c.LANAddress, c.AdminPort)
}

func (h *Handler) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/info", h.info)
	mux.HandleFunc("POST /api/v1/pair", h.pair)

	admin := http.NewServeMux()
	admin.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:")
		_, _ = w.Write(indexHTML)
	})
	admin.HandleFunc("GET /api/admin/status", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, h.S.Status(r.Context()))
	})
	admin.HandleFunc("POST /api/admin/users", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ Name, Email string }
		if !readJSON(w, r, &in) {
			return
		}
		u, err := h.S.AddUser(r.Context(), in.Name, in.Email, false)
		if err != nil {
			writeErr(w, 400, err)
			return
		}
		writeJSON(w, 201, map[string]string{"name": u.Name, "password": u.Password})
	})
	admin.HandleFunc("DELETE /api/admin/users/{name}", func(w http.ResponseWriter, r *http.Request) {
		if err := h.S.RemoveUser(r.Context(), r.PathValue("name")); err != nil {
			writeErr(w, 400, err)
			return
		}
		w.WriteHeader(204)
	})
	admin.HandleFunc("POST /api/admin/pairings", func(w http.ResponseWriter, r *http.Request) {
		var in struct{ User string }
		if !readJSON(w, r, &in) {
			return
		}
		p, err := h.S.StartPairing(in.User)
		if err != nil {
			writeErr(w, 400, err)
			return
		}
		writeJSON(w, 201, map[string]any{"code": p.Code, "expires": p.Expires, "server": h.baseURL(), "link": PairLink(h.baseURL(), p.Code)})
	})
	admin.HandleFunc("GET /api/admin/qr", func(w http.ResponseWriter, r *http.Request) {
		code := r.URL.Query().Get("code")
		png, err := qrcode.Encode(PairLink(h.baseURL(), code), qrcode.Medium, 320)
		if err != nil {
			writeErr(w, 500, err)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(png)
	})
	admin.HandleFunc("DELETE /api/admin/devices/{id}", func(w http.ResponseWriter, r *http.Request) {
		if err := h.S.Revoke(r.Context(), r.PathValue("id")); err != nil {
			writeErr(w, 400, err)
			return
		}
		w.WriteHeader(204)
	})
	admin.HandleFunc("POST /api/admin/update", func(w http.ResponseWriter, r *http.Request) {
		// Pulling images can take minutes; run it in the background.
		go func() {
			if err := h.S.Update(context.Background()); err != nil {
				h.S.Log.Printf("update failed: %v", err)
			}
		}()
		writeJSON(w, 202, map[string]string{"status": "updating"})
	})
	mux.Handle("/", h.adminAuth(admin))
	return securityHeaders(mux)
}

func (h *Handler) adminAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		want := h.S.Store.Read().Config.AdminPassword
		user, pass, ok := r.BasicAuth()
		if !ok || want == "" || user != "admin" || subtle.ConstantTimeCompare([]byte(pass), []byte(want)) != 1 {
			if ok && !h.limiter.allow(clientIP(r)) {
				writeErr(w, 429, errors.New("too many attempts, try again later"))
				return
			}
			if ok {
				h.limiter.fail(clientIP(r))
			}
			w.Header().Set("WWW-Authenticate", `Basic realm="ImmCloudScale server", charset="UTF-8"`)
			writeErr(w, 401, errors.New("sign in as admin with the password shown by `ics-server setup` (or `ics-server admin-password`)"))
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (h *Handler) info(w http.ResponseWriter, r *http.Request) {
	st := h.S.Status(r.Context())
	writeJSON(w, 200, map[string]any{"product": "immcloudscale", "name": st.Hostname, "version": st.Version, "setUp": st.SetUp})
}

func (h *Handler) pair(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	if !h.limiter.allow(ip) {
		writeErr(w, 429, errors.New("too many wrong codes; wait a few minutes"))
		return
	}
	var req core.PairRequest
	if !readJSON(w, r, &req) {
		return
	}
	res, err := h.S.Pair(r.Context(), req)
	if errors.Is(err, core.ErrBadCode) {
		h.limiter.fail(ip)
		writeErr(w, 403, err)
		return
	}
	if err != nil {
		writeErr(w, 500, err)
		return
	}
	writeJSON(w, 200, res)
}

// ---- helpers ----

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeErr(w, 400, fmt.Errorf("invalid JSON: %w", err))
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, err error) {
	writeJSON(w, code, map[string]string{"error": strings.TrimSpace(err.Error())})
}

// limiter counts failures per IP in a sliding window.
type limiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	hits   map[string][]time.Time
}

func newLimiter(max int, window time.Duration) *limiter {
	return &limiter{max: max, window: window, hits: map[string][]time.Time{}}
}

func (l *limiter) prune(ip string, now time.Time) []time.Time {
	keep := l.hits[ip][:0]
	for _, t := range l.hits[ip] {
		if now.Sub(t) < l.window {
			keep = append(keep, t)
		}
	}
	l.hits[ip] = keep
	return keep
}

func (l *limiter) allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.prune(ip, time.Now())) < l.max
}

func (l *limiter) fail(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.hits[ip] = append(l.prune(ip, time.Now()), time.Now())
}
