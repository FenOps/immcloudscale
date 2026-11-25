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
	"os"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/FenOps/immcloudscale/server/internal/core"
	"github.com/FenOps/immcloudscale/server/internal/state"
)

//go:embed ui/index.html
var indexHTML []byte

type Handler struct {
	S         *core.Server
	pairLimit *limiter // wrong pairing codes per IP
	authLimit *limiter // wrong admin passwords per IP
	joinLimit *limiter // join requests per IP
}

func New(s *core.Server) *Handler {
	return &Handler{S: s, pairLimit: newLimiter(10, 10*time.Minute), authLimit: newLimiter(10, 10*time.Minute), joinLimit: newLimiter(5, 10*time.Minute)}
}

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
	mux.HandleFunc("POST /api/v1/join", h.join)
	mux.HandleFunc("GET /api/v1/join/{id}", h.joinStatus)

	// Phones manage members with the secret they got at pairing; only admins' phones get past adminOnly.
	mux.Handle("GET /api/v1/me", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		pending := 0
		if u.IsAdmin {
			for _, x := range h.S.Requests() {
				if x.Status == "pending" {
					pending++
				}
			}
		}
		writeJSON(w, 200, map[string]any{"name": u.Name, "isAdmin": u.IsAdmin, "pendingRequests": pending})
	}))
	mux.Handle("POST /api/v1/me/session", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		tok, err := h.S.UserSession(r.Context(), u.Name)
		if err != nil {
			writeErr(w, 502, err)
			return
		}
		writeJSON(w, 200, map[string]string{"accessToken": tok})
	}))
	mux.Handle("GET /api/v1/manage/features", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		writeJSON(w, 200, h.S.Features())
	}))
	mux.Handle("POST /api/v1/manage/features", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct{ MachineLearning bool }
		if !readJSON(w, r, &in) {
			return
		}
		if err := h.S.SetMachineLearning(in.MachineLearning); err != nil {
			writeErr(w, 409, err)
			return
		}
		writeJSON(w, 202, h.S.Features())
	}))
	mux.Handle("GET /api/v1/manage/requests", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		writeJSON(w, 200, h.S.Requests())
	}))
	mux.Handle("POST /api/v1/manage/requests/{id}/approve", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		h.approve(w, r, u.Name)
	}))
	mux.Handle("POST /api/v1/manage/requests/{id}/decline", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		h.decline(w, r, u.Name)
	}))
	mux.Handle("GET /api/v1/manage/users", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		writeJSON(w, 200, h.S.Status(r.Context()).Users)
	}))
	mux.Handle("POST /api/v1/manage/users/{name}/admin", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		h.setAdmin(w, r)
	}))
	mux.Handle("POST /api/v1/manage/users/{name}/pair", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		h.startPairing(w, r.PathValue("name"))
	}))

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
		h.startPairing(w, in.User)
	})
	admin.HandleFunc("GET /api/admin/requests", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, h.S.Requests())
	})
	admin.HandleFunc("POST /api/admin/requests/{id}/approve", func(w http.ResponseWriter, r *http.Request) {
		h.approve(w, r, "admin console")
	})
	admin.HandleFunc("POST /api/admin/requests/{id}/decline", func(w http.ResponseWriter, r *http.Request) {
		h.decline(w, r, "admin console")
	})
	admin.HandleFunc("POST /api/admin/users/{name}/admin", h.setAdmin)
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
	mux.Handle("/", h.adminAuth(sameOriginOnly(admin)))
	return securityHeaders(mux)
}

func (h *Handler) startPairing(w http.ResponseWriter, user string) {
	p, err := h.S.StartPairing(user)
	if err != nil {
		writeErr(w, 400, err)
		return
	}
	writeJSON(w, 201, map[string]any{"code": p.Code, "expires": p.Expires, "server": h.baseURL(), "link": PairLink(h.baseURL(), p.Code)})
}

func (h *Handler) approve(w http.ResponseWriter, r *http.Request, by string) {
	req, err := h.S.ApproveJoin(r.Context(), r.PathValue("id"), by)
	if err != nil {
		writeErr(w, 400, err)
		return
	}
	writeJSON(w, 200, map[string]any{"request": req, "server": h.baseURL(), "link": PairLink(h.baseURL(), req.Code)})
}

func (h *Handler) decline(w http.ResponseWriter, r *http.Request, by string) {
	if err := h.S.DeclineJoin(r.PathValue("id"), by); err != nil {
		writeErr(w, 400, err)
		return
	}
	w.WriteHeader(204)
}

func (h *Handler) setAdmin(w http.ResponseWriter, r *http.Request) {
	var in struct{ Admin bool }
	if !readJSON(w, r, &in) {
		return
	}
	if err := h.S.SetAdmin(r.PathValue("name"), in.Admin); err != nil {
		writeErr(w, 400, err)
		return
	}
	w.WriteHeader(204)
}

// join takes a request for an account from someone's app on the home network (or the tailnet).
func (h *Handler) join(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	if !h.joinLimit.allow(ip) {
		writeErr(w, 429, errors.New("too many requests from this device; wait a few minutes"))
		return
	}
	var in struct{ Name, Email, Device string }
	if !readJSON(w, r, &in) {
		return
	}
	h.joinLimit.fail(ip) // every request counts, accepted or not
	id, token, err := h.S.RequestJoin(in.Name, in.Email, in.Device)
	if err != nil {
		writeErr(w, 400, err)
		return
	}
	writeJSON(w, 201, map[string]string{"id": id, "token": token})
}

// joinStatus lets the requester's app check its request; once approved it gets the pairing code.
func (h *Handler) joinStatus(w http.ResponseWriter, r *http.Request) {
	status, code, err := h.S.JoinStatus(r.PathValue("id"), r.Header.Get("X-ICS-Token"))
	if errors.Is(err, core.ErrBadRequestToken) {
		writeErr(w, 404, err)
		return
	}
	if err != nil {
		writeErr(w, 500, err)
		return
	}
	out := map[string]string{"status": status, "server": h.baseURL()}
	if code != "" {
		out["code"] = code
	}
	writeJSON(w, 200, out)
}

// deviceAuth checks "Authorization: Device <id>:<secret>" (the phone's secret from pairing).
func (h *Handler) deviceAuth(adminOnly bool, next func(http.ResponseWriter, *http.Request, state.User)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r)
		if !h.authLimit.allow(ip) {
			writeErr(w, 429, errors.New("too many attempts, try again later"))
			return
		}
		cred, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Device ")
		id, secret, _ := strings.Cut(cred, ":")
		u, err := h.S.DeviceUser(id, secret)
		if err != nil {
			h.authLimit.fail(ip)
			writeErr(w, 401, err)
			return
		}
		if adminOnly && !u.IsAdmin {
			writeErr(w, 403, core.ErrNotAdmin)
			return
		}
		next(w, r, u)
	})
}

// sameOriginOnly blocks cross-site requests: browsers resend Basic auth automatically, so without this
// any web page could make the admin's browser add users or start pairings. Changes must come from
// the console's own fetch() calls, which send X-ICS-Request (a header other sites can't add).
func sameOriginOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Sec-Fetch-Site") == "cross-site" {
			writeErr(w, http.StatusForbidden, errors.New("cross-site request refused"))
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead && r.Header.Get("X-ICS-Request") != "1" {
			writeErr(w, http.StatusForbidden, errors.New("missing X-ICS-Request header"))
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (h *Handler) adminAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		want := h.S.Store.Read().Config.AdminPassword
		user, pass, ok := r.BasicAuth()
		if !ok || want == "" || user != "admin" || subtle.ConstantTimeCompare([]byte(pass), []byte(want)) != 1 {
			if ok && !h.authLimit.allow(clientIP(r)) {
				writeErr(w, 429, errors.New("too many attempts, try again later"))
				return
			}
			if ok {
				h.authLimit.fail(clientIP(r))
			}
			w.Header().Set("WWW-Authenticate", `Basic realm="ImmCloudScale server", charset="UTF-8"`)
			writeErr(w, 401, errors.New("sign in as admin with the password shown by `ics-server setup` (or `ics-server admin-password`)"))
			return
		}
		next.ServeHTTP(w, r)
	})
}

// info is public and cheap: it only reads stored settings (no Docker or Immich calls).
// Phones use "id" to check they are talking to their own server before sending credentials.
func (h *Handler) info(w http.ResponseWriter, r *http.Request) {
	c := h.S.Store.Read().Config
	host, _ := os.Hostname()
	info := map[string]any{
		"product": "immcloudscale", "name": host, "version": core.Version, "setUp": c.ImmichAdminPass != "", "id": c.ServerID,
		"machineLearning": c.MachineLearning, // face recognition, smart search and duplicates need it
	}
	// The Tailscale address only (never the auth key): phones paired before Tailscale was set up learn it here.
	if h.S.TailscaleIP != nil {
		if ip := h.S.TailscaleIP(); ip != "" {
			info["tailscaleIp"] = ip
		}
	}
	writeJSON(w, 200, info)
}

func (h *Handler) pair(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	if !h.pairLimit.allow(ip) {
		writeErr(w, 429, errors.New("too many wrong codes; wait a few minutes"))
		return
	}
	var req core.PairRequest
	if !readJSON(w, r, &req) {
		return
	}
	res, err := h.S.Pair(r.Context(), req)
	if errors.Is(err, core.ErrBadCode) {
		h.pairLimit.fail(ip)
		writeErr(w, 403, err)
		return
	}
	if errors.Is(err, core.ErrUserGone) {
		writeErr(w, http.StatusGone, err)
		return
	}
	if errors.Is(err, core.ErrBadKey) {
		writeErr(w, 400, err)
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
	if ct := r.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		writeErr(w, http.StatusUnsupportedMediaType, errors.New("send JSON with Content-Type: application/json"))
		return false
	}
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
	if len(keep) == 0 {
		delete(l.hits, ip) // don't let the map grow with every address ever seen
		return nil
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
