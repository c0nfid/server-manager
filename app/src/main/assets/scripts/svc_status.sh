# Статус и журнал службы. Root (журнал доступен root). UNIT, LINES.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export LC_ALL=C
case "$UNIT" in ''|*[!A-Za-z0-9@._:-]*) echo "Недопустимое имя службы" >&2; exit 1 ;; esac
case "$LINES" in ''|*[!0-9]*) LINES=100 ;; esac
echo '@@STATUS'
systemctl status "$UNIT" --no-pager -l -n 0 2>&1
echo '@@LOG'
journalctl -u "$UNIT" -n "$LINES" --no-pager -o short-iso 2>&1
echo '@@END'
