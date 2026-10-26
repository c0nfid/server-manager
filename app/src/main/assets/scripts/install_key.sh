# Добавляет публичный ключ в ~/.ssh/authorized_keys. Ожидает переменную PUBKEY.
set -e
umask 077
D="$HOME/.ssh"
F="$D/authorized_keys"
mkdir -p "$D"
chmod 700 "$D"
[ -f "$F" ] || : > "$F"
chmod 600 "$F"
BODY=$(printf '%s\n' "$PUBKEY" | awk '{print $1" "$2}')
if grep -qF "$BODY" "$F"; then
  echo "EXISTS"
else
  if [ -s "$F" ] && [ -n "$(tail -c 1 "$F")" ]; then echo >> "$F"; fi
  printf '%s\n' "$PUBKEY" >> "$F"
  echo "ADDED"
fi
if command -v restorecon >/dev/null 2>&1; then restorecon -R "$D" >/dev/null 2>&1 || true; fi
