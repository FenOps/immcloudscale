// Package state keeps the server's configuration, users and paired devices in one JSON file
// (root-only, 0600). It holds secrets: service passwords, WireGuard keys, app passwords.
package state

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"math/big"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

type User struct {
	Name     string    `json:"name"`
	Email    string    `json:"email"`
	Password string    `json:"password"` // web sign-in password for Immich and Nextcloud
	ImmichID string    `json:"immichId"`
	IsAdmin  bool      `json:"isAdmin"`
	Created  time.Time `json:"created"`
}

type Device struct {
	ID            string    `json:"id"`
	User          string    `json:"user"`
	Name          string    `json:"name"`
	PublicKey     string    `json:"publicKey"`
	Address       string    `json:"address"` // tunnel IP, e.g. 10.8.0.2
	ImmichKeyID   string    `json:"immichKeyId"`
	NCAppPassword string    `json:"ncAppPassword"`
	Created       time.Time `json:"created"`
	// TokenHash is the SHA-256 of the secret the phone got at pairing; it signs the phone's member-management requests.
	TokenHash string `json:"tokenHash,omitempty"`
	// ImmichSessionID is the session handed to the phone for the locked folder; it's signed out on revoke.
	ImmichSessionID string `json:"immichSessionId,omitempty"`
}

// JoinRequest is someone asking, from the app, for an account; an admin approves or declines it.
type JoinRequest struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Email     string    `json:"email"`
	Device    string    `json:"device"`
	Created   time.Time `json:"created"`
	Status    string    `json:"status"`    // pending, approved, declined
	TokenHash string    `json:"tokenHash"` // SHA-256 of the requester's secret for checking on the request
	Code      string    `json:"code,omitempty"`
	DecidedBy string    `json:"decidedBy,omitempty"`
}

type Pairing struct {
	Code    string    `json:"code"`
	User    string    `json:"user"`
	Expires time.Time `json:"expires"`
}

type Config struct {
	DataDir         string `json:"dataDir"`    // photos, files, database live here
	StackDir        string `json:"stackDir"`   // generated docker-compose.yml and .env
	LANAddress      string `json:"lanAddress"` // the NAS's LAN IP, used in service URLs
	LANManual       bool   `json:"lanManual"`  // set by hand (--lan-ip): not replaced by the detected address
	LANSubnet       string `json:"lanSubnet"`  // e.g. 192.168.1.0/24, routed through the VPN
	Endpoint        string `json:"endpoint"`   // public host or IP the phones dial
	WGPort          int    `json:"wgPort"`
	WGInterface     string `json:"wgInterface"`
	WGSubnet        string `json:"wgSubnet"` // e.g. 10.8.0.0/24; the server is .1
	WGPrivateKey    string `json:"wgPrivateKey"`
	WGPublicKey     string `json:"wgPublicKey"`
	EgressIface     string `json:"egressIface"`
	ImmichPort      int    `json:"immichPort"`
	NextcloudPort   int    `json:"nextcloudPort"`
	AdminPort       int    `json:"adminPort"`
	MachineLearning bool   `json:"machineLearning"`
	VPNEnabled      bool   `json:"vpnEnabled"`

	DBPassword       string `json:"dbPassword"`
	NCDBPassword     string `json:"ncDbPassword"`
	NCAdminPassword  string `json:"ncAdminPassword"`
	ImmichAdminEmail string `json:"immichAdminEmail"`
	ImmichAdminPass  string `json:"immichAdminPassword"`
	AdminPassword    string `json:"adminPassword"` // for the ics-server web console
	ServerID         string `json:"serverId"`      // random, stable; lets phones recognise this server

	// Handed to phones at pairing so the app joins the tailnet without a sign-in (optional).
	TailscaleAuthKey    string `json:"tailscaleAuthKey,omitempty"`
	TailscaleControlURL string `json:"tailscaleControlUrl,omitempty"`
}

type State struct {
	Config   Config        `json:"config"`
	Users    []User        `json:"users"`
	Devices  []Device      `json:"devices"`
	Pairings []Pairing     `json:"pairings"`
	Requests []JoinRequest `json:"joinRequests,omitempty"`
}

// Store serialises access to the state file.
type Store struct {
	path  string
	mu    sync.Mutex
	s     State
	mtime time.Time
	size  int64
}

var ErrNotSetUp = errors.New("ImmCloudScale server is not set up yet: run `ics-server setup`")

func Open(path string) (*Store, error) {
	st := &Store{path: path}
	if err := st.reload(); err != nil {
		return nil, err
	}
	return st, nil
}

// reload re-reads the file when another process (the CLI or the daemon) changed it.
func (st *Store) reload() error {
	fi, err := os.Stat(st.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if fi.ModTime().Equal(st.mtime) && fi.Size() == st.size {
		return nil
	}
	b, err := os.ReadFile(st.path)
	if err != nil {
		return err
	}
	var fresh State
	if err := json.Unmarshal(b, &fresh); err != nil {
		return err
	}
	st.s, st.mtime, st.size = fresh, fi.ModTime(), fi.Size()
	return nil
}

func (st *Store) Path() string { return st.path }

// Read gives a copy of the state.
func (st *Store) Read() State {
	st.mu.Lock()
	defer st.mu.Unlock()
	_ = st.reload()
	b, _ := json.Marshal(st.s)
	var c State
	_ = json.Unmarshal(b, &c)
	return c
}

// Update changes the state and writes it atomically.
// Update changes the state and writes it atomically. A file lock keeps the CLI and the daemon
// from overwriting each other's changes.
func (st *Store) Update(fn func(*State) error) error {
	st.mu.Lock()
	defer st.mu.Unlock()
	if err := os.MkdirAll(filepath.Dir(st.path), 0o700); err != nil {
		return err
	}
	lock, err := os.OpenFile(st.path+".lock", os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	if err := st.reload(); err != nil {
		return err
	}
	var work State // deep copy, so a failed fn leaves nothing half-changed
	if cp, err := json.Marshal(st.s); err != nil {
		return err
	} else if err := json.Unmarshal(cp, &work); err != nil {
		return err
	}
	if err := fn(&work); err != nil {
		return err
	}
	b, err := json.MarshalIndent(work, "", "  ")
	if err != nil {
		return err
	}
	tmp := st.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, st.path); err != nil {
		return err
	}
	st.s = work
	if fi, err := os.Stat(st.path); err == nil {
		st.mtime, st.size = fi.ModTime(), fi.Size()
	}
	return nil
}

func (s *State) FindUser(name string) *User {
	for i := range s.Users {
		if s.Users[i].Name == name {
			return &s.Users[i]
		}
	}
	return nil
}

const alnum = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
const codeChars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no 0/O, 1/I/L

func random(n int, alphabet string) string {
	out := make([]byte, n)
	for i := range out {
		v, err := rand.Int(rand.Reader, big.NewInt(int64(len(alphabet))))
		if err != nil {
			panic(err)
		}
		out[i] = alphabet[v.Int64()]
	}
	return string(out)
}

// Secret returns a random password-safe string.
func Secret(n int) string { return random(n, alnum) }

// PairCode returns an easy-to-type code like "K7QX-M2PA".
func PairCode() string { c := random(8, codeChars); return c[:4] + "-" + c[4:] }
