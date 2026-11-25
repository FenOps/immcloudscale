#!/usr/bin/env bash
# Installs ImmCloudScale on an Android phone and pairs it with your server, from a computer with adb.
#
#   ./phone-setup.sh --apk ImmCloudScale.apk --server 192.168.1.50 --code K7QX-M2PA   (code from the admin console)
#   ./phone-setup.sh --apk ImmCloudScale.apk --nas alex@192.168.1.50 --person alex      (code fetched over SSH;
#                                                                                        needs sudo without a password there)
#   ./phone-setup.sh --link 'immcloudscale://pair?code=…&server=…'
#
# The phone must be connected with USB debugging, or Wireless debugging (`adb pair` + `adb connect`).
# Pairing needs one tap on the phone ("Pair"); everything else is done here.
set -euo pipefail

APK="" NAS="" PERSON="" LINK="" SERIAL="" SUDO="sudo" SERVER="" CODE=""
need_value() { [ $# -ge 2 ] && [ -n "$2" ] || { echo "phone-setup.sh: $1 needs a value (see --help)" >&2; exit 2; }; }
while [ $# -gt 0 ]; do
  case "$1" in --apk|--nas|--person|--link|--serial|--server|--code) need_value "$@" ;; esac
  case "$1" in
    --apk) APK=$2; shift 2 ;;
    --nas) NAS=$2; shift 2 ;;
    --person) PERSON=$2; shift 2 ;;
    --link) LINK=$2; shift 2 ;;
    --server) SERVER=$2; shift 2 ;;
    --code) CODE=$2; shift 2 ;;
    --serial) SERIAL=$2; shift 2 ;;
    --no-sudo) SUDO=""; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
PKG=app.immcloudscale

command -v adb >/dev/null || die "adb not found. Install Android platform tools: macOS 'brew install android-platform-tools', Ubuntu 'sudo apt install adb', Windows 'winget install Google.PlatformTools'"
[ -n "$APK$LINK$NAS$CODE" ] || die "pass --apk and a way to pair: --server/--code, --nas/--person or --link"

# Check everything we were given before touching the phone or the server.
if [ -z "$LINK" ] && [ -n "$CODE" ]; then
  [ -n "$SERVER" ] || die "--server is required with --code (the NAS address, e.g. 192.168.1.50)"
  case "$CODE" in *[!A-Za-z0-9\ -]*) die "--code looks wrong; it's like K7QX-M2PA" ;; esac
  case "$SERVER" in *[!A-Za-z0-9.:/\[\]-]*) die "--server looks wrong; use the address, e.g. 192.168.1.50" ;; esac
  case "$SERVER" in *://*) ;; *) SERVER="http://$SERVER" ;; esac
  HOSTPORT=${SERVER#*://}
  case "$HOSTPORT" in
    \[*) ;;
    *:*:*) die "write an IPv6 address in brackets, e.g. --server [fe80::1]" ;;
  esac
  case "$HOSTPORT" in
    \[*\]:*) ;;                              # [IPv6]:port
    \[*\]) SERVER="$SERVER:8790" ;;          # [IPv6]
    *:*) ;;                                   # host:port
    *) SERVER="$SERVER:8790" ;;
  esac
  ENC=$(printf '%s' "$SERVER" | sed -e 's/%/%25/g' -e 's/:/%3A/g' -e 's#/#%2F#g' -e 's/\[/%5B/g' -e 's/\]/%5D/g')
  LINK="immcloudscale://pair?code=$CODE&server=$ENC"
fi
if [ -n "$NAS" ] && [ -z "$LINK" ]; then
  [ -n "$PERSON" ] || die "--person is required with --nas (or give --server/--code)"
  case "$PERSON" in *[!a-z0-9._-]*) die "--person must be a user name like alex" ;; esac
fi
if [ -n "$APK" ] && [ ! -f "$APK" ]; then die "APK not found: $APK"; fi
# Called on every link before it goes to the phone, including one fetched from the server over SSH.
check_link() {
  case "$1" in immcloudscale://pair\?*) ;; *) die "not a pairing link: $1" ;; esac
  case "$1" in *[!A-Za-z0-9:/?=\&%._~+-]*) die "the pairing link contains unexpected characters" ;; esac
}
if [ -n "$LINK" ]; then check_link "$LINK"; fi

# With SSH access, remember the paired phones now, so a new one can be recognised afterwards.
BEFORE=""
if [ -n "$NAS" ]; then BEFORE=$(ssh "$NAS" "$SUDO ics-server devices" | awk 'NR>1 {print $1}' | sort); fi

# Pick the phone.
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$SERIAL" ]; then
  COUNT=$(printf '%s\n' "$DEVICES" | grep -c . || true)
  [ "$COUNT" -ge 1 ] || die "no phone found. Enable USB debugging (or Wireless debugging + 'adb pair'/'adb connect') and accept the prompt on the phone"
  [ "$COUNT" -eq 1 ] || die "several devices connected ($(echo $DEVICES)); choose one with --serial"
  SERIAL=$DEVICES
fi
A() { adb -s "$SERIAL" "$@"; }
say "Phone: $(A shell getprop ro.product.manufacturer | tr -d '\r') $(A shell getprop ro.product.model | tr -d '\r') (Android $(A shell getprop ro.build.version.release | tr -d '\r'))"

if [ -n "$APK" ]; then
  say "Installing $(basename "$APK") with its permissions granted"
  A install -r -g "$APK" >/dev/null
  # Pre-approve the VPN so the app doesn't need the system VPN dialog (works on most phones).
  A shell appops set $PKG ACTIVATE_VPN allow >/dev/null 2>&1 || echo "   (the phone will ask once to allow the VPN)"
fi

if [ -z "$LINK" ] && [ -n "$NAS" ]; then
  [ -n "$PERSON" ] || die "--person is required with --nas"
  say "Getting a pairing code for $PERSON from $NAS"
  JSON=$(ssh "$NAS" "$SUDO ics-server pair $(printf %q "$PERSON") --json")
  LINK=$(printf '%s' "$JSON" | sed -n 's/.*"link":"\([^"]*\)".*/\1/p')
  [ -n "$LINK" ] || die "couldn't read the pairing link: $JSON"
fi

if [ -n "$LINK" ]; then
  say "Opening the pairing link on the phone. Tap \"Pair\" on the phone now"
  check_link "$LINK"
  A shell "am start -a android.intent.action.VIEW -d '$LINK' $PKG" >/dev/null
  if [ -n "$NAS" ]; then
    printf '   waiting for the phone'
    PAIRED=""
    for _ in $(seq 1 60); do
      sleep 2; printf '.'
      # A new device ID appeared (re-pairing replaces the old entry, so counting lines isn't enough).
      NEW=$(comm -13 <(printf '%s\n' "$BEFORE") <(ssh "$NAS" "$SUDO ics-server devices" | awk 'NR>1 {print $1}' | sort))
      if [ -n "$NEW" ]; then
        printf '\n'; say "Paired! The phone now has photos, files and the home VPN."
        ssh "$NAS" "$SUDO ics-server devices" | awk -v id="$(printf '%s' "$NEW" | head -1)" 'NR==1 || $1==id'
        PAIRED=1
        break
      fi
    done
    if [ -z "$PAIRED" ]; then
      printf '\n'
      die "the phone didn't pair within 2 minutes. Check that it's on the home Wi-Fi and that you tapped \"Pair\"; then run this again (codes expire after 15 minutes)"
    fi
  else
    echo "   Check the phone: after you tap Pair it shows the Photos tab, and the console lists the phone under Phones."
  fi
fi

cat <<'NEXT'

Last steps on the phone (a few taps):
  • Photos tab → cloud icon → turn on "Back up to Immich"
  • Network tab → switch on to test the home VPN (and "Auto-connect away from home" if you like)
NEXT
