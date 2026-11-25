#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds and signs the TAKwave release APK for a pushed tag, the same way F-Droid builds it,
# so F-Droid can verify its own build against ours (reproducible builds) and ship our signature.
#
#   scripts/build-release.sh v0.5.0
#
# What it does:
#   1. Clones REPO_URL into a temp dir and checks out the tag (never the working tree).
#   2. git reset --hard && git clean -dffx, then applies the recipe's rm/prebuild steps
#      (fdroid/com.fearmikey.takwave.yml: drop gradle/gradle-daemon-jvm.properties and the
#      foojay-resolver plugin). Keep these in sync with the recipe.
#   3. ./gradlew --no-daemon assembleRelease  (unsigned: there is no keystore.properties in a clone)
#   4. Signs with apksigner, adding only the APK Signing Block (--alignment-preserved, v2+v3).
#   5. Checks the certificate against the recipe's AllowedAPKSigningKeys and, if fdroidserver is
#      installed, runs F-Droid's verify_apks(signed, unsigned).
#
# Output: $OUT_DIR (default dist/<tag>/) / TAKwave-<version>.apk
#
# Secrets come from ~/.config/takwave-release/release.env (chmod 600), which may set
#   KEYSTORE   path to the .jks  (default ~/Documents/Keystores/TAKwave-Release.jks)
#   KS_PASS    keystore password (prompted for when unset)
#   KEY_PASS   key password      (optional; apksigner uses KS_PASS when unset)
#   KEY_ALIAS  key alias         (optional; needed only if the keystore holds several keys)
# Passwords are handed to apksigner as env:VAR and never reach Gradle, argv or the terminal.
#
# Other overrides (mainly for tests): REPO_URL, OUT_DIR, RELEASE_ENV, JAVA_HOME, ANDROID_HOME,
# EXPECTED_CERT_SHA256 (set to "" to skip the certificate check), KEEP_WORK=1, FDROID_VENV.
set -euo pipefail

APP=TAKwave
die() { echo "error: $*" >&2; exit 1; }

[[ $# -eq 1 ]] || die "usage: $0 vX.Y.Z"
TAG=$1
[[ $TAG =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]] || die "tag must look like vX.Y.Z, got '$TAG'"
VERSION=${BASH_REMATCH[1]}

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO_URL=${REPO_URL:-https://github.com/fearmikey/TAKwave.git}
OUT_DIR=${OUT_DIR:-$REPO_ROOT/dist/$TAG}
RELEASE_ENV=${RELEASE_ENV:-$HOME/.config/takwave-release/release.env}
FDROID_VENV=${FDROID_VENV:-$HOME/fdroid/venv}

export JAVA_HOME=${JAVA_HOME:-$HOME/android-studio/jbr}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH=$JAVA_HOME/bin:$PATH # apksigner needs java on PATH
[[ -x $JAVA_HOME/bin/java ]] || die "no java in JAVA_HOME=$JAVA_HOME"
BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)
APKSIGNER=$BUILD_TOOLS/apksigner
[[ -x $APKSIGNER ]] || die "apksigner not found under $ANDROID_HOME/build-tools"

OUT_APK=$OUT_DIR/$APP-$VERSION.apk
[[ ! -e $OUT_APK ]] || die "$OUT_APK already exists; remove it first"

# --- signing secrets (never printed) -------------------------------------------------------
KEYSTORE=${KEYSTORE:-} KS_PASS=${KS_PASS:-} KEY_PASS=${KEY_PASS:-} KEY_ALIAS=${KEY_ALIAS:-}
if [[ -f $RELEASE_ENV ]]; then
    perms=$(stat -c %a "$RELEASE_ENV")
    if (( 8#$perms & 077 )); then
        echo "warning: $RELEASE_ENV is readable by others (mode $perms); run: chmod 600 '$RELEASE_ENV'" >&2
    fi
    # shellcheck disable=SC1090
    source "$RELEASE_ENV" # plain assignments, not exported
else
    echo "note: $RELEASE_ENV not found; prompting for passwords" >&2
fi
KEYSTORE=${KEYSTORE:-$HOME/Documents/Keystores/$APP-Release.jks}
[[ -f $KEYSTORE ]] || die "keystore not found: $KEYSTORE"
if [[ -z $KS_PASS ]]; then
    read -rsp "Keystore password for $KEYSTORE: " KS_PASS </dev/tty; echo >&2
fi
[[ -n $KS_PASS ]] || die "empty keystore password"

# --- clone the tag -------------------------------------------------------------------------
WORK=$(mktemp -d "${TMPDIR:-/tmp}/$APP-release.XXXXXX")
cleanup() { [[ ${KEEP_WORK:-0} == 1 ]] && echo "work dir kept: $WORK" >&2 || rm -rf "$WORK"; }
trap cleanup EXIT

echo "==> cloning $REPO_URL at $TAG"
git clone --quiet "$REPO_URL" "$WORK/src"
cd "$WORK/src"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "tag $TAG not found in $REPO_URL (pushed?)"
git -c advice.detachedHead=false checkout --quiet "refs/tags/$TAG"
COMMIT=$(git rev-parse HEAD)
echo "    commit $COMMIT"

RECIPE_KEY=$(sed -n 's/^AllowedAPKSigningKeys:[[:space:]]*//p' fdroid/com.fearmikey.takwave.yml 2>/dev/null | head -1)
EXPECTED_CERT_SHA256=${EXPECTED_CERT_SHA256-$RECIPE_KEY}

# --- build (one universal APK; repeat this block per variant/ABI if splits are ever added) --
build_variant() {
    git reset --quiet --hard
    git clean -dffxq
    # F-Droid recipe: rm + prebuild
    rm -f gradle/gradle-daemon-jvm.properties
    sed -i -e '/foojay-resolver/d' settings.gradle.kts
    ./gradlew --no-daemon --console=plain assembleRelease
}
echo "==> building (JAVA_HOME=$JAVA_HOME)"
build_variant
UNSIGNED=$WORK/src/app/build/outputs/apk/release/app-release-unsigned.apk
[[ -f $UNSIGNED ]] || die "unsigned APK not found at $UNSIGNED"

# --- sign: only add the APK Signing Block (minSdk 29, so no v1) -----------------------------
echo "==> signing with $KEYSTORE"
mkdir -p "$OUT_DIR"
sign_args=(--ks "$KEYSTORE" --ks-pass env:KS_PASS
    --alignment-preserved true
    --v1-signing-enabled false --v2-signing-enabled true
    --v3-signing-enabled true --v4-signing-enabled false)
[[ -n $KEY_ALIAS ]] && sign_args+=(--ks-key-alias "$KEY_ALIAS")
[[ -n $KEY_PASS ]] && sign_args+=(--key-pass env:KEY_PASS)
KS_PASS=$KS_PASS KEY_PASS=$KEY_PASS \
    "$APKSIGNER" sign "${sign_args[@]}" --out "$OUT_APK.tmp" "$UNSIGNED"
unset KS_PASS KEY_PASS

CERTS=$("$APKSIGNER" verify --verbose --print-certs "$OUT_APK.tmp" 2>/dev/null) \
    || { rm -f "$OUT_APK.tmp"; die "apksigner verify failed"; }
# apksigner prints "Signer #1 certificate SHA-256 digest:" or "V2 Signer: certificate ..."
CERT_SHA256=$(sed -n 's/^.*certificate SHA-256 digest: //p' <<<"$CERTS" | sort -u)
[[ $CERT_SHA256 =~ ^[0-9a-f]{64}$ ]] || { rm -f "$OUT_APK.tmp"; die "expected exactly one signer, got: $CERT_SHA256"; }
if [[ -n $EXPECTED_CERT_SHA256 && $CERT_SHA256 != "$EXPECTED_CERT_SHA256" ]]; then
    rm -f "$OUT_APK.tmp"
    die "certificate $CERT_SHA256 does not match AllowedAPKSigningKeys $EXPECTED_CERT_SHA256"
fi

# --- F-Droid's own check: signed APK == unsigned APK + signature ---------------------------
if [[ -x $FDROID_VENV/bin/python ]]; then
    echo "==> fdroidserver verify_apks(signed, unsigned)"
    result=$(cd "$WORK" && "$FDROID_VENV/bin/python" -I -c '
import sys, tempfile, fdroidserver.common as c
c.read_config()
print(c.verify_apks(sys.argv[1], sys.argv[2], tempfile.mkdtemp()))' "$OUT_APK.tmp" "$UNSIGNED" 2>&1 | tail -1)
    [[ $result == None ]] || { rm -f "$OUT_APK.tmp"; die "verify_apks failed: $result"; }
    echo "    verified"
else
    echo "note: $FDROID_VENV not found; skipped verify_apks" >&2
fi
mv "$OUT_APK.tmp" "$OUT_APK"

echo
echo "$APP $VERSION ($TAG, commit $COMMIT)"
echo "  APK:                $OUT_APK"
echo "  APK SHA-256:        $(sha256sum "$OUT_APK" | cut -d' ' -f1)"
grep -E '^Verified using v[1-4] scheme' <<<"$CERTS" | sed 's/^/  /'
echo "  Cert SHA-256:       $CERT_SHA256"
echo "  Expected (recipe):  ${EXPECTED_CERT_SHA256:-<check skipped>}"
