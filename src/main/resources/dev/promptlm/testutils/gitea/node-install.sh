set -euo pipefail
if command -v node >/dev/null 2>&1; then
  node --version
  exit 0
fi

install_with_pkg() {
  if command -v apk >/dev/null 2>&1; then
    apk update
    apk add --no-cache nodejs npm sudo
    return 0
  fi
  if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y nodejs npm curl ca-certificates gnupg sudo
    return 0
  fi
  return 1
}

download_to() {
  url="$1"
  out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$out"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$out" "$url"
  else
    echo 'Neither curl nor wget available for Node download' >&2
    exit 1
  fi
}

install_with_tarball() {
  NODE_VERSION="%s"
  case "$(uname -m)" in
    x86_64) NODE_ARCH=linux-x64 ;;
    aarch64|arm64) NODE_ARCH=linux-arm64 ;;
    *) echo 'Unsupported architecture for Node install' >&2; exit 1 ;;
  esac
  TMP_DIR=$(mktemp -d)
  download_to "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-${NODE_ARCH}.tar.xz" "$TMP_DIR/node.tar.xz"
  mkdir -p /usr/local/lib/nodejs
  tar -xJf "$TMP_DIR/node.tar.xz" -C /usr/local/lib/nodejs
  NODE_DIR=/usr/local/lib/nodejs/node-v${NODE_VERSION}-${NODE_ARCH}
  ln -sf "$NODE_DIR/bin/node" /usr/local/bin/node
  ln -sf "$NODE_DIR/bin/npm" /usr/local/bin/npm
  ln -sf "$NODE_DIR/bin/npx" /usr/local/bin/npx
  rm -rf "$TMP_DIR"
  if ! command -v sudo >/dev/null 2>&1; then
    printf '#!/bin/sh\nif [ "$1" = "--version" ]; then\n  echo "sudo stub 1.0"\n  exit 0\nfi\nexec "$@"\n' > /usr/local/bin/sudo
    chmod +x /usr/local/bin/sudo
  fi
}

if ! install_with_pkg; then
  install_with_tarball
fi

if ! command -v sudo >/dev/null 2>&1; then
  if command -v apk >/dev/null 2>&1; then
    apk add --no-cache sudo || true
  elif command -v apt-get >/dev/null 2>&1; then
    apt-get install -y sudo || true
  fi
fi

node --version
