package core

import (
	"context"
	"net/netip"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/state"
)

// TailscaleInfo tells a phone how to reach this server through Tailscale, which works on home connections
// where the WireGuard VPN can't (no port forwarding, or an internet address shared with other customers).
type TailscaleInfo struct {
	IP string `json:"ip"` // this server's Tailscale address
	// AuthKey, when set, lets the app join the tailnet without anyone signing in on the phone.
	AuthKey    string `json:"authKey,omitempty"`
	ControlURL string `json:"controlUrl,omitempty"` // empty for Tailscale's own servers, else e.g. Headscale
}

// tailscaleIPCmd asks the local Tailscale for this machine's tailnet IPv4 address. It returns "" when
// Tailscale isn't installed, running or signed in.
func tailscaleIPCmd() string {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "tailscale", "ip", "-4").Output()
	if err != nil {
		return ""
	}
	ip, err := netip.ParseAddr(strings.TrimSpace(strings.SplitN(string(out), "\n", 2)[0]))
	if err != nil || !ip.Is4() {
		return ""
	}
	return ip.String()
}

// cachedTailscaleIP remembers the answer for a minute: the pairing service asks on every info request.
func cachedTailscaleIP(get func() string) func() string {
	var mu sync.Mutex
	var ip string
	var at time.Time
	return func() string {
		mu.Lock()
		defer mu.Unlock()
		if time.Since(at) > time.Minute {
			ip, at = get(), time.Now()
		}
		return ip
	}
}

// tailscaleInfo is what pairing hands out, or nil when this server isn't on a tailnet.
func (s *Server) tailscaleInfo() *TailscaleInfo {
	if s.TailscaleIP == nil {
		return nil
	}
	ip := s.TailscaleIP()
	if ip == "" {
		return nil
	}
	c := s.Store.Read().Config
	return &TailscaleInfo{IP: ip, AuthKey: c.TailscaleAuthKey, ControlURL: c.TailscaleControlURL}
}

// SetTailscaleKey stores (or with key "" removes) the auth key that phones get when they pair.
func (s *Server) SetTailscaleKey(key, controlURL string) error {
	return s.Store.Update(func(st *state.State) error {
		st.Config.TailscaleAuthKey, st.Config.TailscaleControlURL = key, controlURL
		return nil
	})
}
