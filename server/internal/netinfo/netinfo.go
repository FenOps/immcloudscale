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
	"syscall"
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

// MemTotalGB is the machine's RAM in GB (0 if unknown).
func MemTotalGB() float64 {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0
	}
	return parseMemTotal(string(b))
}

func parseMemTotal(meminfo string) float64 {
	for _, line := range strings.Split(meminfo, "\n") {
		if f := strings.Fields(line); len(f) >= 2 && f[0] == "MemTotal:" {
			kb, _ := strconv.ParseFloat(f[1], 64)
			return kb / 1024 / 1024
		}
	}
	return 0
}

// FreeGB is the free space on the file system holding path, or its nearest existing parent (0 if unknown).
func FreeGB(path string) float64 {
	for p := path; p != ""; p = parentDir(p) {
		var st syscall.Statfs_t
		if syscall.Statfs(p, &st) == nil {
			return float64(st.Bavail) * float64(st.Bsize) / 1e9
		}
		if p == "/" {
			break
		}
	}
	return 0
}

func parentDir(p string) string {
	i := strings.LastIndex(strings.TrimRight(p, "/"), "/")
	if i <= 0 {
		return "/"
	}
	return p[:i]
}
