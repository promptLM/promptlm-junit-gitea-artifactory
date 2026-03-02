#!/usr/bin/env bash
set -euo pipefail

if [ -z "${GPG_PRIVATE_KEY:-}" ]; then
  echo "GPG_PRIVATE_KEY is empty."
  exit 1
fi

if [ -z "${GPG_PASSPHRASE:-}" ]; then
  echo "GPG_PASSPHRASE is empty."
  exit 1
fi

if ! printf '%s\n' "$GPG_PRIVATE_KEY" | head -n1 | grep -q "BEGIN PGP PRIVATE KEY BLOCK"; then
  echo "GPG_PRIVATE_KEY does not look like an ASCII-armored key."
  exit 1
fi

mkdir -p ~/.gnupg
chmod 700 ~/.gnupg
printf 'pinentry-mode loopback\n' >> ~/.gnupg/gpg.conf
printf 'allow-loopback-pinentry\n' >> ~/.gnupg/gpg-agent.conf

printf '%s\n' "$GPG_PRIVATE_KEY" | gpg --batch --yes --pinentry-mode loopback --import

FPR="$(gpg --batch --list-secret-keys --with-colons | awk -F: '$1=="fpr" {print $10; exit}')"
if [ -z "$FPR" ]; then
  echo "No secret key imported."
  exit 1
fi

TMPFILE="$(mktemp)"
cleanup() {
  rm -f "$TMPFILE" "${TMPFILE}.sig"
}
trap cleanup EXIT

printf 'gpg-sign-test\n' > "$TMPFILE"
gpg --batch --yes --pinentry-mode loopback --passphrase "$GPG_PASSPHRASE" \
  --local-user "$FPR" --detach-sign "$TMPFILE"
