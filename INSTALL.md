# Installing ImmCloudScale: step by step

By the end of this guide you'll have:

* **your own server at home** that stores your photos, videos and files (instead of Google Photos and Google Drive), and
* **the ImmCloudScale app on your Android phone**, backing up your photos and reaching your server from anywhere.

No experience needed: follow the steps in order. Each step says what to do, what you'll see, and how long it takes.
Wherever you see 🤖, you can let **Claude** do that step for you instead.

**Time:** about 1 hour the first time, mostly waiting for downloads.

---

## Words you'll meet

| Word | What it means here |
|---|---|
| **Server** | The computer that stays on at home and keeps your photos and files. An old PC, a mini PC or a Raspberry Pi works. |
| **Ubuntu** | A free operating system (like Windows), which the server runs. |
| **Terminal** | A window where you type commands. On a Mac it's the *Terminal* app; on Windows it's *Terminal* or *PowerShell*. |
| **SSH** | Controlling the server from your own computer's terminal, so the server doesn't need its own screen and keyboard. |
| **IP address** | The server's address on your home network, for example `192.168.1.50`. |
| **Router** | Your home internet box, which makes the Wi-Fi. |
| **APK** | An Android app file you install yourself, without the Play Store. |
| **VPN** | A secure tunnel from your phone to your home, so the app works when you're away. |

---

## What you need

- [ ] **A computer to be the server** with at least **4 GB of RAM**, ideally 8 GB, and enough disk space for your photos.
      It will be **erased** if you install Ubuntu on it, so back up anything on it first.
- [ ] **A USB stick** of 8 GB or more, only if the server doesn't have Ubuntu yet.
- [ ] **An Android phone**, Android 8 or newer.
- [ ] **A network cable** from the server to your router (recommended; Wi-Fi works too).
- [ ] **Your router's admin login**, only needed for Step 6 (using the app away from home). It's often printed on a sticker on the router.
- [ ] *(Optional, for 🤖 steps)* **[Claude Code](https://claude.com/claude-code)**, which needs a Claude Pro or Max subscription or an Anthropic API account.

---

## Step 1: Put Ubuntu on the server (about 20 minutes)

**Skip this step** if the server already runs Ubuntu 22.04, Ubuntu 24.04 or Debian 12.

<details>
<summary><b>On a PC, laptop or mini PC</b></summary>

1. On your everyday computer, download **Ubuntu Server 24.04 LTS** from [ubuntu.com/download/server](https://ubuntu.com/download/server).
2. Write it to the USB stick with [balenaEtcher](https://etcher.balena.io) (Windows, Mac or Linux): choose the downloaded file, choose the USB stick, then click **Flash**.
3. Plug the USB stick into the server and turn it on. Open the boot menu (usually by pressing **F12**, **F10**, **F2** or **Esc** right after power-on) and pick the USB stick.
4. Follow the installer. Accept the defaults, except:
   * **Storage:** "Use an entire disk" erases that disk. Pick the disk you want to use.
   * **Profile:** choose your name, a server name (e.g. `homeserver`), a **username** and a **password**. Write them down.
   * **SSH:** tick **"Install OpenSSH server"**. This lets you control the server from your computer.
   * **Featured snaps:** don't tick anything; ImmCloudScale installs what it needs.
5. When it says **"Reboot now"**, remove the USB stick and press Enter.

</details>

<details>
<summary><b>On a Raspberry Pi 4 or 5</b></summary>

1. On your computer, install [Raspberry Pi Imager](https://www.raspberrypi.com/software/) and insert the Pi's SD card (or SSD).
2. Choose your Pi model, then **Other general-purpose OS → Ubuntu → Ubuntu Server 24.04 LTS (64-bit)**, then the card.
3. When asked to apply OS customisation, choose **Edit settings**: set a **username** and **password**, and on the **Services** tab tick **Enable SSH**. Save, then write.
4. Put the card in the Pi, connect the network cable, and power it on. Wait 3 minutes for the first start.

</details>

### Find the server's IP address

* **If the server has a screen:** after it starts, log in. The welcome text shows a line like `IPv4 address for eth0: 192.168.1.50`.
  Or type `hostname -I`; the first number is the address.
* **Otherwise:** open your router's admin page and look at the list of connected devices for the server's name.

### Give it a fixed address (important)

In your router's admin page, find **"DHCP reservation"**, **"Address reservation"** or **"Static lease"**, and reserve the server's address
so it never changes. Phones reach your photos at this address. (🤖 Ask Claude "How do I reserve an IP address on my *router brand and model*?")

---

## Step 2: Connect to the server from your computer (2 minutes)

You can also do everything directly on the server with its own keyboard and screen. Then skip to Step 3.

1. Open a terminal on your computer:
   * **Windows:** press Start, type **Terminal** (or **PowerShell**), and open it.
   * **Mac:** press ⌘ + Space, type **Terminal**, and press Enter.
2. Type this, using your server's username and address, then press Enter:
   ```bash
   ssh yourname@192.168.1.50
   ```
3. The first time, it asks *"Are you sure you want to continue connecting?"*. Type `yes` and press Enter.
4. Type your server password and press Enter. **Nothing shows on screen while you type a password; that's normal.**

You're in when the line starts with something like `yourname@homeserver:~$`. Commands you type now run on the server.

---

## Step 3: Install ImmCloudScale on the server (15–40 minutes)

Pick **one** of the two options.

### Option A: one command

Copy this line, paste it into the terminal (right-click → Paste, or Ctrl+Shift+V), and press Enter:

```bash
curl -fsSL https://raw.githubusercontent.com/FenOps/immcloudscale/main/server/deploy/install.sh | sudo bash
```

**What happens:**

1. It asks for your **password** (the server's). Type it and press Enter (again, nothing shows while you type).
2. It installs everything ImmCloudScale needs:
   * **Docker**, which runs the apps,
   * **WireGuard**, the VPN,
   * **Immich**, for your photos,
   * **Nextcloud**, for your files,
   * a database.
   You'll see progress bars while it downloads about 5 GB. **Leave it running; on a slow connection this can take half an hour.**
3. It asks: **"Choose your user name"**. Type a short lowercase name such as `alex` and press Enter.
4. When it's finished you'll see a box like this:

```
✅ ImmCloudScale is running.

  Admin console:  http://192.168.1.50:8790   (user: admin, password: Xy7kPq2mWv9cRt4B)
  Photos (web):   http://192.168.1.50:2283
  Files (web):    http://192.168.1.50:8080
  VPN:            to use the apps away from home, forward UDP 51820 on your router to 192.168.1.50

  Your web sign-in: alex@immcloudscale.local / Mn3bVx8LqZ2wTr5K

Pair alex's phone: scan this with the camera, or open ImmCloudScale → Pair with server.
█████████████████████
██ ▄▄▄▄▄ █▀▄█ ▄▄▄▄▄ █      ← a QR code for your phone
...
  Server: http://192.168.1.50:8790
  Code:   K7QX-M2PA   (valid 15 minutes, one phone)
```

📝 **Write down** the admin console password and your web sign-in. (Forgot them? On the server, `sudo ics-server admin-password`
shows the console password and `sudo ics-server user list` shows the sign-ins.)

ImmCloudScale also makes sure the server stays on: it turns off sleep mode, and everything starts again by itself after a power cut or reboot.

<details>
<summary>Options for this command</summary>

Add options after `bash -s --`, for example:

```bash
curl -fsSL https://raw.githubusercontent.com/FenOps/immcloudscale/main/server/deploy/install.sh \
  | sudo bash -s -- --user alex --email alex@example.com --data-dir /mnt/bigdisk/immcloudscale
```

| Option | What it does |
|---|---|
| `--user alex` | your user name, so it doesn't ask |
| `--email you@example.com` | your email, used to sign in on the web |
| `--data-dir /path` | where photos and files are stored, for example a second, bigger disk (default `/srv/immcloudscale`) |
| `--no-ml` / `--ml` | turn smart search and face recognition off or on. They're switched off automatically on machines with less than 6 GB of RAM. |
| `--endpoint myhome.duckdns.org` | your home's internet name, for using the app away from home (Step 6) |
| `--allow-sleep` | don't turn off sleep mode |

Other ways to install: a `.deb` package from [Releases](https://github.com/FenOps/immcloudscale/releases)
(`sudo apt install ./immcloudscale-server_*.deb && sudo ics-server setup`), or from source
(`git clone https://github.com/FenOps/immcloudscale && sudo ./immcloudscale/server/deploy/install.sh`).
</details>

### Option B: 🤖 let Claude install it

Claude checks your machine, asks you a few questions, runs the installation, checks that everything works, and fixes problems as they come up.

**1. Give Claude permission to install software.** Claude can't type your password, so run this once
(it asks for your password one last time). Claude removes this permission when it's done.
```bash
echo "$USER ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/90-claude-install
```

**2. Install Claude Code and start it:**
```bash
curl -fsSL https://claude.ai/install.sh | bash
exec $SHELL -l
mkdir -p ~/immcloudscale-setup && cd ~/immcloudscale-setup
claude
```
The first time, Claude Code shows a link to sign in to your Claude account. Open it on any device and follow it.

**3. Copy this message into Claude.** Change the name and email first, then press Enter:

```text
Install the ImmCloudScale home server on this machine, from https://github.com/FenOps/immcloudscale.
Read its INSTALL.md (Step 3), CLAUDE.md and server/README.md first. I'm a beginner: explain what you're doing in plain words.
My user name: alex   My email: alex@example.com

1. Check the system: Ubuntu 22.04+/Debian 12, amd64 or arm64, RAM, and free disk space. Tell me the LAN IP address
   and remind me to reserve it in my router (DHCP reservation) so it never changes.
2. Ask me where to keep photos and files (default /srv/immcloudscale; list the disks and their free space so I can pick).
3. Run the installer:
   curl -fsSL https://raw.githubusercontent.com/FenOps/immcloudscale/main/server/deploy/install.sh | sudo bash -s -- --user <name> --email <email> [--data-dir …]
   Run it in the background and check on it until it finishes (the first run downloads about 5 GB).
4. Verify: `sudo ics-server status` shows everything running, and http://<lan-ip>:2283, :8080 and :8790 respond
   (setup may pick other ports if those were busy; it prints them). `sudo wg show ics0` lists the VPN.
   If something fails, read the logs, fix it, and explain what you changed.
5. Show me, clearly, in one summary: the admin console address and password, my web sign-in for Photos and Files,
   and a fresh pairing code (`sudo ics-server pair <name>`). Save the same summary to ~/immcloudscale-setup/README-mine.txt (chmod 600).
6. Explain how to set up remote access on my router (forward UDP 51820 to this machine). Ask whether I want a free DuckDNS
   name in case my home IP changes. If yes, I'll give you the DuckDNS token; set up the updater (cron) and run
   `sudo ics-server config --endpoint <name>.duckdns.org`.
7. When everything works, remove /etc/sudoers.d/90-claude-install.

Ask before deleting anything, formatting disks, or changing firewall rules beyond what the installer does.
```

**4. While Claude works:** it asks before running commands. Read what it wants to do and approve.
At the end, it shows you the same box as Option A.

---

## Step 4: Check the admin console (2 minutes)

On your computer (or phone) connected to your home Wi-Fi, open a web browser and go to the **admin console** address from Step 3,
for example **`http://192.168.1.50:8790`**.

* Sign in with user **`admin`** and the **admin console password**.
* You should see **Photos**, **Files** and **Home VPN** marked **Running**.
* This is where you'll add family members, pair phones, and see which phones are connected.

---

## Step 5: Install the app on your phone (5–10 minutes)

**Connect the phone to your home Wi-Fi first.** Pairing only works at home.

Pick **one** option.

### Option A: download and scan

**1. Download the app.** On the phone, open this page in Chrome:
**[github.com/FenOps/immcloudscale/releases/latest](https://github.com/FenOps/immcloudscale/releases/latest)**.
Scroll to **Assets** and tap **`ImmCloudScale-….apk`** to download it.

**2. Install it.** Tap the download when it finishes (or find it in your **Files** / **Downloads** app). Android protects you
from apps outside the Play Store, so you'll see a couple of warnings. This is expected:

| You see | Tap |
|---|---|
| *"For your security, your phone is not allowed to install unknown apps from this source"* | **Settings**, switch on **Allow from this source**, then go back |
| *"Do you want to install this app?"* | **Install** |
| *"Unsafe app blocked"* / *"App scan recommended"* (Google Play Protect) | **More details**, then **Install anyway** (or **Install without scanning**) |

**3. Pair it with your server.** Open **ImmCloudScale**. On the first screen, under **ImmCloudScale Server**, tap **Scan QR code**
and point the phone at the QR code, either on the server's terminal or in the admin console (**People → Pair a phone**).
Then tap **Pair** if asked.

* No QR code handy? Type the **Server address** (e.g. `192.168.1.50`) and the **Pairing code** (e.g. `K7QX-M2PA`), then tap **Pair**.
* "Code wrong or expired"? Codes last 15 minutes and work once. Make a new one in the admin console with **Pair a phone**.

**4. Turn on backup.** After pairing, the app asks **"Turn on backup?"**. Tap **Turn on backup** and switch on **Back up to Immich**.
When Android asks about photos and videos, choose **Allow all**, not "Select photos". Also allow notifications, so you can see backup progress.

That's it: your photos start copying to your server. The first backup of a full phone can take a few hours. Leave the phone on Wi-Fi and charging overnight.

### Option B: 🤖 let Claude install and pair it (from your computer)

Good if you're setting up several phones, or don't want to change install settings on the phone.

**1. Turn on developer options on the phone (one time):**
* Settings → **About phone** → tap **Build number** seven times, until it says "You are now a developer". On Samsung: About phone → Software information → Build number.
* Settings → **System** → **Developer options** (Samsung: Settings → Developer options):
  * Android 11 or newer: switch on **Wireless debugging**. Phone and computer must be on the same Wi-Fi.
  * Older phones: switch on **USB debugging** and connect the phone to the computer with a USB cable.

**2. On your computer**, [install Claude Code](https://claude.com/claude-code), open a terminal, type `claude`, press Enter, and paste this message
(use your server's address):

```text
Install the ImmCloudScale Android app on my phone and pair it with my home server. I'm a beginner.
Project: https://github.com/FenOps/immcloudscale. Read its INSTALL.md (Step 5) and CLAUDE.md first.
My server's address is 192.168.1.50.

1. Install adb (Android platform tools) on this computer if it's missing.
2. Download the newest ImmCloudScale-*.apk from https://github.com/FenOps/immcloudscale/releases/latest,
   and tools/phone-setup.sh from the repository (or clone it with --depth 1).
3. Connect to the phone. For Wireless debugging: tell me to tap "Pair device with pairing code" on the phone, ask me for
   the IP address:port and 6-digit code it shows, run `adb pair`, then `adb connect` to the IP address:port shown on the
   main Wireless debugging screen. For USB: run `adb devices` and tell me to tap "Allow" on the phone.
4. Ask me for a pairing code from the admin console (People → Pair a phone), then run:
   ./phone-setup.sh --apk <apk> --server 192.168.1.50 --code <code>
   and tell me when to tap "Pair" on the phone.
5. Tell me how to turn on backup, and remind me to switch Wireless debugging off when we're done.
```

**3. What you do:** read out the codes the phone shows, then tap **Pair** on the phone when Claude asks. Claude installs the app with
its permissions already allowed, and pairs it.

---

## Step 6: Use it away from home (10 minutes)

At home everything already works. To use the app from anywhere, your router has to let the VPN in.

**1. Forward one port on your router.** Open the router's admin page (often `http://192.168.1.1` or `http://192.168.0.1`; the address is on the
router's sticker) and find **Port forwarding**. It's sometimes called "Virtual server", "NAT" or "Port mapping". Add a rule:

| Setting | Value |
|---|---|
| Name | `ImmCloudScale` |
| Protocol | **UDP** |
| External port | **51820** |
| Internal IP address | your server's address, e.g. **192.168.1.50** |
| Internal port | **51820** |

Save. Forward **only** this one port; never the 2283, 8080 or 8790 ports.
🤖 Not sure where it is? Ask Claude: *"How do I set up port forwarding on my (router brand and model)?"*

**2. If your home internet address changes** (true for most home connections), give your home a free name that follows it:
1. Go to [duckdns.org](https://www.duckdns.org), sign in, and create a name, e.g. `alexhome` (giving you `alexhome.duckdns.org`).
2. On the server, run `sudo ics-server config --endpoint alexhome.duckdns.org`.
3. Set up the DuckDNS updater by following duckdns.org's **install → linux cron** instructions, or 🤖 ask Claude on the server:
   *"Set up the DuckDNS updater for alexhome with token <your token>"*.
4. Pair your phones again (admin console → **Pair a phone**) so they learn the new name.

**3. Test it.** On the phone, turn **Wi-Fi off** so you're on mobile data. Open ImmCloudScale → **Network** tab → switch it **on**.
It should say **Connected to home**. Then open the **Photos** tab: your photos load.

**4. Make it automatic (recommended).** On the **Network** tab, switch on **Auto-connect away from home**. Then, while you're at home on Wi-Fi,
tap **Add current Wi-Fi as trusted**. The phone now connects to home by itself whenever you leave, and disconnects when you're back.

> **Still not connecting?** Some internet providers (especially mobile-broadband and some fibre providers) don't give your home a public address
> ("CGNAT"), and then port forwarding can't work. Ask your provider for a "public IPv4 address", or ask Claude: *"Is my home connection behind CGNAT?"*

---

## Everyday use

* **Photos** and **Collections** work like Google Photos. Backup runs on its own (by default only on Wi-Fi).
* **Files** works like Google Drive: tap **New** to upload or make a folder; **⋮** on a file to share a link, star it or keep it offline.
* **Network** shows whether you're connected to home.
* On a computer, use **Photos (web)** and **Files (web)** from Step 3 in a browser, signing in with your web sign-in.

## Add family members

1. Admin console → **People** → type a name → **Add person**. Their web sign-in appears in the list.
2. Next to their name, tap **Pair a phone**, and on their phone follow **Step 5**.
3. Lost a phone? Admin console → **Phones** → **Revoke**. It loses access immediately.

---

## Troubleshooting

| Problem | What to do |
|---|---|
| `ssh: connect to host … Connection refused` | OpenSSH isn't installed on the server. On the server itself, run `sudo apt install -y openssh-server`. |
| The installer stopped with an error | Run the same command again: it's safe and continues where it left off. Still stuck? Copy the last 20 lines and ask Claude. |
| "timed out waiting for services" | Usually a slow download or too little memory. Run the command again. On a machine with 4 GB of RAM, add `--no-ml` (see options). |
| The admin console page doesn't open | Use `http://` (not https) and the port from the box, e.g. `:8790`. Your computer must be on the same Wi-Fi. On the server, `sudo ics-server status` shows what's running. |
| Phone: "Can't reach the server" | The phone must be on your **home Wi-Fi** to pair, and the server must be on. Check the address and port. |
| Phone: "That pairing code is wrong or has expired" | Make a new code in the admin console (**Pair a phone**). Codes last 15 minutes. |
| Phone: "App not installed" | An older ImmCloudScale build is on the phone. Uninstall it first, then install again. |
| Backup says "Only some photos can be backed up" | Tap **Open settings** → Permissions → Photos and videos → **Allow all**. |
| Works at home but not away | Check Step 6: the rule must be **UDP** 51820 to the right address. If your home address changed, set up DuckDNS. |

**Ask Claude for help.** On the server, run `claude` and describe the problem, for example:
*"ImmCloudScale on this server isn't working: the Photos app can't connect. Diagnose and fix it."*

**Report a bug:** [github.com/FenOps/immcloudscale/issues](https://github.com/FenOps/immcloudscale/issues).

---

## What Claude can and can't do for you

| 🤖 Claude can | 🧑 Only you can |
|---|---|
| Check the server, install everything, verify it and fix problems | Install Ubuntu on the server (Step 1) and plug it in |
| Explain every step in plain words | Sign in to your Claude, router and DuckDNS accounts |
| Create people, pairing codes and the DuckDNS updater | Allow Claude to install software (Step 3 B, one command) |
| Install the app on your phone and start pairing | Turn on developer options on the phone and tap **Pair** |
| Tell you exactly what to change on your router | Change the router settings |
