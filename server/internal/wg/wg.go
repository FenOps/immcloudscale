// Package wg manages the server side of the home VPN: keys, the wg-quick config with one
// [Peer] per paired phone, address allocation and live peer status.
package wg

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/curve25519"
)

// NewKeyPair returns a base64 WireGuard private/public key pair.
func NewKeyPair() (priv, pub string, err error) {
	var k [32]byte
	if _, err = rand.Read(k[:]); err != nil {
		return
	}
	// Clamp, as `wg genkey` does.
	k[0] &= 248
	k[31] = (k[31] & 127) | 64
	pub, err = PublicKey(base64.StdEncoding.EncodeToString(k[:]))
	return base64.StdEncoding.EncodeToString(k[:]), pub, err
}

// PublicKey derives the public key of a base64 private key.
func PublicKey(priv string) (string, error) {
	k, err := base64.StdEncoding.DecodeString(priv)
	if err != nil || len(k) != 32 {
		return "", fmt.Errorf("invalid WireGuard private key")
	}
	p, err := curve25519.X25519(k, curve25519.Basepoint)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(p), nil
}

// ValidKey reports whether s is a base64 32-byte key.
func ValidKey(s string) bool {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	return err == nil && len(b) == 32
}

// ServerAddress is the first host of the tunnel subnet (10.8.0.0/24 → 10.8.0.1).
func ServerAddress(subnet string) (netip.Addr, error) {
	p, err := netip.ParsePrefix(subnet)
	if err != nil {
		return netip.Addr{}, err
	}
	return p.Masked().Addr().Next(), nil
}

// NextAddress picks the lowest free host address after the server's.
func NextAddress(subnet string, used []string) (string, error) {
	p, err := netip.ParsePrefix(subnet)
	if err != nil {
		return "", err
	}
	taken := map[string]bool{}
	for _, u := range used {
		taken[u] = true
	}
	a := p.Masked().Addr().Next() // server
	for {
		a = a.Next()
		if !p.Contains(a) || !a.IsValid() || isBroadcast(p, a) {
			return "", fmt.Errorf("no free VPN addresses left in %s", subnet)
		}
		if !taken[a.String()] {
			return a.String(), nil
		}
	}
}

// isBroadcast reports whether a is the last address of an IPv4 prefix.
func isBroadcast(p netip.Prefix, a netip.Addr) bool {
	if !a.Is4() || p.Bits() >= 31 {
		return false
	}
	return !p.Contains(a.Next())
}

type Peer struct {
	Name      string
	PublicKey string
	Address   string // host IP, without prefix
}

type ServerConfig struct {
	Interface  string
	PrivateKey string
	Subnet     string
	Port       int
	Egress     string // interface used for NAT to the LAN and the internet
	Peers      []Peer
}

// Render produces /etc/wireguard/<iface>.conf. The PostUp rules let phones reach other LAN devices
// and use the NAS as an exit node; they are inserted first so Docker's FORWARD policy doesn't drop them.
func (c ServerConfig) Render() (string, error) {
	srv, err := ServerAddress(c.Subnet)
	if err != nil {
		return "", err
	}
	bits := netip.MustParsePrefix(c.Subnet).Bits()
	var b strings.Builder
	fmt.Fprintf(&b, "# Managed by ics-server. Changes here are overwritten.\n[Interface]\n")
	fmt.Fprintf(&b, "Address = %s/%d\nListenPort = %d\nPrivateKey = %s\n", srv, bits, c.Port, c.PrivateKey)
	if c.Egress != "" {
		fmt.Fprintf(&b, "PostUp = iptables -I FORWARD -i %%i -j ACCEPT; iptables -I FORWARD -o %%i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -I POSTROUTING -s %s -o %s -j MASQUERADE\n", c.Subnet, c.Egress)
		fmt.Fprintf(&b, "PostDown = iptables -D FORWARD -i %%i -j ACCEPT; iptables -D FORWARD -o %%i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -D POSTROUTING -s %s -o %s -j MASQUERADE\n", c.Subnet, c.Egress)
	}
	for _, p := range c.Peers {
		fmt.Fprintf(&b, "\n[Peer]\n# %s\nPublicKey = %s\nAllowedIPs = %s/32\n", p.Name, p.PublicKey, p.Address)
	}
	return b.String(), nil
}

// ClientAllowedIPs is what a phone routes through the tunnel by default: the tunnel and the home LAN.
func ClientAllowedIPs(subnet, lan string) string {
	if lan == "" || lan == subnet {
		return subnet
	}
	return subnet + ", " + lan
}

// PeerStatus is one line of `wg show <iface> dump`.
type PeerStatus struct {
	PublicKey     string    `json:"publicKey"`
	Endpoint      string    `json:"endpoint"`
	LastHandshake time.Time `json:"lastHandshake"`
	RxBytes       int64     `json:"rxBytes"`
	TxBytes       int64     `json:"txBytes"`
}

func ParseDump(out string) []PeerStatus {
	var res []PeerStatus
	for i, line := range strings.Split(strings.TrimSpace(out), "\n") {
		f := strings.Split(line, "\t")
		if i == 0 || len(f) < 8 { // first line is the interface itself
			continue
		}
		hs, _ := strconv.ParseInt(f[4], 10, 64)
		rx, _ := strconv.ParseInt(f[5], 10, 64)
		tx, _ := strconv.ParseInt(f[6], 10, 64)
		ps := PeerStatus{PublicKey: f[0], Endpoint: f[2], RxBytes: rx, TxBytes: tx}
		if f[2] == "(none)" {
			ps.Endpoint = ""
		}
		if hs > 0 {
			ps.LastHandshake = time.Unix(hs, 0)
		}
		res = append(res, ps)
	}
	return res
}

// Manager applies configs to the host with wg-quick / wg. Dir is normally /etc/wireguard.
type Manager interface {
	Apply(cfg ServerConfig) error
	Status(iface string) ([]PeerStatus, error)
}

type HostManager struct{ Dir string }

func (m HostManager) Apply(cfg ServerConfig) error {
	text, err := cfg.Render()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(m.Dir, 0o700); err != nil {
		return err
	}
	// Phones reach other home devices and the internet (exit node) through this machine: forwarding must be on,
	// now and after reboots. (Docker usually turns it on too, but don't depend on that.)
	_ = os.WriteFile("/etc/sysctl.d/99-immcloudscale.conf", []byte("# ImmCloudScale home VPN\nnet.ipv4.ip_forward = 1\n"), 0o644)
	_ = os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1\n"), 0o644)
	path := filepath.Join(m.Dir, cfg.Interface+".conf")
	if err := os.WriteFile(path, []byte(text), 0o600); err != nil {
		return err
	}
	if exec.Command("ip", "link", "show", cfg.Interface).Run() == nil {
		// Running: swap peers in place without dropping connections.
		strip, err := exec.Command("wg-quick", "strip", cfg.Interface).Output()
		if err != nil {
			return fmt.Errorf("wg-quick strip: %w", err)
		}
		cmd := exec.Command("wg", "syncconf", cfg.Interface, "/dev/stdin")
		cmd.Stdin = bytes.NewReader(strip)
		if out, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("wg syncconf: %v: %s", err, out)
		}
		return nil
	}
	if out, err := exec.Command("systemctl", "enable", "--now", "wg-quick@"+cfg.Interface).CombinedOutput(); err != nil {
		return fmt.Errorf("starting wg-quick@%s: %v: %s", cfg.Interface, err, out)
	}
	return nil
}

func (m HostManager) Status(iface string) ([]PeerStatus, error) {
	out, err := exec.Command("wg", "show", iface, "dump").Output()
	if err != nil {
		return nil, err
	}
	return ParseDump(string(out)), nil
}
