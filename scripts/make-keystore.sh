#!/usr/bin/env bash
# Make the Android release keystore once.
# The passwords go to the macOS keychain. They never reach the screen or the repo.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
keystore_dir="${TRIPSPLIT_KEYSTORE_DIR:-$HOME/.tripsplit}"
keystore_file="$keystore_dir/tripsplit-release.jks"
key_alias="tripsplit"
service="tripsplit-android-keystore"
props="$repo_root/android/keystore.properties"

if [[ -e "$keystore_file" ]]; then
  echo "Keystore exists: $keystore_file" >&2
  echo "Stop. A new keystore would break updates for installed apps." >&2
  exit 1
fi

mkdir -p "$keystore_dir"
chmod 700 "$keystore_dir"

# One random password for the store and the key. It stays in this shell.
password="$(openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40)"

# keytool reads the password from the environment, so it is not in the process list.
STORE_PASS="$password" keytool -genkeypair \
  -keystore "$keystore_file" \
  -storetype PKCS12 \
  -alias "$key_alias" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass:env STORE_PASS \
  -keypass:env STORE_PASS \
  -dname "CN=TripSplit, O=TripSplit, C=TR" >/dev/null

# Keychain items: one for the store password, one for the key password.
# -U updates an item that exists. -w with no value would prompt, so pass the value.
security add-generic-password -U -s "$service" -a storePassword -w "$password"
security add-generic-password -U -s "$service" -a keyPassword -w "$password"

# The properties file has no passwords. release-android.sh reads them from the keychain.
umask 077
cat > "$props" <<PROPS
storeFile=$keystore_file
keyAlias=$key_alias
PROPS
unset password

echo "Keystore made: $keystore_file"
echo "Properties file: $props (no passwords inside)"
echo "Next: back up the keystore file and the keychain items. See docs/RELEASING.md."
