#!/usr/bin/env bash
# Build, sign, verify and copy the Android release APK.
# Usage: scripts/release-android.sh [--github-release] [--dry-run]
#   --github-release  Make a draft GitHub release with the APK attached.
#   --dry-run         Check the tools and the keychain. Build nothing.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
service="tripsplit-android-keystore"
java_home_default="/Users/O_ERTAS2/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home"
export JAVA_HOME="${JAVA_HOME:-$java_home_default}"

github_release=0
dry_run=0
for arg in "$@"; do
  case "$arg" in
    --github-release) github_release=1 ;;
    --dry-run) dry_run=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

fail() { echo "Error: $*" >&2; exit 1; }

[[ -x "$JAVA_HOME/bin/java" ]] || fail "JAVA_HOME has no java: $JAVA_HOME"
[[ -f "$repo_root/android/keystore.properties" ]] \
  || fail "android/keystore.properties missing. Run scripts/make-keystore.sh."

# Read the passwords from the keychain. The values stay in the environment of gradlew.
store_password="$(security find-generic-password -s "$service" -a storePassword -w)" \
  || fail "Keychain item storePassword not found."
key_password="$(security find-generic-password -s "$service" -a keyPassword -w)" \
  || fail "Keychain item keyPassword not found."

# Find apksigner in the newest Android build-tools.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
apksigner="$(find "$sdk/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -n 1)"
[[ -n "$apksigner" ]] || fail "apksigner not found under $sdk/build-tools."

if (( dry_run )); then
  echo "Dry run passed: JAVA_HOME, keystore.properties, keychain items and apksigner are present."
  [[ -x "$repo_root/android/gradlew" ]] || echo "Note: android/gradlew is missing. The Android track adds it."
  exit 0
fi

[[ -x "$repo_root/android/gradlew" ]] || fail "android/gradlew missing. The Android track adds it."

(
  cd "$repo_root/android"
  TRIPSPLIT_STORE_PASSWORD="$store_password" TRIPSPLIT_KEY_PASSWORD="$key_password" \
    ./gradlew :app:assembleRelease
)

apk_src="$(find "$repo_root/android/app/build/outputs/apk/release" -name '*.apk' -type f | head -n 1)"
[[ -n "$apk_src" ]] || fail "No release APK found. Check the Gradle output."

"$apksigner" verify --print-certs "$apk_src"

# versionName comes from the APK itself, so the script needs no Gradle parsing.
aapt2="$(dirname "$apksigner")/aapt2"
version_name="$("$aapt2" dump badging "$apk_src" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
[[ -n "$version_name" ]] || fail "Could not read versionName from the APK."

mkdir -p "$repo_root/dist"
apk_dst="$repo_root/dist/TripSplit-$version_name.apk"
cp "$apk_src" "$apk_dst"

sha="$(shasum -a 256 "$apk_dst" | cut -d' ' -f1)"
echo "APK: $apk_dst"
echo "SHA-256: $sha"

if (( github_release )); then
  tag="android-v$version_name"
  gh release create "$tag" "$apk_dst" \
    --repo olcayertas/trip-split \
    --draft \
    --title "TripSplit Android $version_name" \
    --notes "SHA-256: $sha"
  echo "Draft release made: $tag. Publish it by hand after you check it."
fi
