#!/usr/bin/env bash
# Installs the ImmCloudScale server on Ubuntu 22.04+/Debian 12 (amd64 or arm64), then runs `ics-server setup`.
#
#   curl -fsSL https://raw.githubusercontent.com/FenOps/immcloudscale/main/server/deploy/install.sh | sudo bash
#   curl -fsSL …/install.sh | sudo bash -s -- --user alex --data-dir /mnt/tank/immcloudscale
#
# Where the package comes from, in this order:
#   1. ICS_DEB=/path/to/immcloudscale-server_X_arch.deb       a package you copied to the server
#   2. a package already built in ../dist next to this script
#   3. built from this checkout (installs Go if needed)         when run from a `git clone`
#   4. the newest GitHub release
#   5. a fresh clone of the repository, built here              (before the first release, or with ICS_FROM_SOURCE=1)
# Set ICS_SKIP_SETUP=1 to install without running setup.
set -euo pipefail

REPO="FenOps/immcloudscale"
GO_VERSION="1.24.7"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root: sudo $0 $*"
. /etc/os-release
case "${ID:-} ${ID_LIKE:-}" in *ubuntu*|*debian*) ;; *) die "this installer supports Ubuntu and Debian (found ${PRETTY_NAME:-unknown})" ;; esac
ARCH=$(dpkg --print-architecture)
case "$ARCH" in amd64|arm64) ;; *) die "only amd64 and arm64 are supported (found $ARCH)" ;; esac
HERE=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || pwd)
SRC=$(cd "$HERE/.." 2>/dev/null && pwd || true)   # immcloudscale/server when run from a checkout

say "Installing Docker, Docker Compose and WireGuard from ${PRETTY_NAME}"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard-tools iptables curl ca-certificates >/dev/null
if ! command -v docker >/dev/null 2>&1; then
  apt-get install -y -qq docker.io >/dev/null 2>&1 || { curl -fsSL https://get.docker.com | sh; }
fi
if ! docker compose version >/dev/null 2>&1; then
  apt-get install -y -qq docker-compose-v2 >/dev/null 2>&1 || apt-get install -y -qq docker-compose-plugin >/dev/null 2>&1 \
    || die "couldn't install Docker Compose v2; see https://docs.docker.com/compose/install/linux/"
fi
if [ -d /run/systemd/system ]; then systemctl enable --now docker >/dev/null; fi

# Go for building from source: the distribution's if new enough (1.21+ fetches the exact toolchain), else go.dev.
GO_NEEDED=$(sed -n 's/^go \([0-9.]*\).*/\1/p' "$SRC/go.mod" 2>/dev/null || echo 1.24)
go_ok() {   # a Go at least as new as go.mod asks for, so no toolchain download is needed
  command -v go >/dev/null 2>&1 || return 1
  local v; v=$(go env GOVERSION 2>/dev/null | sed 's/^go//')
  [ "$(printf '%s\n%s\n' "$v" "$GO_NEEDED" | sort -V | head -1)" = "$GO_NEEDED" ]
}
ensure_go() {
  export PATH="/usr/local/go/bin:$PATH"
  go_ok && return
  apt-get install -y -qq golang-go >/dev/null 2>&1 || true
  go_ok && return
  say "Installing Go $GO_VERSION (needed once to build ics-server)"
  # dl.google.com is where go.dev/dl downloads are served from.
  curl -fsSL "https://dl.google.com/go/go${GO_VERSION}.linux-${ARCH}.tar.gz" -o /tmp/go.tgz || die "couldn't download Go"
  tar -C /usr/local -xzf /tmp/go.tgz && rm -f /tmp/go.tgz
  go_ok || die "Go installation failed"
}

DEB="${ICS_DEB:-}"
if [ -z "$DEB" ]; then DEB=$(ls "$SRC"/dist/immcloudscale-server_*_"$ARCH".deb 2>/dev/null | sort -V | tail -1 || true); fi
if [ -z "$DEB" ] && [ -f "$SRC/go.mod" ] && [ -x "$SRC/deploy/build-deb.sh" ]; then
  say "Building ics-server from $SRC"
  ensure_go
  (cd "$SRC" && GOTOOLCHAIN=local ARCHES="$ARCH" ./deploy/build-deb.sh)
  DEB=$(ls "$SRC"/dist/immcloudscale-server_*_"$ARCH".deb | sort -V | tail -1)
fi
if [ -z "$DEB" ] && [ "${ICS_FROM_SOURCE:-}" != 1 ]; then
  say "Downloading the newest release"
  TMP=$(mktemp -d)
  URL=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
    | grep -o "https://[^\"]*immcloudscale-server_[^\"]*_${ARCH}.deb" | head -1 || true)
  if [ -z "$URL" ]; then
    echo "   no release found; building from source instead"
  elif curl -fsSL --retry 3 -o "$TMP/ics.deb" "$URL"; then
    DEB="$TMP/ics.deb"
  else
    echo "   couldn't download $URL; building from source instead"
  fi
fi
if [ -z "$DEB" ]; then
  say "Building ics-server from the latest source"
  apt-get install -y -qq git >/dev/null
  CLONE=$(mktemp -d)
  git clone -q --depth 1 "https://github.com/$REPO.git" "$CLONE/immcloudscale"
  SRC="$CLONE/immcloudscale/server"
  GO_NEEDED=$(sed -n 's/^go \([0-9.]*\).*/\1/p' "$SRC/go.mod")
  ensure_go
  (cd "$SRC" && GOTOOLCHAIN=local ARCHES="$ARCH" ./deploy/build-deb.sh)
  DEB=$(ls "$SRC"/dist/immcloudscale-server_*_"$ARCH".deb | sort -V | tail -1)
fi

say "Installing $(basename "$DEB")"
apt-get install -y -qq "$(readlink -f "$DEB")" >/dev/null
ics-server version >/dev/null

if [ "${ICS_SKIP_SETUP:-}" = 1 ]; then
  say "Installed. Run: sudo ics-server setup"
  exit 0
fi
say "Setting up Immich, Nextcloud and the home VPN (the first run downloads about 5 GB)"
if [ -r /dev/tty ] && { : < /dev/tty; } 2>/dev/null; then
  exec ics-server setup "$@" < /dev/tty   # lets setup ask for your user name when piped
else
  exec ics-server setup "$@"
fi
