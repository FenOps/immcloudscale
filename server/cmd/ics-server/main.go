// ics-server installs and runs the ImmCloudScale home server: Immich (photos), Nextcloud (files)
// and a WireGuard VPN, managed together, with one-step pairing for the Android app.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/FenOps/immcloudscale/server/internal/core"
	"github.com/FenOps/immcloudscale/server/internal/netinfo"
	"github.com/FenOps/immcloudscale/server/internal/state"
	"github.com/FenOps/immcloudscale/server/internal/web"
)

const usage = `ImmCloudScale server ` + core.Version + `

Usage: ics-server <command> [options]

  setup                 install or repair everything (Immich, Nextcloud, VPN) and create the owner
  serve                 run the admin console and pairing service (systemd runs this)
  status                show services, people and phones
  pair <person>         show a pairing code and QR for a new phone  [--json]
  user add <name>       add a person (Immich + Nextcloud accounts)   [--email]
  user list             list people and their web sign-in passwords
  user remove <name>    remove a person and their phones
  devices               list paired phones
  revoke <device-id>    disconnect a phone (VPN, photo and file access)
  config                change settings                             [--endpoint --lan-ip]
  admin-password        print the admin console password
  update                download and apply new Immich / Nextcloud images
  version

Global: --state FILE (default /var/lib/immcloudscale/state.json, or $ICS_STATE)
`

func main() {
	args := os.Args[1:]
	statePath := envOr("ICS_STATE", "/var/lib/immcloudscale/state.json")
	if len(args) >= 2 && args[0] == "--state" {
		statePath, args = args[1], args[2:]
	}
	if len(args) == 0 || args[0] == "help" || args[0] == "-h" || args[0] == "--help" {
		fmt.Print(usage)
		return
	}
	st, err := state.Open(statePath)
	check(err)
	s := core.New(st)
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	switch args[0] {
	case "version":
		fmt.Println(core.Version)
	case "setup":
		setup(ctx, s, args[1:])
	case "serve":
		serve(ctx, s, args[1:])
	case "status":
		status(ctx, s)
	case "pair":
		need(args, 2, "ics-server pair <person> [--json]")
		if len(args) > 2 && args[2] == "--json" {
			// For scripts (e.g. tools/phone-setup.sh): {"server":…,"code":…,"link":…,"expires":…}
			p, err := s.StartPairing(args[1])
			check(err)
			c := st.Read().Config
			server := fmt.Sprintf("http://%s:%d", c.LANAddress, c.AdminPort)
			enc := json.NewEncoder(os.Stdout)
			enc.SetEscapeHTML(false) // keep "&" in the link readable for shell scripts
			check(enc.Encode(map[string]any{"server": server, "code": p.Code, "link": web.PairLink(server, p.Code), "expires": p.Expires}))
			return
		}
		printPairing(s, args[1])
	case "user":
		need(args, 2, "ics-server user add|list|remove")
		switch args[1] {
		case "add":
			fs := flag.NewFlagSet("user add", flag.ExitOnError)
			email := fs.String("email", "", "email address (optional)")
			need(args, 3, "ics-server user add <name> [--email you@example.com]")
			check(fs.Parse(args[3:]))
			u, err := s.AddUser(ctx, args[2], *email, false)
			check(err)
			fmt.Printf("Added %s.\nWeb sign-in for Immich and Nextcloud: %s / %s\nPair their phone with: ics-server pair %s\n", u.Name, u.Email, u.Password, u.Name)
		case "list":
			w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
			fmt.Fprintln(w, "NAME\tWEB SIGN-IN\tPASSWORD\tPHONES")
			for _, u := range s.Status(ctx).Users {
				fmt.Fprintf(w, "%s\t%s\t%s\t%d\n", u.Name, u.Email, u.Password, u.Devices)
			}
			w.Flush()
		case "remove":
			need(args, 3, "ics-server user remove <name>")
			check(s.RemoveUser(ctx, args[2]))
			fmt.Println("Removed", args[2])
		default:
			fail("unknown user command %q", args[1])
		}
	case "devices":
		w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
		fmt.Fprintln(w, "ID\tPERSON\tPHONE\tVPN ADDRESS\tONLINE\tPAIRED")
		for _, d := range s.Status(ctx).Devices {
			fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%v\t%s\n", d.ID, d.User, d.Name, d.Address, d.Online, d.Created.Format("2006-01-02"))
		}
		w.Flush()
	case "revoke":
		need(args, 2, "ics-server revoke <device-id>")
		check(s.Revoke(ctx, args[1]))
		fmt.Println("Revoked", args[1])
	case "config":
		fs := flag.NewFlagSet("config", flag.ExitOnError)
		endpoint := fs.String("endpoint", "", "public host name or IP phones use to reach the VPN")
		lan := fs.String("lan-ip", "", "the NAS's LAN address used in service URLs")
		check(fs.Parse(args[1:]))
		check(st.Update(func(x *state.State) error {
			if *endpoint != "" {
				x.Config.Endpoint = *endpoint
			}
			if *lan != "" {
				x.Config.LANAddress = *lan
			}
			return nil
		}))
		fmt.Println("Saved. Phones pick up a new endpoint when they are paired again.")
	case "admin-password":
		fmt.Println(st.Read().Config.AdminPassword)
	case "update":
		fmt.Println("Pulling new images and restarting services…")
		check(s.Update(ctx))
		fmt.Println("Done.")
	default:
		fail("unknown command %q\n\n%s", args[0], usage)
	}
}

func setup(ctx context.Context, s *core.Server, args []string) {
	fs := flag.NewFlagSet("setup", flag.ExitOnError)
	o := core.SetupOptions{}
	fs.StringVar(&o.DataDir, "data-dir", "", "where photos, files and the database are stored (default /srv/immcloudscale)")
	fs.StringVar(&o.LANAddress, "lan-ip", "", "the NAS's LAN address (detected)")
	fs.StringVar(&o.Endpoint, "endpoint", "", "public host name or IP for the VPN (default: detected public IP)")
	fs.IntVar(&o.WGPort, "vpn-port", 0, "WireGuard UDP port (default 51820)")
	fs.StringVar(&o.FirstUser, "user", "", "the owner's user name (asked if missing)")
	fs.StringVar(&o.FirstEmail, "email", "", "the owner's email (optional)")
	noML := fs.Bool("no-ml", false, "skip Immich machine learning (smart search, faces) on low-power NAS")
	fs.BoolVar(&o.SkipVPN, "no-vpn", false, "don't set up WireGuard")
	noPublic := fs.Bool("no-public-ip-lookup", false, "don't ask api.ipify.org for the public IP")
	check(fs.Parse(args))
	o.MachineLearning = !*noML

	if os.Geteuid() != 0 {
		fail("setup needs root: sudo ics-server setup")
	}
	for _, bin := range []string{"docker"} {
		if _, err := exec.LookPath(bin); err != nil {
			fail("%s is not installed (the installer adds it: see README)", bin)
		}
	}
	if !o.SkipVPN {
		if _, err := exec.LookPath("wg-quick"); err != nil {
			fail("wireguard-tools is not installed: sudo apt install wireguard-tools")
		}
	}
	if lan, err := netinfo.DetectLAN(); err == nil {
		if o.LANAddress == "" {
			o.LANAddress = lan.Address
		}
		o.LANSubnet, o.EgressIface = lan.Subnet, lan.Iface
	}
	if o.Endpoint == "" && s.Store.Read().Config.Endpoint == "" && !*noPublic && !o.SkipVPN {
		o.Endpoint = netinfo.PublicIP(ctx)
	}
	if o.FirstUser == "" && len(s.Store.Read().Users) == 0 {
		fmt.Print("Your user name (lowercase, e.g. alex): ")
		_, _ = fmt.Scanln(&o.FirstUser)
	}

	check(s.Setup(ctx, o))
	openFirewall(s)
	if _, err := os.Stat("/etc/systemd/system/ics-server.service"); err == nil {
		_ = exec.Command("systemctl", "enable", "--now", "ics-server").Run()
		_ = exec.Command("systemctl", "restart", "ics-server").Run()
	}

	c := s.Store.Read().Config
	fmt.Printf("\n✅ ImmCloudScale is running.\n\n")
	fmt.Printf("  Admin console:  http://%s:%d   (user: admin, password: %s)\n", c.LANAddress, c.AdminPort, c.AdminPassword)
	fmt.Printf("  Photos (web):   http://%s:%d\n", c.LANAddress, c.ImmichPort)
	fmt.Printf("  Files (web):    http://%s:%d\n", c.LANAddress, c.NextcloudPort)
	if !o.SkipVPN {
		fmt.Printf("  VPN:            forward UDP %d on your router to %s (endpoint: %s)\n", c.WGPort, c.LANAddress, c.Endpoint)
	}
	st := s.Store.Read()
	for _, u := range st.Users {
		if u.IsAdmin {
			fmt.Printf("\n  Your web sign-in: %s / %s\n", u.Email, u.Password)
			printPairing(s, u.Name)
			break
		}
	}
}

func printPairing(s *core.Server, user string) {
	p, err := s.StartPairing(user)
	check(err)
	c := s.Store.Read().Config
	server := fmt.Sprintf("http://%s:%d", c.LANAddress, c.AdminPort)
	link := web.PairLink(server, p.Code)
	q, err := qrcode.New(link, qrcode.Low)
	check(err)
	fmt.Printf("\nPair %s's phone: scan this with the camera, or open ImmCloudScale → Pair with server.\n", user)
	fmt.Print(q.ToSmallString(false))
	fmt.Printf("  Server: %s\n  Code:   %s   (valid 15 minutes, one phone)\n\n", server, p.Code)
	if !serving(c.AdminPort) {
		fmt.Println("  Note: the pairing service isn't running. Start it with: sudo systemctl start ics-server")
	}
}

func serving(port int) bool {
	cl := http.Client{Timeout: 2 * time.Second}
	r, err := cl.Get(fmt.Sprintf("http://127.0.0.1:%d/api/v1/info", port))
	if err != nil {
		return false
	}
	r.Body.Close()
	return r.StatusCode == 200
}

func serve(ctx context.Context, s *core.Server, args []string) {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	addr := fs.String("listen", "", "listen address (default :<adminPort>)")
	check(fs.Parse(args))
	if *addr == "" {
		port := s.Store.Read().Config.AdminPort
		if port == 0 {
			port = 8790
		}
		*addr = fmt.Sprintf(":%d", port)
	}
	srv := &http.Server{Addr: *addr, Handler: web.New(s).Routes(), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		<-ctx.Done()
		sctx, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = srv.Shutdown(sctx)
	}()
	s.Log.Printf("ImmCloudScale server %s listening on %s", core.Version, *addr)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		check(err)
	}
}

func status(ctx context.Context, s *core.Server) {
	st := s.Status(ctx)
	if !st.SetUp {
		fmt.Println("Not set up yet: sudo ics-server setup")
		return
	}
	mark := func(ok bool) string {
		if ok {
			return "running"
		}
		return "DOWN"
	}
	fmt.Printf("ImmCloudScale %s on %s\n\n", st.Version, st.Hostname)
	fmt.Printf("  Photos (Immich)     %s   http://%s:%d\n", mark(st.Immich), st.Config.LANAddress, st.Config.ImmichPort)
	fmt.Printf("  Files (Nextcloud)   %s   http://%s:%d\n", mark(st.Nextcloud), st.Config.LANAddress, st.Config.NextcloudPort)
	fmt.Printf("  VPN (WireGuard)     %s   %s:%d\n", mark(st.VPNUp), st.Config.Endpoint, st.Config.WGPort)
	if st.Storage != nil {
		fmt.Printf("  Disk                %s of %s used\n", st.Storage.DiskUse, st.Storage.DiskSize)
	}
	fmt.Println()
	for _, sv := range st.Services {
		fmt.Printf("  %-26s %s\n", sv.Name, strings.TrimSpace(sv.Status))
	}
	fmt.Printf("\n  %d people, %d phones\n", len(st.Users), len(st.Devices))
}

// openFirewall adds ufw rules when ufw is active (Ubuntu's default firewall).
func openFirewall(s *core.Server) {
	out, err := exec.Command("ufw", "status").Output()
	if err != nil || !strings.Contains(string(out), "Status: active") {
		return
	}
	c := s.Store.Read().Config
	rules := [][]string{{"allow", fmt.Sprintf("%d/udp", c.WGPort)}}
	for _, src := range []string{c.LANSubnet, c.WGSubnet} {
		if src == "" {
			continue
		}
		for _, p := range []int{c.ImmichPort, c.NextcloudPort, c.AdminPort} {
			rules = append(rules, []string{"allow", "from", src, "to", "any", "port", fmt.Sprint(p), "proto", "tcp"})
		}
	}
	for _, r := range rules {
		_ = exec.Command("ufw", r...).Run()
	}
	fmt.Println("  Firewall (ufw) rules added for the VPN and the home network.")
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func need(args []string, n int, msg string) {
	if len(args) < n {
		fail("usage: %s", msg)
	}
}

func check(err error) {
	if err != nil {
		fail("%v", err)
	}
}

func fail(f string, a ...any) {
	fmt.Fprintf(os.Stderr, "ics-server: "+f+"\n", a...)
	os.Exit(1)
}
