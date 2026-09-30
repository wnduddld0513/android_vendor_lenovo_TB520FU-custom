#!/usr/bin/env bash
# Source patches for the optional TB520FU customizations
# (vendor/lenovo/TB520FU-custom). Safe to re-run; re-run after every
# `repo sync`. Called at the end of device/lenovo/TB520FU/patches/apply.sh.
#
# usage: bash vendor/lenovo/TB520FU-custom/patches/apply.sh [pixelos-source-root]
#
# Patch files are named <project path with / -> _>-NNNN-<description>.patch
# and are applied with `git apply` to the matching project; nothing is
# committed. A patch for a project the ROM does not have is skipped; a patch
# that no longer applies is reported and left out, the others are still
# applied, and the script exits 1 with the list so the caller can note it.
#
# Applied patches are recorded in STATE (a copy plus "<repo-dir> <file>" in
# LIST), so the device tree's patches/apply.sh can revert them when this
# repository is removed, and so a patch dropped from here is reverted on the
# next run.
set -uo pipefail
PATCHES=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TOP=${1:-$(cd "$PATCHES/../../../.." && pwd)}
cd "$TOP"
[ -f build/envsetup.sh ] || { echo "ERROR: $TOP is not a source root" >&2; exit 1; }

STATE="$TOP/.tb520fu-custom-applied"
LIST="$STATE/applied.list"
NEW_LIST="$STATE/applied.list.new"
FAILED=()

apply_patch() { # repo-dir patch-file
    local dir=$1 patch=$2 name
    name=$(basename "$patch")
    # Projects some ROMs do not have
    if [ ! -d "$dir" ]; then
        echo "skipped (no $dir): $name"
        return 0
    fi
    if git -C "$dir" apply --check "$patch" 2>/dev/null; then
        git -C "$dir" apply "$patch" && echo "applied $name"
    elif git -C "$dir" apply --reverse --check "$patch" 2>/dev/null; then
        echo "already applied: $name"
    else
        echo "ERROR: $name does not apply to $dir, left out" >&2
        FAILED+=("$dir: $name")
        return 0
    fi
    # Record the applied patch for the device tree's revert and for the
    # dropped-patch revert below.
    mkdir -p "$STATE"
    cp "$patch" "$STATE/$name"
    echo "$dir $name" >> "$NEW_LIST"
}

rm -f "$NEW_LIST"
mkdir -p "$STATE"
# The list always exists, so the dropped-patch loop below and the mv at the end
# work even when every patch here is skipped.
: > "$NEW_LIST"

# frameworks/base
# 0005: Optional Galaxy Tab S11 Ultra (SM-X930) identity for the Play Store,
#       set from "Custom features" (persist.sys.tb520fu.spoof_galaxy). The
#       framework reads the boot-time snapshot sys.tb520fu.spoof_galaxy, so the
#       switch only takes effect after a restart. Applies to the Play Store and
#       the Play services device check-in; the GMS droidguard process keeps its
#       Play Integrity behaviour.
apply_patch frameworks/base \
    "$PATCHES/frameworks_base-0005-tb520fu-galaxy-device-spoof.patch"

# packages/apps/Updater
# 0001: SourceForge folder as update server (RSS feed of the OTA folder):
#       newest signed <package>.json of the running variant (PRC / ROW dtb),
#       Ed25519 signature, HTTPS + sourceforge.net only, package SHA-256 check.
apply_patch packages/apps/Updater \
    "$PATCHES/packages_apps_Updater-0001-sourceforge-folder.patch"
# 0002: the advertised CertifiedProps APK has an AOSPA package name and
#       only spoofs build properties; it is incompatible with this product.
#       Let the TB520FU resource overlay hide the nonfunctional updater item.
apply_patch packages/apps/Updater \
    "$PATCHES/packages_apps_Updater-0002-hide-unsupported-certified-props.patch"

# Patches dropped from this repository: revert the copy saved on a previous
# run (newest first is not needed, every entry is independent).
if [ -f "$LIST" ]; then
    while read -r dir name; do
        [ -n "$name" ] || continue
        if grep -qxF -- "$dir $name" "$NEW_LIST" 2>/dev/null; then
            continue
        fi
        if [ -f "$STATE/$name" ] && \
                git -C "$dir" apply --reverse --check "$STATE/$name" 2>/dev/null; then
            git -C "$dir" apply --reverse "$STATE/$name" &&
                echo "reverted $name (dropped from vendor/lenovo/TB520FU-custom)"
        fi
        rm -f "$STATE/$name"
    done < "$LIST"
fi
mv "$NEW_LIST" "$LIST"

if [ ${#FAILED[@]} -gt 0 ]; then
    echo
    echo "WARNING: these patches were left out:" >&2
    printf '  %s\n' "${FAILED[@]}" >&2
    exit 1
fi
exit 0
