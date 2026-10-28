#!/usr/bin/env bash
# Builds and installs GnuCOBOL 3.2 (the version Homebrew installs on macOS) on Ubuntu,
# so CI, Devin's VM and a Mac all run the same compiler. Ubuntu's own package is 3.1.2.
# Usage: gnucobol/install-gnucobol.sh [prefix]   (default /usr/local; uses sudo)
set -euo pipefail

VERSION=3.2
SHA256=3bb48af46ced4779facf41fdc2ee60e4ccb86eaa99d010b36685315df39c2ee2
PREFIX="${1:-/usr/local}"

if [ -x "$PREFIX/bin/cobc" ] && "$PREFIX/bin/cobc" --version | head -1 | grep -q "GnuCOBOL) $VERSION"; then
  echo "GnuCOBOL $VERSION already installed in $PREFIX"; exit 0
fi

sudo apt-get update
sudo apt-get install -y build-essential libgmp-dev libdb-dev libncurses-dev libjson-c-dev curl

SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
curl -fsSL "https://ftp.gnu.org/gnu/gnucobol/gnucobol-$VERSION.tar.xz" -o "$SRC/gnucobol.tar.xz"
echo "$SHA256  $SRC/gnucobol.tar.xz" | sha256sum -c -
tar -xJf "$SRC/gnucobol.tar.xz" -C "$SRC"
cd "$SRC/gnucobol-$VERSION"
./configure --prefix="$PREFIX" --with-db >/dev/null
make -j"$(nproc)" >/dev/null
sudo make install >/dev/null
sudo ldconfig
"$PREFIX/bin/cobc" --version | head -1
