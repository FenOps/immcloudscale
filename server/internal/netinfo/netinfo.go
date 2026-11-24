// Package netinfo finds the NAS's LAN address, subnet and outgoing interface, and its public IP.
package netinfo

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// DefaultRouteIface parses /proc/net/route and returns the interface of the default route.
func DefaultRouteIface(routeTable string) string {
	sc := bufio.NewScanner(strings.NewReader(routeTable))
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) >= 8 && f[1] == "00000000" && f[7] == "00000000" {
			return f[0]
		}
	}
	return ""
}

type LAN struct {
	Iface   string
	Address string
	Subnet  string
}

// DetectLAN returns the IPv4 address and subnet of the interface that carries the default route.
func DetectLAN() (LAN, error) {
	b, err := os.ReadFile("/proc/net/route")
	if err != nil {
		return LAN{}, err
	}
	name := DefaultRouteIface(string(b))
	ifc, err := net.InterfaceByName(name)
	if err != nil {
		return LAN{}, err
	}
	addrs, err := ifc.Addrs()
	if err != nil {
		return LAN{}, err
	}
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil {
			ones, _ := ipn.Mask.Size()
			return LAN{Iface: name, Address: ipn.IP.String(), Subnet: ipn.IP.Mask(ipn.Mask).String() + "/" + strconv.Itoa(ones)}, nil
		}
	}
	return LAN{Iface: name}, nil
}

// PublicIP asks a public "what is my IP" service; used once at setup as the default VPN endpoint.
func PublicIP(ctx context.Context) string {
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://api.ipify.org", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 64))
	ip := net.ParseIP(strings.TrimSpace(string(b)))
	if ip == nil {
		return ""
	}
	return ip.String()
}
