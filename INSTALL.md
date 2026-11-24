# Installing ImmCloudScale

This guide takes you from a **fresh server** and a **new Android phone** to working photos, files and a home VPN.
Every part has two ways to do it:

* 🤖 **Let Claude do it.** [Claude Code](https://claude.com/claude-code) runs the commands, checks the results and fixes problems.
  You answer a few questions and do the things only a person can: taps on the phone and router settings.
* 🧑 **Do it yourself.** Usually one command for the server and one download for the phone.

Expect about **30 minutes**. Most of that is the server downloading about 5 GB of software.

---

## Before you start

| You need | Notes |
|---|---|
| **A server**: a NAS, mini PC or old laptop | **Ubuntu 22.04 / 24.04** or **Debian 12**, 64-bit (amd64 or arm64, Raspberry Pi 4/5 included). At least 4 GB RAM (8 GB with smart search), plus disk space for your photos and files. Plugged into your router by cable if possible. |
| **An Android phone** | Android 8 or newer. |
| **Your router's admin page** | Only for using the apps away from home (Part 3). |
| **Claude Code** *(only for the 🤖 paths)* | Needs a Claude Pro/Max subscription or an Anthropic API account. |

---

## Part 1: the server

### 🧑 Option A: one command

On the server (with a keyboard and screen, or over `ssh yourname@<server-ip>` from your computer):

```bash
curl -fsSL https://raw.githubusercontent.com/FenOps/immcloudscale/main/server/deploy/install.sh | sudo bash
```

It installs Docker, Docker Compose and WireGuard, then the ImmCloudScale server, and asks for your user name.
To set options up front, put them after `bash -s --`:

```bash
curl -fsSL https://raw.githubusercontent.com/FenOps/immcloudscale/main/server/deploy/install.sh \
  | sudo bash -s -- --user alex --email alex@example.com --data-dir /mnt/bigdisk/immcloudscale
#   --no-ml           skip smart search / face recognition on small machines (under 8 GB RAM)
#   --endpoint NAME   a DuckDNS or other name for using the VPN away from home (see Part 3)
```

When setup finishes it prints:

```
✅ ImmCloudScale is running.
  Admin console:  http://192.168.1.50:8790   (user: admin, password: …)
  Photos (web):   http://192.168.1.50:2283
  Files (web):    http://192.168.1.50:8080
  Your web sign-in: alex@example.com / …
Pair alex's phone: [QR code]   Code: K7QX-M2PA
```

**Write the passwords down.** You can show them again later with `sudo ics-server admin-password` and `sudo ics-server user list`.

**Give the server a fixed address.** In your router, reserve the server's IP address (often called "DHCP reservation" or
"static lease"). Phones reach Photos and Files at that address, so it must not change.

<details>
<summary>Other ways to install the server</summary>

* **From a release package:** download `immcloudscale-server_<version>_amd64.deb` (or `_arm64` for Raspberry Pi) from the
  [Releases page](https://github.com/FenOps/immcloudscale/releases), then
  `sudo apt install ./immcloudscale-server_*.deb && sudo ics-server setup`.
* **From source:** `git clone https://github.com/FenOps/immcloudscale && sudo ./immcloudscale/server/deploy/install.sh`
  (it builds `ics-server`, downloading Go if needed).
</details>

### 🤖 Option B: let Claude install it

**1. Open a terminal on the server** (keyboard and screen, or `ssh yourname@<server-ip>`).

**2. Give Claude temporary admin rights.** Claude's commands can't type your `sudo` password, so allow passwordless `sudo` for the
install. Claude removes this at the end, and you can remove it yourself any time with `sudo rm /etc/sudoers.d/90-claude-install`.
```bash
echo "$USER ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/90-claude-install
```

**3. Install and start Claude Code:**
```bash
curl -fsSL https://claude.ai/install.sh | bash
exec $SHELL -l          # reload the shell so `claude` is found
mkdir -p ~/immcloudscale-setup && cd ~/immcloudscale-setup
claude                  # sign in when asked
```

**4. Paste this prompt** (change the name and email first):

```text
Install the ImmCloudScale home server on this machine, from https://github.com/FenOps/immcloudscale.
Read its INSTALL.md (Part 1), CLAUDE.md and server/README.md first.
My user name: alex   My email: alex@example.com

1. Check the system: Ubuntu 22.04+/Debian 12, amd64 or arm64, RAM, and free disk space. Tell me the LAN IP address
   and remind me to reserve it in my router (DHCP reservation) so it never changes.
2. Ask me where to keep photos and files (default /srv/immcloudscale; list the disks and their free space so I can pick)
   and whether to turn on smart search / face recognition (needs about 4 GB more RAM; skip it on small machines).
3. Run the installer:
   curl -fsSL https://raw.githubusercontent.com/FenOps/immcloudscale/main/server/deploy/install.sh | sudo bash -s -- --user <name> --email <email> [--data-dir …] [--no-ml]
   Run it in the background and check on it until it finishes (the first run downloads about 5 GB).
4. Verify: `sudo ics-server status` shows everything running; http://<lan-ip>:2283, :8080 and :8790 respond;
   `sudo wg show ics0` lists the VPN. If something fails, read the logs, fix it, and explain what you changed.
5. Show me, clearly, in one summary: the admin console address and password, my web sign-in for Photos and Files,
   and a fresh pairing code (`sudo ics-server pair <name>`). Save the same summary to ~/immcloudscale-setup/README-mine.txt (chmod 600).
6. Tell me exactly what to set on my router for remote access (forward UDP 51820 to this machine), and ask whether
   I want a free DuckDNS name in case my home IP changes. If yes, I'll give you the DuckDNS token; set up the updater
   (cron) and run `sudo ics-server config --endpoint <name>.duckdns.org`.
7. When everything works, remove /etc/sudoers.d/90-claude-install.

Ask before deleting anything, formatting disks, or changing firewall rules beyond what the installer does.
```

**5. While it works:** approve the commands Claude asks to run and answer its questions. At the end you'll have the admin console address and password, your web sign-in, and a pairing code.

> **Prefer to run Claude on your computer instead?** Start `claude` on your computer, and in the prompt replace
> "on this machine" with "on my server over SSH: `ssh yourname@192.168.1.50`". Claude runs each command through `ssh`.
> Do step 2 on the server first, and set up an SSH key (`ssh-copy-id yourname@192.168.1.50`) so Claude isn't asked for a password.

---

## Part 2: the phone

You need a pairing code. If the one from setup has expired (they last 15 minutes), open the **admin console**
(`http://<server>:8790`, user `admin`) → **People** → **Pair a phone**, or run `sudo ics-server pair alex` on the server.

### 🧑 Option A: download and scan

1. **On the phone, open [github.com/FenOps/immcloudscale/releases/latest](https://github.com/FenOps/immcloudscale/releases/latest)**
   and download **`ImmCloudScale-<version>.apk`**.
2. **Install it.** Open the download and allow "Install unknown apps" for your browser when Android asks.
   Google Play Protect may warn that the app is unknown, because it isn't from the Play Store. Choose **Install anyway**.
3. **Pair.** Connect the phone to your home Wi-Fi, open ImmCloudScale → **Scan QR code**, and point it at the QR code on the
   server screen or in the admin console. Or scan the QR code with the phone's camera app, or type the server address
   (`192.168.1.50`) and the code by hand.
4. **Allow** the VPN request and photo access when the app asks.

### 🤖 Option B: let Claude install and pair it (from your computer)

**1. On the phone, turn on developer mode:** Settings → About phone → tap **Build number** seven times.
Then Settings → System → **Developer options**:
* Android 11 or newer: turn on **Wireless debugging** (phone and computer on the same Wi-Fi), then tap it to see its details.
* Older phones: turn on **USB debugging** and plug the phone into the computer.

**2. On your computer:** [install Claude Code](https://claude.com/claude-code), open a terminal in an empty folder, run `claude`, and paste:

```text
Install the ImmCloudScale Android app on my phone and pair it with my home server.
Project: https://github.com/FenOps/immcloudscale. Read its INSTALL.md (Part 2) and CLAUDE.md first.
My server's address is 192.168.1.50.

1. Install adb (Android platform tools) if missing.
2. Download the newest ImmCloudScale-*.apk from https://github.com/FenOps/immcloudscale/releases/latest,
   and tools/phone-setup.sh from the repository (or clone it with --depth 1).
3. Connect to the phone. For Wireless debugging, ask me for the IP:port and 6-digit code under
   "Pair device with pairing code", run `adb pair`, then `adb connect` to the IP:port shown on the Wireless debugging
   screen. For USB, check `adb devices` and tell me to accept the prompt on the phone.
4. Ask me for the pairing code from the admin console, then run:
   ./phone-setup.sh --apk <apk> --server 192.168.1.50 --code <code>
   and tell me when to tap "Pair" on the phone.
5. Tell me the last taps to do (turn on backup), and remind me I can turn Wireless debugging off again.
```

**3. What you do:** read out the Wireless debugging pairing code and the server's pairing code, then tap **Pair** on the phone when Claude says.
The script installs the app with its permissions already granted, pre-approves the VPN, and opens the pairing screen.

> Running it yourself: `./tools/phone-setup.sh --apk ImmCloudScale.apk --server 192.168.1.50 --code K7QX-M2PA`.
> With SSH access to the server and passwordless sudo, `--nas yourname@192.168.1.50 --person alex` fetches the code by itself
> and waits until the phone shows up as paired.

### Finish on the phone (both options)

* **Photos** tab → cloud icon (top right) → turn on **Back up to Immich**. Choose Wi-Fi only, charging only and which folders to include.
* **Network** tab → flip the switch to check the VPN connects. Optionally turn on **Auto-connect away from home**, then
  **Add current Wi-Fi as trusted** while you're at home.

---

## Part 3: use it away from home

Photos and Files already work on your home Wi-Fi. For everywhere else:

1. **Forward a port on your router:** UDP **51820** → your server's IP (for example 192.168.1.50).
   Look for "Port forwarding", "Virtual server" or "NAT" in the router's admin page. Forward only this one port; never forward
   2283, 8080 or 8790.
   * 🤖 Claude can't log in to your router, but it can walk you through it: tell it your router's brand and model.
     If you use the Claude in Chrome browser extension, Claude can also click through the router's web page with you watching.
2. **If your home IP address changes** (most home connections): get a free name at [duckdns.org](https://www.duckdns.org)
   and tell the server: `sudo ics-server config --endpoint yourname.duckdns.org`. Then pair phones again from the console
   so they get the new address. 🤖 Step 6 of the server prompt sets this up for you, including the updater that keeps the name current.
3. **Test:** on the phone, turn Wi-Fi off, open **Network** and switch the VPN on, then open **Photos**.
   The Network tab shows "Connected to home", and both servers show a response time.

> **Behind CGNAT?** Some mobile-broadband and fibre providers don't give you a public IP address, so forwarding can't work.
> Ask your provider for a public IPv4 address, or run the VPN endpoint on a small cloud server. Claude can help you check:
> "Is my home connection behind CGNAT?"

---

## Add family members

* Admin console → **People** → type a name → **Add person**, or `sudo ics-server user add sam --email sam@example.com`.
* Each person gets their own photo library and files, with one password for both web apps (shown in the console).
* Pair their phone with **Pair a phone** next to their name (Part 2). A lost phone? **Revoke** it in the console.

---

## Troubleshooting

| Problem | What to do |
|---|---|
| Setup "timed out waiting for services" | Usually a slow download or too little RAM. Run `sudo ics-server setup` again (it's safe), and see logs with `sudo docker compose -f /opt/immcloudscale/docker-compose.yml logs --tail 50`. On machines with 4 GB or less, add `--no-ml`. |
| Phone: "That pairing code is wrong or has expired" | Codes work once, for 15 minutes. Make a new one in the console. |
| Phone can't reach the server while pairing | Phone and server must be on the same network. Check `http://<server>:8790` opens in the phone's browser; if `ufw` is on, `sudo ics-server setup` adds the rules. |
| VPN connects but no photos away from home | Check the port forward is **UDP** 51820 to the right IP, and the endpoint is right (`sudo ics-server status`). If your home IP changed, see Part 3, step 2. |
| "Install blocked" on the phone | Allow "Install unknown apps" for that app, or use the 🤖 adb path. |
| `adb pair` fails | The pairing code and port change every time you open "Pair device with pairing code". Read them again. |
| Forgot a password | `sudo ics-server admin-password` (console) and `sudo ics-server user list` (web sign-ins). |

**Ask Claude.** Run `claude` on the server and describe the problem, for example: *"ImmCloudScale Photos says it can't reach the server. Diagnose it."*
The commands it needs are in [server/README.md](server/README.md).

---

## What Claude can and can't do here

| Claude can | Only you can |
|---|---|
| Check the system, install everything and verify every service | Sign in to Claude and DuckDNS |
| Watch setup, read logs and fix problems | Approve `sudo` once (step 2 of Part 1, Option B) and the commands Claude asks to run |
| Make people and pairing codes, set up DuckDNS, change the endpoint | Turn on Developer options / Wireless debugging and read out codes from the phone |
| Install the APK with permissions, pre-approve the VPN and open pairing | Tap **Pair** on the phone, and allow anything Android insists on asking |
| Explain your router's port-forwarding screen | Change router settings (unless you let Claude in Chrome do it with you watching) |
