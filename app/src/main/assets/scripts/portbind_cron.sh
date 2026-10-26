# Расписание portbind refresh через /etc/cron.d. Root. INTERVAL=минуты (0 = удалить).
set -e
F=/etc/cron.d/portbind-refresh
L=/etc/logrotate.d/portbind-refresh
if [ "$INTERVAL" = 0 ]; then
  rm -f "$F" "$L"
  echo "Расписание refresh удалено"
  exit 0
fi
[ -d /etc/cron.d ] || { echo "Каталог /etc/cron.d отсутствует: установите cron (cron/cronie)" >&2; exit 1; }
case "$INTERVAL" in
  1) SPEC='* * * * *' ;;
  60) SPEC='0 * * * *' ;;
  *) SPEC="*/$INTERVAL * * * *" ;;
esac
umask 022
cat > "$F" <<CRON
# Managed by Server Manager (Android): portbind DNS refresh
SHELL=/bin/sh
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
$SPEC root /usr/local/sbin/portbind refresh >> /var/log/portbind-refresh.log 2>&1
CRON
chmod 0644 "$F"
if [ -d /etc/logrotate.d ]; then
  cat > "$L" <<'ROTATE'
/var/log/portbind-refresh.log {
    weekly
    rotate 4
    compress
    missingok
    notifempty
}
ROTATE
  chmod 0644 "$L"
fi
echo "Расписание установлено: $SPEC"
