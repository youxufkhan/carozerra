#!/usr/bin/env bash
# Build carozerra_<version>_all.deb into packaging/dist/
#
# Usage: packaging/build-deb.sh [version]   (default: 0.0.0-dev)
set -euo pipefail

VERSION="${1:-0.0.0-dev}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG_DIR="$ROOT/packaging"
BUILD="$PKG_DIR/build/carozerra_${VERSION}_all"
DIST="$PKG_DIR/dist"

rm -rf "$BUILD"
mkdir -p "$BUILD/DEBIAN" \
         "$BUILD/usr/share/carozerra" \
         "$BUILD/usr/bin" \
         "$BUILD/usr/share/applications" \
         "$BUILD/usr/share/pixmaps"

# app payload
cp "$ROOT/carozerra.py" "$ROOT/decode.py" "$BUILD/usr/share/carozerra/"

# assets/ holds 83 clips (~11M) plus the README's screenshots, but the desktop
# app only ever opens the handful named in carozerra.py's CLIPS. Ship exactly
# those -- read out of carozerra.py itself so this can't drift from the app.
mkdir -p "$BUILD/usr/share/carozerra/assets/clips"
cp "$ROOT/assets/pioneer.png" "$BUILD/usr/share/carozerra/assets/pioneer.png"
mapfile -t CLIPS < <(python3 - "$ROOT/carozerra.py" <<'PY'
import ast, re, sys
src = open(sys.argv[1]).read()
m = re.search(r'^CLIPS\s*=\s*(\[.*?\])', src, re.S | re.M)
if not m:
    sys.exit("build-deb.sh: could not find CLIPS in carozerra.py")
print('\n'.join(ast.literal_eval(m.group(1))))
PY
)
[ "${#CLIPS[@]}" -gt 0 ] || { echo "build-deb.sh: CLIPS came back empty" >&2; exit 1; }
for clip in "${CLIPS[@]}"; do
  cp "$ROOT/assets/clips/$clip" "$BUILD/usr/share/carozerra/assets/clips/$clip"
done
echo "packaged ${#CLIPS[@]} clip(s)"

# launcher shim
cat > "$BUILD/usr/bin/carozerra" <<'SHIM'
#!/bin/sh
exec python3 /usr/share/carozerra/carozerra.py "$@"
SHIM
chmod 755 "$BUILD/usr/bin/carozerra"

# desktop entry + icon
cp "$PKG_DIR/debian/carozerra.desktop" "$BUILD/usr/share/applications/carozerra.desktop"
cp "$ROOT/assets/pioneer.png" "$BUILD/usr/share/pixmaps/carozerra.png"

# control file (version substituted)
sed "s/__VERSION__/${VERSION}/" "$PKG_DIR/debian/control.in" > "$BUILD/DEBIAN/control"

mkdir -p "$DIST"
dpkg-deb --build --root-owner-group "$BUILD" "$DIST/carozerra_${VERSION}_all.deb"

echo "built: $DIST/carozerra_${VERSION}_all.deb"
