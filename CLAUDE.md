# ImmCloudScale: notes for Claude

People often ask Claude Code to **install** this project on their server or phone. Follow `INSTALL.md` (written for beginners: explain in plain words); this file adds the rules.

## Layout
Licensed AGPL-3.0-only (see LICENSE).
- `app/`: Android app (Kotlin, Compose). Build `./gradlew :app:assembleDebug`, test `./gradlew :app:testDebugUnitTest`.
- `server/`: `ics-server`, the Go home server (Immich + Nextcloud + WireGuard). Test `go test ./...`, package `deploy/build-deb.sh`.
- `server/deploy/install.sh`: the installer. Run it with sudo from a checkout; it builds from source when no `.deb` is given.
- `tools/phone-setup.sh`: installs the APK and pairs a phone over adb (`--server … --code …`).

## Installing on a server
- Supported: Ubuntu 22.04/24.04, Debian 12; amd64/arm64. Check before starting; stop and explain otherwise.
- Install with `curl -fsSL https://raw.githubusercontent.com/FenOps/immcloudscale/main/server/deploy/install.sh | sudo bash -s -- <setup options>`,
  or `sudo ./server/deploy/install.sh` from a clone of https://github.com/FenOps/immcloudscale.
- Your shell can't answer sudo password prompts. If `sudo -n true` fails, ask the person to create
  `/etc/sudoers.d/90-claude-install` (INSTALL.md, Step 3, Option B) and remove it when you finish.
- `install.sh` and `ics-server setup` are safe to re-run; prefer re-running over hand-editing `/opt/immcloudscale` or `/etc/wireguard/ics0.conf`
  (both are generated, and `ics-server` overwrites them).
- Setup may choose other ports than 2283/8080/8790 when those are busy; read them from its output or `sudo ics-server status`.
- Setup takes 5–20 minutes on first run. Run it in the background and check its output; don't start a second copy.
- Verify with `sudo ics-server status`, `sudo wg show ics0`, and `curl -s http://127.0.0.1:2283/api/server/ping`.
  Logs: `sudo docker compose -f /opt/immcloudscale/docker-compose.yml logs --tail 100 <service>`.
- Secrets live in `/var/lib/immcloudscale/state.json` (0600). Show the person their own passwords when asked, but never post them
  to external services, issues or pull requests.
- Ask before: deleting data under the data dir (default `/srv/immcloudscale`), formatting or mounting disks, `docker compose down -v`,
  changing firewall rules beyond what setup does, or forwarding ports (that's the person's router; give them instructions).

## Installing on a phone
- Needs adb and a phone with USB or Wireless debugging; the pairing code and port for `adb pair` come from the phone's screen.
- Get the APK from https://github.com/FenOps/immcloudscale/releases/latest (`ImmCloudScale-<version>.apk`); before the first
  release, use the latest `immcloudscale-debug-apk` CI artifact or build it with `./gradlew :app:assembleDebug`.
- Pairing: `tools/phone-setup.sh --apk <apk> --server <nas-ip> --code <code>`; the person taps "Pair" once.
  Codes come from the admin console (`http://<nas>:8790`, user `admin`) or `sudo ics-server pair <person>`; they expire after 15 minutes.

## Quality checks (required before saying a task is done)
1. Run the `qa-tester` subagent. Fix every failure and run it again until it passes.
2. Run the `code-reviewer` subagent on the changes and fix all "must fix" items.
3. If the UI changed, run the `ui-reviewer` subagent and apply its quick wins where they fit.
4. Run the `code-polisher` subagent on the changed files, then commit.
5. In the final message, give one short line per check with its result. Never say something works without having run it.

## Code and commit style
- Write code the way the existing codebase is written: same naming, formatting and amount of commenting.
- Don't mention AI, Claude or assistants in code, comments, docs, commit messages or pull request descriptions. No emoji in code, logs or commits.
- Commit messages: a short, plain, imperative subject line (for example "Fix invoice total rounding"), plus a body only when it adds something.
