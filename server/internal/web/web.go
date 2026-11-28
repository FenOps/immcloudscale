// Package web serves the phone pairing API and the admin console.
package web

import (
	"context"
	"crypto/subtle"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/FenOps/immcloudscale/server/internal/bichon"
	"github.com/FenOps/immcloudscale/server/internal/core"
	"github.com/FenOps/immcloudscale/server/internal/state"
)

//go:embed ui/index.html
var indexHTML []byte

type Handler struct {
	S            *core.Server
	pairLimit    *limiter // wrong pairing codes per IP
	authLimit    *limiter // wrong admin passwords per IP
	joinLimit    *limiter // join requests per IP
	sessionLimit *limiter // locked-folder sessions per phone
	// wrong phone secrets per IP (separate from the console, so a misbehaving phone can't lock the admin out)
	deviceLimit *limiter
}

func New(s *core.Server) *Handler {
	return &Handler{S: s, pairLimit: newLimiter(10, 10*time.Minute), authLimit: newLimiter(10, 10*time.Minute), joinLimit: newLimiter(5, 10*time.Minute), sessionLimit: newLimiter(10, time.Hour), deviceLimit: newLimiter(10, 10*time.Minute)}
}

// PairLink is what the QR code and invites hold; opening it on the phone opens the app. It says until when the
// code works, and (with ts) how to reach the server through Tailscale, so it also works away from home.
func PairLink(serverURL, code string, expires time.Time, ts *core.TailscaleInfo) string {
	v := url.Values{"server": {serverURL}, "code": {code}}
	if !expires.IsZero() {
		v.Set("exp", strconv.FormatInt(expires.Unix(), 10))
	}
	if ts != nil {
		v.Set("ts", ts.IP)
		if ts.AuthKey != "" {
			v.Set("tskey", ts.AuthKey)
		}
		if ts.ControlURL != "" {
			v.Set("control", ts.ControlURL)
		}
	}
	return "immcloudscale://pair?" + v.Encode()
}

// link is PairLink for this server. Only invites sent from the app carry the Tailscale join key (they're meant to
// travel by message); other codes are used at home, and pairing hands the key to the phone anyway.
func (h *Handler) link(p state.Pairing) string {
	ts := h.S.TailscaleJoin()
	if ts != nil && p.CreatedBy == "" {
		ts = &core.TailscaleInfo{IP: ts.IP, ControlURL: ts.ControlURL}
	}
	return PairLink(h.baseURL(), p.Code, p.Expires, ts)
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
		dev := deviceID(r)
		if !h.sessionLimit.allow(dev) {
			writeErr(w, 429, errors.New("too many sign-ins from this phone; try again later"))
			return
		}
		h.sessionLimit.fail(dev) // every session counts
		tok, err := h.S.DeviceSession(r.Context(), dev)
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
		var in struct{ MachineLearning *bool }
		if !readJSON(w, r, &in) {
			return
		}
		if in.MachineLearning == nil {
			writeErr(w, 400, errors.New("say whether machineLearning should be on or off"))
			return
		}
		if err := h.S.SetMachineLearning(*in.MachineLearning); err != nil {
			writeErr(w, 409, err)
			return
		}
		writeJSON(w, 202, h.S.Features())
	}))
	mux.Handle("POST /api/v1/manage/invites", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct{ Name, Email string }
		if !readJSON(w, r, &in) {
			return
		}
		p, created, err := h.S.Invite(r.Context(), in.Name, in.Email, u.Name)
		if err != nil {
			writeErr(w, 400, err)
			return
		}
		writeJSON(w, 201, map[string]any{"name": p.User, "code": p.Code, "expires": p.Expires, "server": h.baseURL(), "link": h.link(p), "created": created})
	}))
	mux.Handle("GET /api/v1/manage/invites", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		type invite struct {
			Name      string    `json:"name"`
			Code      string    `json:"code"`
			Expires   time.Time `json:"expires"`
			CreatedBy string    `json:"createdBy"`
		}
		out := []invite{}
		for _, p := range h.S.SentInvites() {
			out = append(out, invite{p.User, p.Code, p.Expires, p.CreatedBy})
		}
		writeJSON(w, 200, out)
	}))
	mux.Handle("DELETE /api/v1/manage/invites/{code}", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		if err := h.S.CancelInvite(r.PathValue("code")); err != nil {
			writeErr(w, 400, err)
			return
		}
		w.WriteHeader(204)
	}))
	// Email archive: each person sees and searches only their own mailboxes (kept in Bichon on this NAS).
	mux.Handle("GET /api/v1/me/mail", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		writeJSON(w, 200, h.S.MailStatus(r.Context(), u.Name, u.IsAdmin))
	}))
	mux.Handle("POST /api/v1/me/mail/accounts", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in core.NewMailbox
		if !readJSON(w, r, &in) {
			return
		}
		owner := strings.ToLower(strings.TrimSpace(in.For))
		if owner == "" {
			owner = u.Name
		}
		if owner != u.Name && !u.IsAdmin {
			writeErr(w, 403, core.ErrNotAdmin)
			return
		}
		a, err := h.S.AddMail(r.Context(), owner, in)
		if err != nil {
			writeMailErr(w, err)
			return
		}
		writeJSON(w, 201, a)
	}))
	mux.Handle("POST /api/v1/me/mail/accounts/{id}/pause", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct{ Paused bool }
		if !readJSON(w, r, &in) {
			return
		}
		id, ok := mailID(w, r.PathValue("id"))
		if ok {
			writeMailErr(w, h.S.PauseMail(r.Context(), u.Name, id, in.Paused))
		}
	}))
	mux.Handle("POST /api/v1/me/mail/accounts/{id}/password", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct{ Password string }
		if !readJSON(w, r, &in) {
			return
		}
		if id, ok := mailID(w, r.PathValue("id")); ok {
			writeMailErr(w, h.S.SetMailPassword(r.Context(), u.Name, id, in.Password))
		}
	}))
	mux.Handle("DELETE /api/v1/me/mail/accounts/{id}", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		if id, ok := mailID(w, r.PathValue("id")); ok {
			writeMailErr(w, h.S.RemoveMail(r.Context(), u.Name, id))
		}
	}))
	mux.Handle("POST /api/v1/manage/mail/accounts/{id}/assign", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct{ Person string }
		if !readJSON(w, r, &in) {
			return
		}
		if id, ok := mailID(w, r.PathValue("id")); ok {
			writeMailErr(w, h.S.AssignMail(r.Context(), id, strings.ToLower(strings.TrimSpace(in.Person))))
		}
	}))
	mux.Handle("POST /api/v1/me/mail/search", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var q core.MailQuery
		if !readJSON(w, r, &q) {
			return
		}
		p, err := h.S.SearchMail(r.Context(), u.Name, q)
		if err != nil {
			writeMailErr(w, err)
			return
		}
		writeJSON(w, 200, p)
	}))
	mux.Handle("GET /api/v1/me/mail/messages/{account}/{id}", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		account, ok := mailID(w, r.PathValue("account"))
		if !ok {
			return
		}
		m, err := h.S.ReadMail(r.Context(), u.Name, account, r.PathValue("id"), r.URL.Query().Get("remote") == "1")
		if err != nil {
			writeMailErr(w, err)
			return
		}
		writeJSON(w, 200, m)
	}))
	mux.Handle("GET /api/v1/me/mail/messages/{account}/{id}/attachments/{hash}", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		account, ok := mailID(w, r.PathValue("account"))
		if !ok {
			return
		}
		body, size, err := h.S.MailAttachment(r.Context(), u.Name, account, r.PathValue("id"), r.PathValue("hash"))
		if err != nil {
			writeMailErr(w, err)
			return
		}
		defer body.Close()
		w.Header().Set("Content-Type", "application/octet-stream")
		if size > 0 {
			w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
		}
		_, _ = io.Copy(w, body)
	}))
	mux.Handle("POST /api/v1/me/mail/delete", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct {
			Messages []struct {
				Account uint64 `json:"account,string"`
				ID      string `json:"id"`
			} `json:"messages"`
		}
		if !readJSON(w, r, &in) {
			return
		}
		ids := map[uint64][]string{}
		for _, m := range in.Messages {
			ids[m.Account] = append(ids[m.Account], m.ID)
		}
		res, err := h.S.DeleteMail(r.Context(), u.Name, ids)
		if err != nil {
			writeMailErr(w, err)
			return
		}
		writeJSON(w, 200, res)
	}))
	// Moving away from Google: each person connects their own accounts; admins add the Google API key.
	mux.Handle("GET /api/v1/me/google", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		writeJSON(w, 200, h.S.GoogleStatus(u.Name))
	}))
	mux.Handle("POST /api/v1/manage/google/key", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct{ ClientID, ClientSecret string }
		if !readJSON(w, r, &in) {
			return
		}
		if err := h.S.SetGoogleKey(in.ClientID, in.ClientSecret); err != nil {
			writeErr(w, 400, err)
			return
		}
		w.WriteHeader(204)
	}))
	mux.Handle("POST /api/v1/me/google/connect", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct{ For string }
		if !readJSON(w, r, &in) {
			return
		}
		owner := strings.ToLower(strings.TrimSpace(in.For))
		if owner == "" {
			owner = u.Name
		}
		if owner != u.Name && !u.IsAdmin {
			writeErr(w, 403, core.ErrNotAdmin)
			return
		}
		link, err := h.S.StartGoogleLogin(u.Name, owner)
		if err != nil {
			writeErr(w, 400, err)
			return
		}
		writeJSON(w, 200, map[string]string{"url": link})
	}))
	mux.Handle("POST /api/v1/me/google/finish", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		var in struct{ URL string }
		if !readJSON(w, r, &in) {
			return
		}
		a, err := h.S.FinishGoogleLogin(r.Context(), u.Name, in.URL)
		if err != nil {
			writeErr(w, 400, err)
			return
		}
		writeJSON(w, 201, a)
	}))
	mux.Handle("POST /api/v1/me/google/accounts/{id}/sync", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		if err := h.S.SyncGoogleNow(u.Name, r.PathValue("id")); err != nil {
			writeErr(w, 404, err)
			return
		}
		w.WriteHeader(202)
	}))
	mux.Handle("DELETE /api/v1/me/google/accounts/{id}", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		if err := h.S.RemoveGoogle(u.Name, r.PathValue("id"), u.IsAdmin); err != nil {
			writeErr(w, 404, err)
			return
		}
		w.WriteHeader(204)
	}))
	mux.Handle("POST /api/v1/me/google/import", h.deviceAuth(false, func(w http.ResponseWriter, r *http.Request, u state.User) {
		h.S.ImportNow(u.Name)
		w.WriteHeader(202)
	}))
	mux.Handle("POST /api/v1/manage/users/{name}/iphone-invite", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		inv, err := h.S.InviteToIPhone(r.Context(), r.PathValue("name"))
		switch {
		case errors.Is(err, core.ErrNoPerson), errors.Is(err, core.ErrUserGone):
			writeErr(w, 404, err)
		case err != nil:
			writeErr(w, 502, err) // Photos (Immich) didn't answer
		default:
			writeJSON(w, 201, inv)
		}
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
		writeJSON(w, 200, h.S.Members())
	}))
	mux.Handle("POST /api/v1/manage/users/{name}/admin", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		h.setAdmin(w, r)
	}))
	mux.Handle("POST /api/v1/manage/users/{name}/pair", h.deviceAuth(true, func(w http.ResponseWriter, r *http.Request, u state.User) {
		// A code for a member's phone sent from the app works like an invite (it can be sent by message), except
		// for admins' phones, which are paired at home.
		name := r.PathValue("name")
		if st := h.S.Store.Read(); st.FindUser(name) != nil && !st.FindUser(name).IsAdmin {
			p, err := h.S.StartPairingBy(name, u.Name)
			if err != nil {
				writeErr(w, 400, err)
				return
			}
			writeJSON(w, 201, map[string]any{"name": p.User, "code": p.Code, "expires": p.Expires, "server": h.baseURL(), "link": h.link(p)})
			return
		}
		h.startPairing(w, name)
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
	admin.HandleFunc("POST /api/admin/users/{name}/iphone", func(w http.ResponseWriter, r *http.Request) {
		setup, err := h.S.IPhoneSetup(r.Context(), r.PathValue("name"))
		switch {
		case errors.Is(err, core.ErrNoPerson):
			writeErr(w, 404, err)
			return
		case err != nil:
			writeErr(w, 502, err) // Files (Nextcloud) didn't answer
			return
		}
		png, err := qrcode.Encode(setup.FilesLink, qrcode.Medium, 320)
		if err != nil {
			writeErr(w, 500, err)
			return
		}
		writeJSON(w, 200, map[string]any{"setup": setup, "filesQr": "data:image/png;base64," + base64.StdEncoding.EncodeToString(png)})
	})
	admin.HandleFunc("GET /api/admin/qr", func(w http.ResponseWriter, r *http.Request) {
		code := r.URL.Query().Get("code")
		var p state.Pairing
		for _, x := range h.S.Invites() {
			if x.Code == code {
				p = x
			}
		}
		if p.Code == "" {
			writeErr(w, 404, errors.New("that code was used or has expired"))
			return
		}
		png, err := qrcode.Encode(h.link(p), qrcode.Medium, 320)
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
	// Phone API paths that don't exist: a plain 404, not the console's password prompt.
	mux.HandleFunc("/api/v1/", func(w http.ResponseWriter, r *http.Request) { writeErr(w, 404, errors.New("no such API")) })
	mux.Handle("/", h.adminAuth(sameOriginOnly(admin)))
	return securityHeaders(mux)
}

func (h *Handler) startPairing(w http.ResponseWriter, user string) {
	p, err := h.S.StartPairing(user)
	if err != nil {
		writeErr(w, 400, err)
		return
	}
	writeJSON(w, 201, map[string]any{"code": p.Code, "expires": p.Expires, "server": h.baseURL(), "link": h.link(p)})
}

func (h *Handler) approve(w http.ResponseWriter, r *http.Request, by string) {
	req, err := h.S.ApproveJoin(r.Context(), r.PathValue("id"), by)
	if err != nil {
		writeErr(w, 400, err)
		return
	}
	p := state.Pairing{Code: req.Code}
	for _, x := range h.S.Invites() {
		if x.Code == req.Code {
			p = x
		}
	}
	writeJSON(w, 200, map[string]any{"request": req, "server": h.baseURL(), "link": h.link(p)})
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

// deviceID is the phone a deviceAuth-checked request came from.
func deviceID(r *http.Request) string {
	cred, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Device ")
	id, _, _ := strings.Cut(cred, ":")
	return id
}

// deviceAuth checks "Authorization: Device <id>:<secret>" (the phone's secret from pairing).
func (h *Handler) deviceAuth(adminOnly bool, next func(http.ResponseWriter, *http.Request, state.User)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r)
		if !h.deviceLimit.allow(ip) {
			writeErr(w, 429, errors.New("too many attempts, try again later"))
			return
		}
		cred, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Device ")
		id, secret, _ := strings.Cut(cred, ":")
		u, err := h.S.DeviceUser(id, secret)
		if err != nil {
			h.deviceLimit.fail(ip)
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

func mailID(w http.ResponseWriter, s string) (uint64, bool) {
	id, err := strconv.ParseUint(s, 10, 64)
	if err != nil {
		writeErr(w, 400, errors.New("bad mailbox id"))
		return 0, false
	}
	return id, true
}

// writeMailErr answers 204 for nil, else a status that says whose fault it was.
func writeMailErr(w http.ResponseWriter, err error) {
	var be *bichon.APIError
	switch {
	case err == nil:
		w.WriteHeader(204)
	case errors.Is(err, core.ErrNotYourMail):
		writeErr(w, 404, err) // the same as a mailbox that doesn't exist: nothing to learn about others'
	case errors.Is(err, core.ErrNoPerson):
		writeErr(w, 404, err)
	case errors.Is(err, core.ErrMailNotSetUp):
		writeErr(w, 503, err)
	case errors.Is(err, core.ErrNeedMailPassword):
		writeErr(w, 409, err)
	case errors.As(err, &be) && be.Status == 404:
		writeErr(w, 404, errors.New("that message isn't in the archive any more"))
	case errors.As(err, &be):
		writeErr(w, 502, err) // Bichon refused or failed
	default:
		writeErr(w, 400, err)
	}
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
