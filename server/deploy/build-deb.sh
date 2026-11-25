#!/usr/bin/env bash
# Builds immcloudscale-server_<version>_<arch>.deb for amd64 and arm64 into ../dist/.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(grep -oP 'const Version = "\K[^"]+' internal/core/core.go)
mkdir -p dist
for ARCH in ${ARCHES:-amd64 arm64}; do
  ROOT=$(mktemp -d)
  chmod 0755 "$ROOT"
  mkdir -p "$ROOT/DEBIAN" "$ROOT/usr/bin" "$ROOT/lib/systemd/system"
  chmod 0755 "$ROOT/DEBIAN" "$ROOT/usr" "$ROOT/usr/bin" "$ROOT/lib" "$ROOT/lib/systemd" "$ROOT/lib/systemd/system"
  CGO_ENABLED=0 GOOS=linux GOARCH=$ARCH go build -trimpath -ldflags "-s -w" -o "$ROOT/usr/bin/ics-server" ./cmd/ics-server
  install -m 0644 deploy/ics-server.service "$ROOT/lib/systemd/system/ics-server.service"
  mkdir -p "$ROOT/usr/share/doc/immcloudscale-server"
  { printf 'Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/\nUpstream-Name: ImmCloudScale\nSource: https://github.com/FenOps/immcloudscale\n\nFiles: *\nCopyright: ImmCloudScale contributors\nLicense: AGPL-3.0-only\n'
    sed 's/^$/./; s/^/ /' ../LICENSE; } > "$ROOT/usr/share/doc/immcloudscale-server/copyright"
  cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: immcloudscale-server
Version: $VERSION
Architecture: $ARCH
Maintainer: ImmCloudScale <noreply@immcloudscale.local>
Depends: wireguard-tools, iptables, docker.io (>= 20.10) | docker-ce
Recommends: docker-compose-v2 | docker-compose-plugin
Section: net
Priority: optional
Homepage: https://github.com/FenOps/immcloudscale
Description: All-in-one home server for the ImmCloudScale Android app
 Installs and manages Immich (photos), Nextcloud (files) and a WireGuard VPN
 together, with one-step phone pairing by QR code and a web admin console.
CONTROL
  cat > "$ROOT/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e
if [ -d /run/systemd/system ]; then
  systemctl daemon-reload || true
  systemctl enable ics-server >/dev/null 2>&1 || true
  if [ -f /var/lib/immcloudscale/state.json ]; then systemctl restart ics-server || true; fi
fi
if [ ! -f /var/lib/immcloudscale/state.json ]; then
  echo
  echo "ImmCloudScale server installed. Finish with:  sudo ics-server setup"
  echo
fi
POSTINST
  cat > "$ROOT/DEBIAN/prerm" <<'PRERM'
#!/bin/sh
set -e
if [ -d /run/systemd/system ] && [ "$1" = remove ]; then
  systemctl disable --now ics-server >/dev/null 2>&1 || true
fi
# Your photos, files and containers are left in place; see the README to remove them.
PRERM
  chmod 0755 "$ROOT/DEBIAN/postinst" "$ROOT/DEBIAN/prerm"
  dpkg-deb --root-owner-group --build "$ROOT" "dist/immcloudscale-server_${VERSION}_${ARCH}.deb" >/dev/null
  rm -rf "$ROOT"
  echo "dist/immcloudscale-server_${VERSION}_${ARCH}.deb"
done
