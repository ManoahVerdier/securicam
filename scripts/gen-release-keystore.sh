#!/usr/bin/env bash
# Generate the Android release keystore for Securicam.
#
# The keystore + its credentials are PRIVATE — they are gitignored and must
# never leave your machine. If you lose them you can't push updates of the
# same APK (Android refuses to install upgrades signed with a different key).
#
# Usage:
#   ./scripts/gen-release-keystore.sh           # interactive password prompt
#   STORE_PASS=xxx KEY_PASS=xxx ./scripts/gen-release-keystore.sh   # CI-style
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE_DIR="$REPO_ROOT/android/app/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/release.keystore"
PROPS_FILE="$KEYSTORE_DIR/release.properties"

mkdir -p "$KEYSTORE_DIR"

if [[ -f "$KEYSTORE_FILE" ]]; then
    echo "Keystore already exists at $KEYSTORE_FILE — aborting." >&2
    exit 1
fi

read -rp "Common Name (e.g. Manoah Verdier): " CN
read -rp "Organization (e.g. Securicam): " ORG
read -rp "Country code (e.g. FR): " COUNTRY

if [[ -z "${STORE_PASS:-}" ]]; then
    read -rsp "Keystore password (>= 8 chars): " STORE_PASS; echo
fi
if [[ -z "${KEY_PASS:-}" ]]; then
    read -rsp "Key password (press Enter to reuse keystore password): " KEY_PASS; echo
    KEY_PASS="${KEY_PASS:-$STORE_PASS}"
fi

keytool -genkeypair -v \
    -keystore "$KEYSTORE_FILE" \
    -storetype PKCS12 \
    -alias securicam \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$STORE_PASS" \
    -keypass   "$KEY_PASS" \
    -dname "CN=$CN, O=$ORG, C=$COUNTRY"

cat > "$PROPS_FILE" <<EOF
storeFile=keystore/release.keystore
storePassword=$STORE_PASS
keyAlias=securicam
keyPassword=$KEY_PASS
EOF
chmod 600 "$PROPS_FILE" "$KEYSTORE_FILE"

echo
echo "Created:"
echo "  $KEYSTORE_FILE"
echo "  $PROPS_FILE  (gitignored — contains plaintext passwords)"
echo
echo "Backup these two files to a safe location (password manager, encrypted USB)."
