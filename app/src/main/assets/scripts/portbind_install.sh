# Установка/обновление portbind. Root. Ожидает PB_B64 (скрипт в base64) и PB_SHA256.
set -e
umask 022
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
mkdir -p /usr/local/sbin
TMP=$(mktemp /usr/local/sbin/.portbind.XXXXXX)
trap 'rm -f "$TMP"' EXIT
printf '%s' "$PB_B64" | base64 -d > "$TMP"
if command -v sha256sum >/dev/null 2>&1; then
  echo "$PB_SHA256  $TMP" | sha256sum -c - >/dev/null 2>&1 || { echo "Контрольная сумма не совпала, установка отменена" >&2; exit 1; }
fi
chmod 0755 "$TMP"
chown 0:0 "$TMP"
mv -f "$TMP" /usr/local/sbin/portbind
trap - EXIT
echo "Установлено: /usr/local/sbin/portbind"
/usr/local/sbin/portbind --version
