#!/usr/bin/env bash
# Builds the Tailscale bridge into app/libs/tsbridge.aar with gomobile.
# Needs Go (the version in go.mod is fetched automatically), ANDROID_HOME and an NDK (ANDROID_NDK_HOME,
# or the newest one under $ANDROID_HOME/ndk).
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "No Android NDK found: install one with sdkmanager \"ndk;27.3.13750724\"" >&2; exit 1; }
export ANDROID_NDK_HOME GOTOOLCHAIN="go$(sed -n 's/^go //p' go.mod)"
MOBILE=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
BIN="$(go env GOPATH)/bin"
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE" "golang.org/x/mobile/cmd/gobind@$MOBILE"
mkdir -p ../libs
PATH="$BIN:$PATH" gomobile bind -target=android/arm64,android/arm -androidapi 26 \
  -javapkg app.immcloudscale -ldflags="-s -w" -trimpath -o ../libs/tsbridge.aar .
rm -f ../libs/tsbridge-sources.jar
ls -l ../libs/tsbridge.aar
