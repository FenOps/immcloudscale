// ics-server installs and runs the ImmCloudScale home server: Immich (photos), Nextcloud (files)
// and a WireGuard VPN, managed together, with one-step pairing for the Android app.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
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
  user remove <name>    delete a person's accounts, photos and files [--yes]
  user admin <name>     let a person approve sign-ups and manage members [--off]
  requests              list sign-up requests from the app; approve or decline them
                                                        [--approve <id> --decline <id>]
  devices               list paired phones
  revoke <device-id>    disconnect a phone (VPN, photo and file access)
  config                change settings                             [--endpoint --lan-ip]
  tailscale             remote access through Tailscale: show it, or store a key so the app
                        joins without signing in            [--auth-key --control-url --remove-key]
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
	if errors.Is(err, os.ErrPermission) {
		fail("permission denied: run it with sudo, e.g. sudo ics-server %s", strings.Join(args, " "))
	}
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
			check(enc.Encode(map[string]any{"server": server, "code": p.Code, "link": web.PairLink(server, p.Code, p.Expires, s.TailscaleJoin()), "expires": p.Expires}))
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
			need(args, 3, "ics-server user remove <name> [--yes]")
			known := false
			for _, u := range s.Status(ctx).Users {
				known = known || u.Name == args[2]
			}
			if !known {
				fail("no user %q (see: ics-server user list)", args[2])
			}
			if !(len(args) > 3 && args[3] == "--yes") {
				fmt.Printf("This deletes %s's Immich and Nextcloud accounts, including all their photos and files, and disconnects their phones.\nType the name again to confirm: ", args[2])
				var again string
				_, _ = fmt.Scanln(&again)
				if again != args[2] {
					fail("not confirmed; nothing was removed")
				}
			}
			check(s.RemoveUser(ctx, args[2]))
			fmt.Println("Removed", args[2])
		case "admin":
			need(args, 3, "ics-server user admin <name> [--off]")
			on := !(len(args) > 3 && args[3] == "--off")
			check(s.SetAdmin(args[2], on))
			if on {
				fmt.Printf("%s is now an admin: they can approve sign-up requests and manage members in the app.\n", args[2])
			} else {
				fmt.Printf("%s is no longer an admin.\n", args[2])
			}
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
		if s.Store.Read().Config.TailscaleAuthKey != "" {
			fmt.Println("If that phone used Tailscale, also remove it at https://login.tailscale.com/admin/machines.")
		}
	case "tailscale":
		tailscaleCmd(s, args[1:])
	case "requests":
		fs := flag.NewFlagSet("requests", flag.ExitOnError)
		approve := fs.String("approve", "", "approve the request with this ID")
		decline := fs.String("decline", "", "decline the request with this ID")
		check(fs.Parse(args[1:]))
		switch {
		case *approve != "":
			req, err := s.ApproveJoin(ctx, *approve, "command line")
			check(err)
			fmt.Printf("Approved %s. Their app pairs by itself; or give them this code: %s\n", req.Name, req.Code)
		case *decline != "":
			check(s.DeclineJoin(*decline, "command line"))
			fmt.Println("Declined.")
		default:
			w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
			fmt.Fprintln(w, "ID\tNAME\tEMAIL\tPHONE\tSTATUS\tASKED")
			for _, r := range s.Requests() {
				fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\n", r.ID, r.Name, r.Email, r.Device, r.Status, r.Created.Format("2006-01-02 15:04"))
			}
			w.Flush()
		}
	case "config":
		fs := flag.NewFlagSet("config", flag.ExitOnError)
		endpoint := fs.String("endpoint", "", "public host name or IP phones use to reach the VPN")
		lan := fs.String("lan-ip", "", "the NAS's LAN address used in service URLs")
		check(fs.Parse(args[1:]))
		if *endpoint == "" && *lan == "" {
			fail("nothing to change: use --endpoint <public name or IP> and/or --lan-ip <address>")
		}
		if *endpoint != "" && !validHost(*endpoint) {
			if strings.Count(*endpoint, ":") == 1 {
				fail("give the name without a port (the VPN port is set with setup --vpn-port), e.g. myhome.duckdns.org")
			}
			fail("%q isn't a host name or IP address (e.g. myhome.duckdns.org or 203.0.113.7)", *endpoint)
		}
		if ip := net.ParseIP(*lan); *lan != "" && (ip == nil || ip.To4() == nil) {
			fail("%q isn't an IPv4 address (e.g. 192.168.1.50)", *lan)
		}
		check(st.Update(func(x *state.State) error {
			if *endpoint != "" {
				x.Config.Endpoint = *endpoint
			}
			if *lan != "" {
				if *endpoint == "" && x.Config.Endpoint == x.Config.LANAddress {
					x.Config.Endpoint = *lan // no public address was known: the VPN endpoint was the LAN address
				}
				x.Config.LANAddress, x.Config.LANManual = *lan, true
			}
			return nil
		}))
		fmt.Println("Saved. Now run `sudo ics-server setup` to apply it, then pair phones again (admin console → Pair a phone).")
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
	fs.StringVar(&o.LANAddress, "lan-ip", "", "the NAS's LAN IPv4 address (detected)")
	fs.StringVar(&o.Endpoint, "endpoint", "", "public host name or IP for the VPN (default: detected public IP)")
	fs.IntVar(&o.WGPort, "vpn-port", 0, "WireGuard UDP port (default 51820)")
	fs.StringVar(&o.FirstUser, "user", "", "the owner's user name (asked if missing)")
	fs.StringVar(&o.FirstEmail, "email", "", "the owner's email (optional)")
	noML := fs.Bool("no-ml", false, "skip Immich machine learning (smart search, faces); automatic below 6 GB RAM")
	forceML := fs.Bool("ml", false, "turn on machine learning even below 6 GB RAM")
	noVPN := fs.Bool("no-vpn", false, "don't set up WireGuard")
	withVPN := fs.Bool("vpn", false, "turn the VPN back on after --no-vpn")
	noPublic := fs.Bool("no-public-ip-lookup", false, "don't ask api.ipify.org for the public IP")
	allowSleep := fs.Bool("allow-sleep", false, "don't stop this machine from suspending (it must stay on to serve phones)")
	check(fs.Parse(args))
	if ip := net.ParseIP(o.LANAddress); o.LANAddress != "" && (ip == nil || ip.To4() == nil) {
		fail("--lan-ip %q isn't an IPv4 address (e.g. 192.168.1.50)", o.LANAddress)
	}
	if o.WGPort != 0 && (o.WGPort < 1 || o.WGPort > 65535) {
		fail("--vpn-port %d isn't a port number (1-65535)", o.WGPort)
	}
	if o.Endpoint != "" && !validHost(o.Endpoint) {
		fail("--endpoint %q isn't a host name or IP address without a port (e.g. myhome.duckdns.org)", o.Endpoint)
	}

	if os.Geteuid() != 0 {
		fail("setup needs root: sudo ics-server setup")
	}
	if _, err := exec.LookPath("docker"); err != nil {
		fail("Docker is not installed. Run the installer (see INSTALL.md), or: sudo apt install docker.io docker-compose-v2")
	}
	if err := exec.Command("docker", "info").Run(); err != nil {
		_ = exec.Command("systemctl", "start", "docker").Run()
		if err := exec.Command("docker", "info").Run(); err != nil {
			fail("Docker is installed but not running. Start it with: sudo systemctl start docker")
		}
	}
	if err := exec.Command("docker", "compose", "version").Run(); err != nil {
		fail("Docker Compose v2 is missing. Run the installer again (it adds it), or see https://docs.docker.com/compose/install/linux/")
	}
	// Flags left out keep what was chosen before (a re-run doesn't flip settings).
	stored := s.Store.Read().Config
	firstRun := stored.DBPassword == ""
	vpnOn := firstRun || stored.VPNEnabled
	switch {
	case *noVPN:
		vpnOn = false
		o.VPN = &vpnOn
	case *withVPN:
		vpnOn = true
		o.VPN = &vpnOn
	}
	if vpnOn {
		if _, err := exec.LookPath("wg-quick"); err != nil {
			fail("wireguard-tools is not installed: sudo apt install wireguard-tools")
		}
	}

	// Smart search and face recognition need memory; small machines run better without them.
	ramGB := netinfo.MemTotalGB()
	on, off := true, false
	switch {
	case *noML:
		o.MachineLearning = &off
	case *forceML:
		o.MachineLearning = &on
	case firstRun && ramGB > 0 && ramGB < 6:
		o.MachineLearning = &off
		fmt.Printf("Note: this machine has %.1f GB of RAM, so smart search and face recognition are turned off (use --ml to force them on).\n", ramGB)
	}

	dataDir := o.DataDir
	if dataDir == "" {
		dataDir = s.Store.Read().Config.DataDir
	}
	if dataDir == "" {
		dataDir = "/srv/immcloudscale"
	}
	if free := netinfo.FreeGB(dataDir); free > 0 && free < 20 {
		fmt.Printf("Warning: only %.0f GB free where photos and files will be stored (%s). The apps need about 10 GB, plus room for your photos.\n   Choose a bigger disk with --data-dir /path/on/big/disk if you have one.\n", free, dataDir)
	}

	if lan, err := netinfo.DetectLAN(); err == nil {
		o.DetectedLAN = lan.Address
		o.LANSubnet, o.EgressIface = lan.Subnet, lan.Iface
	}
	if o.Endpoint == "" && stored.Endpoint == "" && !*noPublic && vpnOn {
		o.Endpoint = netinfo.PublicIP(ctx)
	}
	if o.FirstUser == "" && len(s.Store.Read().Users) == 0 {
		o.FirstUser = askUserName()
	}
	if o.FirstUser != "" && !validName(o.FirstUser) {
		fail("%q isn't a valid user name: use 2-32 lowercase letters, digits, '.', '_' or '-', starting with a letter (e.g. alex)", o.FirstUser)
	}

	check(s.Setup(ctx, o))
	openFirewall(s)
	if !*allowSleep {
		keepAwake()
	}
	startPairingService(s)

	c := s.Store.Read().Config
	fmt.Printf("\nImmCloudScale is running.\n\n")
	fmt.Printf("  Admin console:  http://%s:%d   (user: admin, password: %s)\n", c.LANAddress, c.AdminPort, c.AdminPassword)
	fmt.Printf("  Photos (web):   http://%s:%d\n", c.LANAddress, c.ImmichPort)
	fmt.Printf("  Files (web):    http://%s:%d\n", c.LANAddress, c.NextcloudPort)
	if c.VPNEnabled {
		if c.Endpoint == "" || c.Endpoint == c.LANAddress {
			fmt.Printf("  VPN:            no public address found, so the VPN only works at home for now.\n")
			fmt.Printf("                  Fix it later with: sudo ics-server config --endpoint <your public IP or DuckDNS name>\n")
		} else {
			fmt.Printf("  VPN:            to use the apps away from home, forward UDP %d on your router to %s\n", c.WGPort, c.LANAddress)
		}
	}
	fmt.Printf("\n  Keep this machine's address fixed: in your router, reserve %s for it (\"DHCP reservation\").\n", c.LANAddress)
	st := s.Store.Read()
	for _, u := range st.Users {
		if u.IsAdmin {
			fmt.Printf("\n  Your web sign-in: %s / %s\n", u.Email, u.Password)
			printPairing(s, u.Name)
			break
		}
	}
	fmt.Println("  Write these passwords down. Show them again any time with: sudo ics-server admin-password / sudo ics-server user list")
}

// askUserName reads a user name from the terminal, a few times at most; it never loops on end-of-input.
func askUserName() string {
	in := bufio.NewReader(os.Stdin)
	for try := 0; try < 3; try++ {
		fmt.Print("\nChoose your user name (lowercase letters, e.g. alex): ")
		line, err := in.ReadString('\n')
		name := strings.TrimSpace(line)
		if validName(name) {
			return name
		}
		if err != nil { // end of input: nobody to ask
			break
		}
		fmt.Println("  Use 2-32 lowercase letters or digits (also . _ -), starting with a letter, and no spaces.")
	}
	fail("no valid user name given: run setup again with --user yourname")
	return ""
}

func validName(n string) bool {
	if len(n) < 2 || len(n) > 32 || n[0] < 'a' || n[0] > 'z' {
		return false
	}
	for _, r := range n {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '.' || r == '_' || r == '-') {
			return false
		}
	}
	return n != "ics-admin"
}

// startPairingService runs the console/pairing service: via systemd when available (it then also starts at boot),
// otherwise (containers, WSL without systemd) as a background process.
func startPairingService(s *core.Server) {
	port := s.Store.Read().Config.AdminPort
	if exec.Command("systemctl", "cat", "ics-server").Run() == nil {
		_ = exec.Command("systemctl", "daemon-reload").Run()
		_ = exec.Command("systemctl", "enable", "ics-server").Run()
		_ = exec.Command("systemctl", "restart", "ics-server").Run()
	} else if !serving(port) {
		self, _ := os.Executable()
		logf, _ := os.OpenFile("/var/log/ics-server.log", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o640)
		cmd := exec.Command(self, "--state", s.Store.Path(), "serve")
		cmd.Stdout, cmd.Stderr = logf, logf
		cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
		if err := cmd.Start(); err == nil {
			fmt.Println("  (systemd isn't available, so the pairing service runs in the background until the next reboot;")
			fmt.Println("   after a reboot start it with: sudo ics-server serve &)")
		}
	}
	for i := 0; i < 30 && !serving(port); i++ {
		time.Sleep(500 * time.Millisecond)
	}
}

// keepAwake stops suspend/hibernate: a server that sleeps can't back up phones or answer the VPN.
func keepAwake() {
	if _, err := os.Stat("/run/systemd/system"); err != nil {
		return
	}
	out, _ := exec.Command("systemctl", "is-enabled", "suspend.target").Output()
	if strings.TrimSpace(string(out)) == "masked" {
		return
	}
	if exec.Command("systemctl", "mask", "sleep.target", "suspend.target", "hibernate.target", "hybrid-sleep.target").Run() == nil {
		fmt.Println("  This machine will no longer go to sleep, so phones can always reach it (undo: sudo systemctl unmask sleep.target suspend.target hibernate.target hybrid-sleep.target).")
	}
}

func printPairing(s *core.Server, user string) {
	p, err := s.StartPairing(user)
	check(err)
	c := s.Store.Read().Config
	server := fmt.Sprintf("http://%s:%d", c.LANAddress, c.AdminPort)
	link := web.PairLink(server, p.Code, p.Expires, s.TailscaleJoin())
	q, err := qrcode.New(link, qrcode.Low)
	check(err)
	fmt.Printf("\nPair %s's phone: scan this with the phone's camera, or open ImmCloudScale → Scan QR code.\n", user)
	fmt.Print(q.ToSmallString(false))
	fmt.Printf("  Server: %s\n  Code:   %s   (valid 15 minutes, one phone)\n\n", server, p.Code)
	if !serving(c.AdminPort) {
		fmt.Println("  Warning: the pairing service isn't answering, so the phone can't pair yet. Start it with: sudo systemctl restart ics-server")
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
	if st.Config.VPNEnabled {
		fmt.Printf("  VPN (WireGuard)     %s   %s:%d\n", mark(st.VPNUp), st.Config.Endpoint, st.Config.WGPort)
	} else {
		fmt.Printf("  VPN (WireGuard)     off (set up without the VPN)\n")
	}
	switch {
	case st.TailscaleIP != "" && st.TailscaleKey:
		fmt.Printf("  Tailscale           running   %s (phones join on their own)\n", st.TailscaleIP)
	case st.TailscaleIP != "":
		fmt.Printf("  Tailscale           running   %s (phones sign in with Tailscale)\n", st.TailscaleIP)
	default:
		fmt.Printf("  Tailscale           not set up (see: ics-server tailscale)\n")
	}
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
	// Phones on the tailnet (Tailscale's own interface; harmless when Tailscale isn't installed).
	for _, p := range []int{c.ImmichPort, c.NextcloudPort, c.AdminPort} {
		rules = append(rules, []string{"allow", "in", "on", "tailscale0", "to", "any", "port", fmt.Sprint(p), "proto", "tcp"})
	}
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

// tailscaleCmd shows remote access through Tailscale and stores the key phones join with.
func tailscaleCmd(s *core.Server, args []string) {
	fs := flag.NewFlagSet("tailscale", flag.ExitOnError)
	key := fs.String("auth-key", "", "a reusable Tailscale auth key (tskey-auth-...) that paired phones join with")
	control := fs.String("control-url", "", "your Headscale address (leave out for Tailscale)")
	remove := fs.Bool("remove-key", false, "stop giving phones the auth key; they sign in with Tailscale instead")
	check(fs.Parse(args))
	if *key != "" && *remove {
		fail("use either --auth-key or --remove-key")
	}
	if *control != "" && *key == "" {
		fail("--control-url goes with --auth-key (the Headscale key)")
	}
	if *key != "" {
		if strings.ContainsAny(*key, " \t\n") || len(*key) < 20 || len(*key) > 200 {
			fail("that doesn't look like an auth key; copy the whole key (it starts with tskey-auth-)")
		}
		if *control == "" && !strings.HasPrefix(*key, "tskey-") {
			fail("Tailscale keys start with tskey-; for Headscale, also give --control-url")
		}
		if *control != "" && !strings.HasPrefix(*control, "https://") && !strings.HasPrefix(*control, "http://") {
			fail("--control-url needs the full address, e.g. https://headscale.example.org")
		}
		check(s.SetTailscaleKey(*key, *control))
		fmt.Println("Saved. Phones get the key when they pair (once Tailscale runs here); phones paired earlier get it after pairing again.")
		fmt.Println("Anyone with this key can add devices to your tailnet: make it a tagged key and limit the tag in your")
		fmt.Println("tailnet's access rules to this server's ports (Immich, Nextcloud, pairing). See INSTALL.md, Remote access.")
	}
	if *remove {
		if s.Store.Read().Config.TailscaleAuthKey == "" {
			fmt.Println("No key was saved.")
		} else {
			check(s.SetTailscaleKey("", ""))
			fmt.Println("Removed. Phones now sign in with Tailscale to connect from outside.")
		}
	}
	ip := ""
	if s.TailscaleIP != nil {
		ip = s.TailscaleIP()
	}
	if ip == "" {
		fmt.Print(`Tailscale isn't running on this server, so phones can't use it yet. To set it up:
  curl -fsSL https://tailscale.com/install.sh | sh
  sudo tailscale up
Then run: sudo ics-server tailscale
`)
		return
	}
	c := s.Store.Read().Config
	fmt.Printf("Tailscale is running: this server is %s on your tailnet.\n", ip)
	if c.TailscaleAuthKey != "" {
		fmt.Println("Phones join on their own with the saved key.")
	} else {
		fmt.Print(`Phones sign in with your Tailscale account in the app. To skip that, create a reusable key at
https://login.tailscale.com/admin/settings/keys and run: sudo ics-server tailscale --auth-key <key>
`)
	}
}

// validHost accepts a DNS name or an IP address (no port, no spaces).
func validHost(h string) bool {
	if strings.HasPrefix(h, "[") && strings.HasSuffix(h, "]") {
		ip := net.ParseIP(h[1 : len(h)-1])
		return ip != nil && ip.To4() == nil // IPv6 in brackets
	}
	if ip := net.ParseIP(h); ip != nil {
		return ip.To4() != nil // IPv6 must be written in brackets, e.g. [2001:db8::1]
	}
	if len(h) == 0 || len(h) > 253 {
		return false
	}
	for _, label := range strings.Split(h, ".") {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-') {
				return false
			}
		}
	}
	return true
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
