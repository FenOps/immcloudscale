package tsbridge

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"tailscale.com/tsnet"
)

func TestRejectsRequestsWithoutTheToken(t *testing.T) {
	h := proxy(nil, "secret")
	for _, auth := range []string{"", "Bearer wrong", "secret"} {
		r, _ := http.NewRequest("GET", "http://192.168.1.49:2283/api/server/ping", nil)
		if auth != "" {
			r.Header.Set("Proxy-Authorization", auth)
		}
		w := &recorder{header: http.Header{}}
		h.ServeHTTP(w, r)
		if w.code != http.StatusProxyAuthRequired {
			t.Errorf("auth %q: got %d, want 407", auth, w.code)
		}
	}
}

func TestRoutesTheHomeAddressToTheTailnetAddress(t *testing.T) {
	SetRoute("192.168.1.49", "100.64.0.1")
	defer SetRoute("192.168.1.49", "")
	if got, err := target("192.168.1.49:2283"); err != nil || got != "100.64.0.1:2283" {
		t.Errorf("got %s %v", got, err)
	}
	if got, err := target("100.64.0.1:8790"); err != nil || got != "100.64.0.1:8790" {
		t.Errorf("the server's own Tailscale address: got %s %v", got, err)
	}
	if _, err := target("100.64.0.7:22"); err == nil {
		t.Error("other tailnet machines must not be reachable through the proxy")
	}
}

// TestThroughATailnet joins a real tailnet (for example a local Headscale) as a stand-in server and as the
// phone, then fetches from the server's home address through the proxy. It runs only when
// TS_TEST_CONTROL_URL and TS_TEST_AUTHKEY are set.
func TestThroughATailnet(t *testing.T) {
	control, key := os.Getenv("TS_TEST_CONTROL_URL"), os.Getenv("TS_TEST_AUTHKEY")
	if control == "" || key == "" {
		t.Skip("set TS_TEST_CONTROL_URL and TS_TEST_AUTHKEY to run against a tailnet")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	nas := &tsnet.Server{Dir: t.TempDir(), Hostname: "test-nas", AuthKey: key, ControlURL: control, Logf: func(string, ...any) {}}
	defer nas.Close()
	st, err := nas.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := nas.Listen("tcp", ":2283")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "pong from %s for host %s", r.URL.Path, r.Host)
	}))
	nasIP := st.TailscaleIPs[0].String()

	if err := Start(t.TempDir(), "test-phone", key, control); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	for {
		var s map[string]string
		json.Unmarshal([]byte(Status()), &s)
		if s["state"] == "Running" {
			break
		}
		if ctx.Err() != nil {
			t.Fatalf("never connected: %v\n%s", s, Logs())
		}
		time.Sleep(200 * time.Millisecond)
	}
	SetRoute("192.168.1.49", nasIP)

	proxyURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", ProxyPort()))
	client := &http.Client{Transport: &http.Transport{
		Proxy:              http.ProxyURL(proxyURL),
		ProxyConnectHeader: http.Header{"Proxy-Authorization": {"Bearer " + ProxyToken()}},
	}}
	req, _ := http.NewRequestWithContext(ctx, "GET", "http://192.168.1.49:2283/api/server/ping", nil)
	req.Header.Set("Proxy-Authorization", "Bearer "+ProxyToken())
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !strings.Contains(string(body), "pong from /api/server/ping for host 192.168.1.49:2283") {
		t.Fatalf("got %d %q", resp.StatusCode, body)
	}
}

type recorder struct {
	header http.Header
	code   int
}

func (r *recorder) Header() http.Header         { return r.header }
func (r *recorder) Write(b []byte) (int, error) { return len(b), nil }
func (r *recorder) WriteHeader(c int)           { r.code = c }

// TestPairThroughAnInvite plays a phone away from home: with only an invite link, it joins the tailnet and pairs
// with the server through the proxy. A stand-in node forwards the server's pairing port onto the tailnet. It runs
// when TS_TEST_CONTROL_URL, TS_TEST_AUTHKEY and TS_TEST_INVITE (an invite link from a live server) are set.
func TestPairThroughAnInvite(t *testing.T) {
	control, key, invite := os.Getenv("TS_TEST_CONTROL_URL"), os.Getenv("TS_TEST_AUTHKEY"), os.Getenv("TS_TEST_INVITE")
	if control == "" || key == "" || invite == "" {
		t.Skip("set TS_TEST_CONTROL_URL, TS_TEST_AUTHKEY and TS_TEST_INVITE")
	}
	link, err := url.Parse(invite)
	if err != nil {
		t.Fatal(err)
	}
	server, code := link.Query().Get("server"), link.Query().Get("code")
	srv, _ := url.Parse(server)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	// The server's side: its pairing port, reachable on the tailnet.
	nas := &tsnet.Server{Dir: t.TempDir(), Hostname: "invite-nas", AuthKey: key, ControlURL: control, Logf: func(string, ...any) {}}
	defer nas.Close()
	st, err := nas.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := nas.Listen("tcp", ":"+srv.Port())
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				up, err := net.Dial("tcp", srv.Host)
				if err != nil {
					return
				}
				defer up.Close()
				go io.Copy(up, c)
				io.Copy(c, up)
			}()
		}
	}()

	// The phone: what the invite carries, nothing else.
	if err := Start(t.TempDir(), "invite-phone", key, control); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	for {
		var s map[string]string
		json.Unmarshal([]byte(Status()), &s)
		if s["state"] == "Running" {
			break
		}
		if ctx.Err() != nil {
			t.Fatalf("never connected: %v", s)
		}
		time.Sleep(200 * time.Millisecond)
	}
	SetRoute(srv.Hostname(), st.TailscaleIPs[0].String())

	var priv, pub [32]byte
	rand.Read(priv[:])
	curve25519.ScalarBaseMult(&pub, &priv)
	body := fmt.Sprintf(`{"code":%q,"deviceName":"Invite test","wgPublicKey":%q}`, code, base64.StdEncoding.EncodeToString(pub[:]))
	proxyURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", ProxyPort()))
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}
	req, _ := http.NewRequestWithContext(ctx, "POST", server+"/api/v1/pair", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Proxy-Authorization", "Bearer "+ProxyToken())
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	out, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !strings.Contains(string(out), `"deviceId"`) {
		t.Fatalf("pairing through the tailnet: %d %s", resp.StatusCode, out)
	}
	t.Log("paired through Tailscale")
}
