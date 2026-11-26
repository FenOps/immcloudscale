// Package tsbridge runs Tailscale inside the app, without the Android VPN slot or the Tailscale app, and
// serves a local HTTP proxy that carries the app's requests for the home server through the tailnet.
//
// The app builds it into an Android library with gomobile (see build-aar.sh), so the exported API sticks
// to types gomobile can bind: strings, ints, bools and errors.
package tsbridge

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "tailscale.com/feature/androidbin" // interfaces, DNS and CA roots for code running as an Android app
	"tailscale.com/tsnet"
)

var (
	mu     sync.Mutex
	srv    *tsnet.Server
	ln     net.Listener
	token  string
	routes = map[string]string{} // LAN host -> tailnet host

	// Separate from mu: Tailscale logs while Start holds mu.
	logMu sync.Mutex
	logs  []string
)

// Start joins the tailnet. dir keeps this phone's Tailscale identity between runs. authKey may be empty:
// the phone then needs a sign-in, whose page AuthURL returns. controlURL is empty for Tailscale's servers,
// or a Headscale address. Calling Start again while running does nothing.
func Start(dir, hostname, authKey, controlURL string) (err error) {
	// A panic while starting would close the whole app; report it as an error instead.
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("Tailscale couldn't start: %v", r)
		}
	}()
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return nil
	}
	if err := useAppDirs(dir); err != nil {
		return err
	}
	s := &tsnet.Server{
		Dir:      dir,
		Hostname: hostname,
		// The phone leaves the tailnet when it goes offline for a while, so reinstalls and lost phones don't pile up.
		Ephemeral:  true,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Logf:       func(string, ...any) {},
		UserLogf:   keepLog,
	}
	if err := s.Start(); err != nil {
		return err
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		s.Close()
		return err
	}
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		s.Close()
		l.Close()
		return err
	}
	srv, ln, token = s, l, hex.EncodeToString(b)
	go http.Serve(l, proxy(s, token))
	return nil
}

// useAppDirs gives Tailscale's logs a place inside the app's own storage. An Android app has no home, cache or
// writable temp directory, and without one Tailscale's log setup panics ("no safe place found to store log state").
func useAppDirs(dir string) error {
	logs := filepath.Join(dir, "logs")
	if err := os.MkdirAll(logs, 0o700); err != nil {
		return err
	}
	return os.Setenv("TS_LOGS_DIR", logs)
}

// Stop leaves the tailnet until the next Start. The phone stays signed in. Routes are forgotten: they belong to
// the tailnet that was connected, and the next Start sets its own.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	routes = map[string]string{}
	if srv == nil {
		return
	}
	ln.Close()
	srv.Close()
	srv, ln, token = nil, nil, ""
}

// Logout signs this phone out of the tailnet and stops. The next Start needs a new sign-in or key.
func Logout() error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s != nil {
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			if err := lc.Logout(ctx); err != nil {
				return err
			}
		}
	}
	Stop()
	return nil
}

// SetRoute sends requests for lanHost (the server's home address, as the app knows it) to tailnetHost
// (the server's Tailscale address). Only routed hosts can be reached: the proxy never opens the rest of the tailnet.
func SetRoute(lanHost, tailnetHost string) {
	mu.Lock()
	defer mu.Unlock()
	if tailnetHost == "" {
		delete(routes, lanHost)
	} else {
		routes[lanHost] = tailnetHost
	}
}

// ProxyPort is the local proxy's port, or 0 when stopped.
func ProxyPort() int {
	mu.Lock()
	defer mu.Unlock()
	if ln == nil {
		return 0
	}
	return ln.Addr().(*net.TCPAddr).Port
}

// ProxyToken must be sent as "Proxy-Authorization: Bearer <token>", so other apps on the phone can't use
// the proxy to reach the tailnet.
func ProxyToken() string {
	mu.Lock()
	defer mu.Unlock()
	return token
}

// Status describes the connection as JSON: {"state", "authURL", "ip"}. state is one of Stopped,
// Starting, NeedsLogin, NeedsMachineAuth, Running.
func Status() string {
	mu.Lock()
	s := srv
	mu.Unlock()
	out := map[string]string{"state": "Stopped"}
	if s != nil {
		out["state"] = "Starting"
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			st, err := lc.StatusWithoutPeers(ctx)
			cancel()
			if err == nil {
				out["state"] = st.BackendState
				out["authURL"] = st.AuthURL
				if len(st.TailscaleIPs) > 0 {
					out["ip"] = st.TailscaleIPs[0].String()
				}
			}
		}
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// Logs returns Tailscale's recent messages, newest last, for the app's troubleshooting screen.
func Logs() string {
	logMu.Lock()
	defer logMu.Unlock()
	return strings.Join(logs, "\n")
}

func keepLog(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	logMu.Lock()
	defer logMu.Unlock()
	logs = append(logs, line)
	if len(logs) > 200 {
		logs = logs[len(logs)-200:]
	}
}

// target is where addr really goes: the server's Tailscale address for its home address, or for itself.
func target(addr string) (string, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", err
	}
	mu.Lock()
	defer mu.Unlock()
	h := strings.ToLower(host)
	if t, ok := routes[h]; ok {
		return net.JoinHostPort(t, port), nil
	}
	for _, t := range routes {
		if t == h {
			return addr, nil
		}
	}
	return "", fmt.Errorf("%s isn't your server", host)
}

// proxy handles plain HTTP requests (absolute URLs, as clients send them to a proxy) and CONNECT
// tunnels, dialling the destination through the tailnet.
func proxy(s *tsnet.Server, tok string) http.Handler {
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		to, err := target(addr)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		return s.Dial(ctx, network, to)
	}
	forward := &httputil.ReverseProxy{
		Rewrite: func(r *httputil.ProxyRequest) {
			r.Out.Header.Del("Proxy-Authorization")
			r.Out.Host = r.In.Host
		},
		Transport: &http.Transport{
			DialContext:           dial,
			ResponseHeaderTimeout: 2 * time.Minute,
			MaxIdleConnsPerHost:   8,
			IdleConnTimeout:       90 * time.Second,
		},
		FlushInterval: -1, // stream video and big downloads as they arrive
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			http.Error(w, "Can't reach your server through Tailscale: "+err.Error(), http.StatusBadGateway)
		},
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got, ok := strings.CutPrefix(r.Header.Get("Proxy-Authorization"), "Bearer ")
		if !ok || subtle.ConstantTimeCompare([]byte(got), []byte(tok)) != 1 {
			w.Header().Set("Proxy-Authenticate", `Bearer realm="immcloudscale"`)
			http.Error(w, "proxy authorization required", http.StatusProxyAuthRequired)
			return
		}
		if r.Method == http.MethodConnect {
			tunnel(w, r, dial)
			return
		}
		if r.URL.Host == "" {
			http.Error(w, "not a proxy request", http.StatusBadRequest)
			return
		}
		forward.ServeHTTP(w, r)
	})
}

func tunnel(w http.ResponseWriter, r *http.Request, dial func(context.Context, string, string) (net.Conn, error)) {
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	up, err := dial(ctx, "tcp", r.Host)
	cancel()
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		up.Close()
		http.Error(w, "tunnelling not supported", http.StatusInternalServerError)
		return
	}
	down, buf, err := hj.Hijack()
	if err != nil {
		up.Close()
		return
	}
	if _, err := down.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\n")); err != nil {
		up.Close()
		down.Close()
		return
	}
	go func() {
		if buf.Reader.Buffered() > 0 {
			io.CopyN(up, buf, int64(buf.Reader.Buffered()))
		}
		io.Copy(up, down)
		up.Close()
	}()
	io.Copy(down, up)
	down.Close()
}
