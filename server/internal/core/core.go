// Package core ties the pieces together: it sets up the stack, keeps Immich, Nextcloud and
// WireGuard users in step, and turns a one-time pairing code into everything a phone needs.
package core

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/mail"
	"os"
	"regexp"
	"strings"
	"time"

	"github.com/FenOps/immcloudscale/server/internal/immich"
	"github.com/FenOps/immcloudscale/server/internal/nextcloud"
	"github.com/FenOps/immcloudscale/server/internal/stack"
	"github.com/FenOps/immcloudscale/server/internal/state"
	"github.com/FenOps/immcloudscale/server/internal/wg"
)

const Version = "0.1.0"

const ncAdmin = "ics-admin"

type Server struct {
	Store  *state.Store
	Docker stack.Runner
	WG     wg.Manager
	// Where the server itself reaches the services (normally 127.0.0.1 and the published ports).
	ImmichURL    func(state.Config) string
	NextcloudURL func(state.Config) string
	Now          func() time.Time
	Log          *log.Logger
	WaitTimeout  time.Duration
	// PortFree checks ports on first setup; nil means "assume free" (tests).
	PortFree func(port int, udp bool) bool
}

func New(st *state.Store) *Server {
	return &Server{
		Store:        st,
		Docker:       stack.Docker{},
		WG:           wg.HostManager{Dir: "/etc/wireguard"},
		ImmichURL:    func(c state.Config) string { return fmt.Sprintf("http://127.0.0.1:%d", c.ImmichPort) },
		NextcloudURL: func(c state.Config) string { return fmt.Sprintf("http://127.0.0.1:%d", c.NextcloudPort) },
		Now:          time.Now,
		Log:          log.New(os.Stderr, "", log.LstdFlags),
		WaitTimeout:  20 * time.Minute,
		PortFree:     PortFree,
	}
}

func (s *Server) immich(c state.Config) *immich.Client       { return immich.New(s.ImmichURL(c)) }
func (s *Server) nextcloud(c state.Config) *nextcloud.Client { return nextcloud.New(s.NextcloudURL(c)) }

// ---- setup ----

type SetupOptions struct {
	DataDir         string
	StackDir        string
	LANAddress      string
	LANSubnet       string
	EgressIface     string
	Endpoint        string
	WGPort          int
	WGSubnet        string
	MachineLearning bool
	FirstUser       string
	FirstEmail      string
	SkipVPN         bool
}

// Setup installs or repairs the whole stack. It is safe to run again: existing secrets are kept.
func (s *Server) Setup(ctx context.Context, o SetupOptions) error {
	err := s.Store.Update(func(st *state.State) error {
		c := &st.Config
		set := func(dst *string, v, def string) {
			if v != "" {
				*dst = v
			} else if *dst == "" {
				*dst = def
			}
		}
		set(&c.DataDir, o.DataDir, "/srv/immcloudscale")
		set(&c.StackDir, o.StackDir, "/opt/immcloudscale")
		set(&c.LANAddress, o.LANAddress, "")
		set(&c.LANSubnet, o.LANSubnet, "")
		set(&c.EgressIface, o.EgressIface, "")
		set(&c.Endpoint, o.Endpoint, c.LANAddress)
		set(&c.WGSubnet, o.WGSubnet, "10.8.0.0/24")
		set(&c.WGInterface, "", "ics0")
		if o.WGPort != 0 {
			c.WGPort = o.WGPort
		} else if c.WGPort == 0 {
			c.WGPort = s.freePort(51820, true, nil)
		}
		// First setup: use the usual ports, or the next free ones if another program already has them
		// (8080 especially is often taken on home servers). Phones learn the ports when they pair.
		taken := map[int]bool{}
		pick := func(cur *int, def int, udp bool) {
			if *cur == 0 {
				*cur = s.freePort(def, udp, taken)
			}
			taken[*cur] = true
		}
		pick(&c.ImmichPort, 2283, false)
		pick(&c.NextcloudPort, 8080, false)
		pick(&c.AdminPort, 8790, false)
		c.MachineLearning = o.MachineLearning
		c.VPNEnabled = !o.SkipVPN
		set(&c.DBPassword, "", state.Secret(32))
		set(&c.NCDBPassword, "", state.Secret(32))
		set(&c.NCAdminPassword, "", state.Secret(24))
		set(&c.ImmichAdminEmail, "", "ics-admin@immcloudscale.local")
		set(&c.ImmichAdminPass, "", state.Secret(24))
		set(&c.AdminPassword, "", state.Secret(16))
		if c.WGPrivateKey == "" {
			priv, pub, err := wg.NewKeyPair()
			if err != nil {
				return err
			}
			c.WGPrivateKey, c.WGPublicKey = priv, pub
		}
		if c.LANAddress == "" {
			return errors.New("could not detect the NAS's LAN address; pass --lan-ip")
		}
		return nil
	})
	if err != nil {
		return err
	}
	c := s.Store.Read().Config

	s.Log.Printf("Writing the service bundle to %s (data in %s)", c.StackDir, c.DataDir)
	srv, _ := wg.ServerAddress(c.WGSubnet)
	params := stack.Params{
		DataDir: c.DataDir, ImmichPort: c.ImmichPort, NextcloudPort: c.NextcloudPort,
		MachineLearning: c.MachineLearning, DBPassword: c.DBPassword, NCDBPassword: c.NCDBPassword,
		NCAdminPassword: c.NCAdminPassword,
		TrustedDomains:  uniq([]string{"localhost", "127.0.0.1", c.LANAddress, srv.String(), hostOnly(c.Endpoint)}),
	}
	if err := stack.Write(c.StackDir, params); err != nil {
		return err
	}
	s.Log.Printf("Downloading Immich, Nextcloud, PostgreSQL and Valkey (about 5 GB the first time; this can take a while)…")
	if err := s.Docker.Pull(c.StackDir); err != nil {
		return fmt.Errorf("downloading the apps failed (check the internet connection, then run setup again): %w", err)
	}
	s.Log.Printf("Starting the apps…")
	if err := s.Docker.Up(c.StackDir); err != nil {
		return err
	}
	if err := s.waitReady(ctx, c); err != nil {
		return err
	}

	// Immich: create the hidden service admin on first run.
	im := s.immich(c)
	if _, err := im.Login(ctx, c.ImmichAdminEmail, c.ImmichAdminPass); err != nil {
		s.Log.Printf("Creating the Immich service administrator")
		if err := im.AdminSignUp(ctx, c.ImmichAdminEmail, c.ImmichAdminPass, "ImmCloudScale"); err != nil {
			return fmt.Errorf("immich admin sign-up: %w", err)
		}
	}

	if !o.SkipVPN {
		if err := s.applyWG(); err != nil {
			return fmt.Errorf("starting the VPN: %w", err)
		}
	}

	if st := s.Store.Read(); o.FirstUser != "" && st.FindUser(o.FirstUser) == nil {
		if _, err := s.AddUser(ctx, o.FirstUser, o.FirstEmail, true); err != nil {
			return err
		}
	}
	return nil
}

// freePort returns def if nothing listens on it, else the next free port above it.
func (s *Server) freePort(def int, udp bool, skip map[int]bool) int {
	if s.PortFree == nil {
		return def
	}
	for p := def; p < def+100; p++ {
		if !skip[p] && s.PortFree(p, udp) {
			if p != def {
				s.Log.Printf("Port %d is already used by another program; using %d instead", def, p)
			}
			return p
		}
	}
	return def
}

// PortFree reports whether a TCP (or UDP) port can be bound on this machine.
func PortFree(port int, udp bool) bool {
	addr := fmt.Sprintf(":%d", port)
	if udp {
		c, err := net.ListenPacket("udp", addr)
		if err != nil {
			return false
		}
		c.Close()
		return true
	}
	l, err := net.Listen("tcp", addr)
	if err != nil {
		return false
	}
	l.Close()
	return true
}

func (s *Server) waitReady(ctx context.Context, c state.Config) error {
	deadline := s.Now().Add(s.WaitTimeout)
	im, nc := s.immich(c), s.nextcloud(c)
	imOK, ncOK := false, false
	for {
		if !imOK && im.Ping(ctx) {
			imOK = true
			s.Log.Printf("Immich is up")
		}
		if !ncOK && nc.Installed(ctx) {
			ncOK = true
			s.Log.Printf("Nextcloud is up")
		}
		if imOK && ncOK {
			return nil
		}
		if s.Now().After(deadline) {
			return fmt.Errorf("timed out waiting for services (immich ready: %v, nextcloud ready: %v); see `docker compose -f %s/docker-compose.yml logs`", imOK, ncOK, c.StackDir)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(3 * time.Second):
		}
	}
}

func (s *Server) applyWG() error {
	st := s.Store.Read()
	c := st.Config
	if !c.VPNEnabled {
		return nil
	}
	cfg := wg.ServerConfig{Interface: c.WGInterface, PrivateKey: c.WGPrivateKey, Subnet: c.WGSubnet, Port: c.WGPort, Egress: c.EgressIface}
	for _, d := range st.Devices {
		cfg.Peers = append(cfg.Peers, wg.Peer{Name: d.User + " / " + d.Name, PublicKey: d.PublicKey, Address: d.Address})
	}
	return s.WG.Apply(cfg)
}

// ---- users ----

var userRe = regexp.MustCompile(`^[a-z][a-z0-9._-]{1,31}$`)

func (s *Server) AddUser(ctx context.Context, name, email string, admin bool) (state.User, error) {
	name = strings.ToLower(strings.TrimSpace(name))
	if !userRe.MatchString(name) || name == ncAdmin {
		return state.User{}, fmt.Errorf("user names are 2-32 lowercase letters, digits, '.', '_' or '-', starting with a letter")
	}
	st := s.Store.Read()
	if st.Config.ImmichAdminPass == "" {
		return state.User{}, state.ErrNotSetUp
	}
	if st.FindUser(name) != nil {
		return state.User{}, fmt.Errorf("user %q already exists", name)
	}
	if email == "" {
		email = name + "@immcloudscale.local"
	}
	if _, err := mail.ParseAddress(email); err != nil {
		return state.User{}, fmt.Errorf("invalid email %q", email)
	}
	c := st.Config
	u := state.User{Name: name, Email: email, Password: state.Secret(16), IsAdmin: admin, Created: s.Now()}

	im := s.immich(c)
	tok, err := im.Login(ctx, c.ImmichAdminEmail, c.ImmichAdminPass)
	if err != nil {
		return u, fmt.Errorf("immich admin login: %w", err)
	}
	iu, err := im.CreateUser(ctx, tok, email, u.Password, name)
	if err != nil {
		return u, fmt.Errorf("creating Immich user: %w", err)
	}
	u.ImmichID = iu.ID
	if err := s.nextcloud(c).CreateUser(ctx, ncAdmin, c.NCAdminPassword, name, u.Password, name, email); err != nil {
		_ = im.DeleteUser(ctx, tok, iu.ID) // keep both services in step
		return u, fmt.Errorf("creating Nextcloud user: %w", err)
	}
	err = s.Store.Update(func(st *state.State) error { st.Users = append(st.Users, u); return nil })
	return u, err
}

func (s *Server) RemoveUser(ctx context.Context, name string) error {
	st := s.Store.Read()
	u := st.FindUser(name)
	if u == nil {
		return fmt.Errorf("no user %q", name)
	}
	for _, d := range st.Devices {
		if d.User == name {
			if err := s.Revoke(ctx, d.ID); err != nil {
				s.Log.Printf("revoking %s: %v", d.ID, err)
			}
		}
	}
	c := st.Config
	im := s.immich(c)
	if tok, err := im.Login(ctx, c.ImmichAdminEmail, c.ImmichAdminPass); err == nil && u.ImmichID != "" {
		if err := im.DeleteUser(ctx, tok, u.ImmichID); err != nil {
			s.Log.Printf("deleting Immich user: %v", err)
		}
	}
	if err := s.nextcloud(c).DeleteUser(ctx, ncAdmin, c.NCAdminPassword, name); err != nil {
		s.Log.Printf("deleting Nextcloud user: %v", err)
	}
	return s.Store.Update(func(st *state.State) error {
		out := st.Users[:0]
		for _, x := range st.Users {
			if x.Name != name {
				out = append(out, x)
			}
		}
		st.Users = out
		return nil
	})
}

// ---- pairing ----

const PairingTTL = 15 * time.Minute

func (s *Server) StartPairing(user string) (state.Pairing, error) {
	var p state.Pairing
	err := s.Store.Update(func(st *state.State) error {
		if st.FindUser(user) == nil {
			return fmt.Errorf("no user %q", user)
		}
		now := s.Now()
		keep := st.Pairings[:0]
		for _, x := range st.Pairings {
			if x.Expires.After(now) {
				keep = append(keep, x)
			}
		}
		p = state.Pairing{Code: state.PairCode(), User: user, Expires: now.Add(PairingTTL)}
		st.Pairings = append(keep, p)
		return nil
	})
	return p, err
}

type PairRequest struct {
	Code        string `json:"code"`
	DeviceName  string `json:"deviceName"`
	WGPublicKey string `json:"wgPublicKey"`
}

type PairResult struct {
	DeviceID string `json:"deviceId"`
	Server   struct {
		Name    string `json:"name"`
		Version string `json:"version"`
	} `json:"server"`
	User struct {
		Name  string `json:"name"`
		Email string `json:"email"`
	} `json:"user"`
	Immich struct {
		URL    string `json:"url"`
		APIKey string `json:"apiKey"`
	} `json:"immich"`
	Nextcloud struct {
		URL         string `json:"url"`
		User        string `json:"user"`
		AppPassword string `json:"appPassword"`
	} `json:"nextcloud"`
	WireGuard *struct {
		Address         string `json:"address"`
		ServerPublicKey string `json:"serverPublicKey"`
		Endpoint        string `json:"endpoint"`
		AllowedIPs      string `json:"allowedIps"`
		DNS             string `json:"dns"`
	} `json:"wireguard,omitempty"`
}

var ErrBadCode = errors.New("that pairing code is wrong or has expired")

func normCode(c string) string {
	c = strings.ToUpper(strings.NewReplacer("-", "", " ", "").Replace(c))
	if len(c) == 8 {
		return c[:4] + "-" + c[4:]
	}
	return c
}

// Pair consumes a code and provisions the phone: an Immich API key, a Nextcloud app password
// and a WireGuard peer for the public key the phone generated (its private key never leaves it).
func (s *Server) Pair(ctx context.Context, req PairRequest) (PairResult, error) {
	var res PairResult
	code := normCode(req.Code)
	var pairing state.Pairing
	err := s.Store.Update(func(st *state.State) error {
		now := s.Now()
		for i, p := range st.Pairings {
			if p.Code == code && p.Expires.After(now) {
				pairing = p
				st.Pairings = append(st.Pairings[:i], st.Pairings[i+1:]...) // single use
				return nil
			}
		}
		return ErrBadCode
	})
	if err != nil {
		return res, err
	}
	if req.WGPublicKey != "" && !wg.ValidKey(req.WGPublicKey) {
		return res, errors.New("invalid WireGuard public key")
	}
	name := strings.TrimSpace(req.DeviceName)
	if name == "" {
		name = "Android phone"
	}
	if len(name) > 60 {
		name = name[:60]
	}

	st := s.Store.Read()
	c := st.Config
	u := st.FindUser(pairing.User)
	if u == nil {
		return res, fmt.Errorf("user %q was removed", pairing.User)
	}
	dev := state.Device{ID: "dev_" + strings.ToLower(state.Secret(10)), User: u.Name, Name: name, PublicKey: strings.TrimSpace(req.WGPublicKey), Created: s.Now()}

	im := s.immich(c)
	tok, err := im.Login(ctx, u.Email, u.Password)
	if err != nil {
		return res, fmt.Errorf("immich login for %s: %w", u.Name, err)
	}
	key, err := im.CreateAPIKey(ctx, tok, "ImmCloudScale – "+name)
	if err != nil {
		return res, fmt.Errorf("creating Immich API key: %w", err)
	}
	dev.ImmichKeyID = key.ID
	appPass, err := s.nextcloud(c).AppPassword(ctx, u.Name, u.Password)
	if err != nil {
		_ = im.DeleteAPIKey(ctx, tok, key.ID)
		return res, fmt.Errorf("creating Nextcloud app password: %w", err)
	}
	dev.NCAppPassword = appPass

	err = s.Store.Update(func(st *state.State) error {
		if !st.Config.VPNEnabled {
			dev.PublicKey = ""
		}
		if dev.PublicKey != "" {
			used := []string{}
			for _, d := range st.Devices {
				used = append(used, d.Address)
				if d.PublicKey == dev.PublicKey {
					return errors.New("this phone's VPN key is already paired; revoke the old device first")
				}
			}
			addr, err := wg.NextAddress(st.Config.WGSubnet, used)
			if err != nil {
				return err
			}
			dev.Address = addr
		}
		st.Devices = append(st.Devices, dev)
		return nil
	})
	if err != nil {
		_ = im.DeleteAPIKey(ctx, tok, key.ID)
		_ = s.nextcloud(c).RevokeAppPassword(ctx, u.Name, appPass)
		return res, err
	}
	if dev.PublicKey != "" {
		if err := s.applyWG(); err != nil {
			s.Log.Printf("updating WireGuard peers: %v", err)
		}
	}

	host, _ := os.Hostname()
	res.DeviceID = dev.ID
	res.Server.Name, res.Server.Version = host, Version
	res.User.Name, res.User.Email = u.Name, u.Email
	res.Immich.URL = fmt.Sprintf("http://%s:%d", c.LANAddress, c.ImmichPort)
	res.Immich.APIKey = key.Secret
	res.Nextcloud.URL = fmt.Sprintf("http://%s:%d", c.LANAddress, c.NextcloudPort)
	res.Nextcloud.User, res.Nextcloud.AppPassword = u.Name, appPass
	if dev.Address != "" {
		res.WireGuard = &struct {
			Address         string `json:"address"`
			ServerPublicKey string `json:"serverPublicKey"`
			Endpoint        string `json:"endpoint"`
			AllowedIPs      string `json:"allowedIps"`
			DNS             string `json:"dns"`
		}{
			Address: dev.Address + "/32", ServerPublicKey: c.WGPublicKey,
			Endpoint:   fmt.Sprintf("%s:%d", hostOnly(c.Endpoint), c.WGPort),
			AllowedIPs: wg.ClientAllowedIPs(c.WGSubnet, c.LANSubnet),
		}
	}
	s.Log.Printf("Paired %q for %s (VPN %s)", name, u.Name, dev.Address)
	return res, nil
}

// Revoke removes a phone: its VPN peer, Immich API key and Nextcloud app password.
func (s *Server) Revoke(ctx context.Context, deviceID string) error {
	st := s.Store.Read()
	var dev *state.Device
	for i := range st.Devices {
		if st.Devices[i].ID == deviceID {
			dev = &st.Devices[i]
		}
	}
	if dev == nil {
		return fmt.Errorf("no device %q", deviceID)
	}
	c := st.Config
	if u := st.FindUser(dev.User); u != nil {
		im := s.immich(c)
		if tok, err := im.Login(ctx, u.Email, u.Password); err == nil && dev.ImmichKeyID != "" {
			if err := im.DeleteAPIKey(ctx, tok, dev.ImmichKeyID); err != nil {
				s.Log.Printf("deleting Immich API key: %v", err)
			}
		}
		if dev.NCAppPassword != "" {
			if err := s.nextcloud(c).RevokeAppPassword(ctx, u.Name, dev.NCAppPassword); err != nil {
				s.Log.Printf("revoking Nextcloud app password: %v", err)
			}
		}
	}
	if err := s.Store.Update(func(st *state.State) error {
		out := st.Devices[:0]
		for _, d := range st.Devices {
			if d.ID != deviceID {
				out = append(out, d)
			}
		}
		st.Devices = out
		return nil
	}); err != nil {
		return err
	}
	if dev.PublicKey != "" {
		return s.applyWG()
	}
	return nil
}

// ---- status & maintenance ----

type DeviceStatus struct {
	state.Device
	Online        bool      `json:"online"`
	LastHandshake time.Time `json:"lastHandshake"`
	RxBytes       int64     `json:"rxBytes"`
	TxBytes       int64     `json:"txBytes"`
}

type Status struct {
	Version   string          `json:"version"`
	Hostname  string          `json:"hostname"`
	SetUp     bool            `json:"setUp"`
	Config    PublicConfig    `json:"config"`
	Services  []stack.Service `json:"services"`
	Immich    bool            `json:"immichReady"`
	Nextcloud bool            `json:"nextcloudReady"`
	VPNUp     bool            `json:"vpnUp"`
	Storage   *immich.Storage `json:"storage,omitempty"`
	Users     []PublicUser    `json:"users"`
	Devices   []DeviceStatus  `json:"devices"`
}

type PublicConfig struct {
	LANAddress      string `json:"lanAddress"`
	LANSubnet       string `json:"lanSubnet"`
	Endpoint        string `json:"endpoint"`
	WGPort          int    `json:"wgPort"`
	WGSubnet        string `json:"wgSubnet"`
	ImmichPort      int    `json:"immichPort"`
	NextcloudPort   int    `json:"nextcloudPort"`
	DataDir         string `json:"dataDir"`
	MachineLearning bool   `json:"machineLearning"`
	VPNEnabled      bool   `json:"vpnEnabled"`
}

type PublicUser struct {
	Name     string    `json:"name"`
	Email    string    `json:"email"`
	Password string    `json:"password"`
	IsAdmin  bool      `json:"isAdmin"`
	Created  time.Time `json:"created"`
	Devices  int       `json:"devices"`
}

func (s *Server) Status(ctx context.Context) Status {
	st := s.Store.Read()
	c := st.Config
	host, _ := os.Hostname()
	out := Status{Version: Version, Hostname: host, SetUp: c.ImmichAdminPass != ""}
	out.Config = PublicConfig{c.LANAddress, c.LANSubnet, c.Endpoint, c.WGPort, c.WGSubnet, c.ImmichPort, c.NextcloudPort, c.DataDir, c.MachineLearning, c.VPNEnabled}
	if !out.SetUp {
		return out
	}
	out.Services, _ = s.Docker.Services(c.StackDir)
	im := s.immich(c)
	out.Immich = im.Ping(ctx)
	out.Nextcloud = s.nextcloud(c).Installed(ctx)
	if tok, err := im.Login(ctx, c.ImmichAdminEmail, c.ImmichAdminPass); err == nil {
		if sto, err := im.Storage(ctx, tok); err == nil {
			out.Storage = &sto
		}
	}
	peers, err := s.WG.Status(c.WGInterface)
	out.VPNUp = err == nil
	byKey := map[string]wg.PeerStatus{}
	for _, p := range peers {
		byKey[p.PublicKey] = p
	}
	count := map[string]int{}
	for _, d := range st.Devices {
		count[d.User]++
		ds := DeviceStatus{Device: d}
		ds.NCAppPassword = ""
		if p, ok := byKey[d.PublicKey]; ok {
			ds.LastHandshake, ds.RxBytes, ds.TxBytes = p.LastHandshake, p.RxBytes, p.TxBytes
			ds.Online = !p.LastHandshake.IsZero() && s.Now().Sub(p.LastHandshake) < 3*time.Minute
		}
		out.Devices = append(out.Devices, ds)
	}
	for _, u := range st.Users {
		out.Users = append(out.Users, PublicUser{u.Name, u.Email, u.Password, u.IsAdmin, u.Created, count[u.Name]})
	}
	return out
}

// Update pulls newer images for the pinned versions and restarts what changed.
func (s *Server) Update(ctx context.Context) error {
	c := s.Store.Read().Config
	if err := s.Docker.Pull(c.StackDir); err != nil {
		return err
	}
	return s.Docker.Up(c.StackDir)
}

func uniq(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, v := range in {
		if v != "" && !seen[v] {
			seen[v] = true
			out = append(out, v)
		}
	}
	return out
}

// hostOnly strips a ":port" suffix (IPv6 literals in brackets are kept whole).
func hostOnly(endpoint string) string {
	if strings.HasPrefix(endpoint, "[") {
		if i := strings.Index(endpoint, "]"); i > 0 {
			return endpoint[:i+1]
		}
	}
	if strings.Count(endpoint, ":") == 1 {
		return endpoint[:strings.Index(endpoint, ":")]
	}
	return endpoint
}
