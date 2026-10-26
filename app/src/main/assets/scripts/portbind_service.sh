# Автозапуск portbind apply после перезагрузки через systemd. Root. ACTION=install|remove
set -e
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
command -v systemctl >/dev/null 2>&1 || { echo "systemd не найден на сервере" >&2; exit 1; }
case "$ACTION" in
  install)
    [ -x /usr/local/sbin/portbind ] || { echo "Сначала установите portbind" >&2; exit 1; }
    cat > /etc/systemd/system/portbind.service <<'UNIT'
[Unit]
Description=Apply saved portbind NAT rules
Documentation=man:nft(8)
Wants=network-online.target
After=network-online.target nftables.service firewalld.service ufw.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/sbin/portbind apply

[Install]
WantedBy=multi-user.target
UNIT
    chmod 0644 /etc/systemd/system/portbind.service
    systemctl daemon-reload
    systemctl enable --now portbind.service
    echo "portbind.service включён"
    ;;
  remove)
    systemctl disable --now portbind.service 2>/dev/null || true
    rm -f /etc/systemd/system/portbind.service
    systemctl daemon-reload
    echo "portbind.service отключён и удалён (правила в ядре не изменялись)"
    ;;
  *)
    echo "Неизвестное действие: $ACTION" >&2
    exit 1
    ;;
esac
