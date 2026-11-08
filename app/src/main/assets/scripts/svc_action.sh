# Управление службой. Root. UNIT=имя.service, ACTION=start|stop|restart|reload|enable|disable|enable-now|disable-now
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export LC_ALL=C
die() { echo "$*" >&2; exit 1; }
case "$UNIT" in ''|*[!A-Za-z0-9@._:-]*) die "Недопустимое имя службы: $UNIT" ;; esac
case "$UNIT" in *.service) ;; *) die "Ожидается имя вида name.service" ;; esac
case "$ACTION" in
  start|stop|restart|reload|enable|disable) systemctl "$ACTION" "$UNIT" || exit $? ;;
  enable-now) systemctl enable --now "$UNIT" || exit $? ;;
  disable-now) systemctl disable --now "$UNIT" || exit $? ;;
  *) die "Неизвестное действие: $ACTION" ;;
esac
echo "Выполнено: $ACTION $UNIT"
echo "active: $(systemctl is-active "$UNIT" 2>/dev/null)"
echo "enabled: $(systemctl is-enabled "$UNIT" 2>/dev/null)"
