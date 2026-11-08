# Перенос SSH на другой порт. Root.
# ACTION=add      — добавить порт NEW к текущим (старые продолжают работать)
# ACTION=finalize — оставить только NEW; CLOSE_OLD=1 закрывает старые порты (OLD) в UFW/firewalld
# ACTION=revert   — вернуть конфигурацию из BACKUP и убрать NEW из файрвола
set -e
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
umask 022
CFG=/etc/ssh/sshd_config
OVR_DIR=/etc/systemd/system/ssh.socket.d
OVR=$OVR_DIR/srvmanager-port.conf

die() { echo "$*" >&2; exit 1; }

case "$NEW" in ''|*[!0-9]*) die "Неверный порт: $NEW" ;; esac
[ "$NEW" -ge 1 ] && [ "$NEW" -le 65535 ] || die "Порт должен быть в диапазоне 1–65535"
[ -f "$CFG" ] || die "Не найден $CFG"
command -v sshd >/dev/null 2>&1 || die "Не найден sshd"

current_ports() {
  p=$(sshd -T 2>/dev/null | awk '$1=="port"{print $2}' | sort -un | tr '\n' ' ')
  [ -n "$p" ] || p=22
  echo $p
}

socket_active() {
  command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet ssh.socket 2>/dev/null
}

listening() {
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk 'NR>1{print $4}' | grep -qE "[:.]$1\$"
  else
    netstat -ltn 2>/dev/null | awk 'NR>2{print $4}' | grep -qE "[:.]$1\$"
  fi
}

# Порты пишутся управляемым блоком в начало sshd_config (до любых Match);
# прежние директивы Port комментируются с пометкой #srvmanager#.
write_ports() {
  tmp=$(mktemp "$CFG.XXXXXX")
  {
    echo "# >>> srvmanager ports (managed by Server Manager)"
    for p in "$@"; do echo "Port $p"; done
    echo "# <<< srvmanager ports"
    sed -e '/^# >>> srvmanager ports/,/^# <<< srvmanager ports/d' \
        -e 's/^\([[:space:]]*\)\([Pp][Oo][Rr][Tt][[:space:]]\)/#srvmanager# \1\2/' "$CFG"
  } > "$tmp"
  chmod 0644 "$tmp"
  mv -f "$tmp" "$CFG"
  if socket_active; then
    # Ubuntu 22.10+: sshd запускается через ssh.socket, порты задаются в юните сокета.
    mkdir -p "$OVR_DIR"
    {
      echo "[Socket]"
      echo "ListenStream="
      for p in "$@"; do echo "ListenStream=0.0.0.0:$p"; echo "ListenStream=[::]:$p"; done
    } > "$OVR"
  fi
}

apply_ssh() {
  sshd -t || return 1
  if socket_active; then
    systemctl daemon-reload
    systemctl restart ssh.socket
    systemctl restart ssh.service 2>/dev/null || true
  elif command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
    systemctl reload-or-restart ssh 2>/dev/null || systemctl reload-or-restart sshd
  elif command -v service >/dev/null 2>&1; then
    service ssh reload 2>/dev/null || service sshd reload 2>/dev/null || service sshd restart
  else
    pkill -HUP -o -x sshd
  fi
}

open_fw() {
  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
    ufw allow "$1/tcp" comment 'SSH (Server Manager)' >/dev/null 2>&1 || ufw allow "$1/tcp" >/dev/null
    echo "UFW: открыт порт $1/tcp"
  fi
  if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port="$1/tcp" >/dev/null
    firewall-cmd --reload >/dev/null
    echo "firewalld: открыт порт $1/tcp"
  fi
}

close_fw() {
  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
    ufw --force delete allow "$1/tcp" >/dev/null 2>&1 || true
    ufw --force delete allow "$1" >/dev/null 2>&1 || true
    if [ "$1" = 22 ]; then
      ufw --force delete allow OpenSSH >/dev/null 2>&1 || true
      ufw --force delete allow ssh >/dev/null 2>&1 || true
    fi
    echo "UFW: закрыт порт $1"
  fi
  if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    firewall-cmd --permanent --remove-port="$1/tcp" >/dev/null 2>&1 || true
    [ "$1" = 22 ] && firewall-cmd --permanent --remove-service=ssh >/dev/null 2>&1 || true
    firewall-cmd --reload >/dev/null
    echo "firewalld: закрыт порт $1"
  fi
}

restore_backup() {
  cp -p "$1" "$CFG"
  if [ -f "$1.socket" ]; then cp -p "$1.socket" "$OVR"; else rm -f "$OVR"; fi
}

case "$ACTION" in
  add)
    OLD=$(current_ports)
    for p in $OLD; do [ "$p" = "$NEW" ] && die "SSH уже слушает порт $NEW"; done
    listening "$NEW" && die "Порт $NEW уже занят другим процессом"
    if [ -f /var/lib/portbind/rules.tsv ] && awk -F '\t' -v p="$NEW" '$1==p{f=1} END{exit !f}' /var/lib/portbind/rules.tsv; then
      die "Порт $NEW занят перенаправлением PortBind"
    fi
    DROPIN=$(grep -liE '^[[:space:]]*port[[:space:]]' /etc/ssh/sshd_config.d/*.conf 2>/dev/null || true)
    [ -z "$DROPIN" ] || die "Порт задан в $DROPIN — уберите оттуда директиву Port и повторите"
    LA=$(cat "$CFG" /etc/ssh/sshd_config.d/*.conf 2>/dev/null | awk 'tolower($1)=="listenaddress"{print $2}' | grep -E '(\]:[0-9]+$)|(^[^:]+:[0-9]+$)' || true)
    [ -z "$LA" ] || die "В ListenAddress указан порт ($LA) — перенос порта не поддерживается"
    if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce)" = Enforcing ]; then
      command -v semanage >/dev/null 2>&1 || die "SELinux включён: установите semanage (пакет policycoreutils-python-utils)"
      semanage port -a -t ssh_port_t -p tcp "$NEW" 2>/dev/null || semanage port -m -t ssh_port_t -p tcp "$NEW"
      echo "SELinux: порт $NEW разрешён для sshd"
    fi
    BACKUP="$CFG.srvmanager.$(date +%Y%m%d%H%M%S)"
    cp -p "$CFG" "$BACKUP"
    if [ -f "$OVR" ]; then cp -p "$OVR" "$BACKUP.socket"; fi
    write_ports $OLD "$NEW"
    if ! sshd -t; then
      restore_backup "$BACKUP"
      die "Проверка sshd -t не прошла, конфигурация восстановлена"
    fi
    open_fw "$NEW"
    if ! apply_ssh; then
      restore_backup "$BACKUP"; apply_ssh || true
      die "Не удалось перезапустить sshd, конфигурация восстановлена"
    fi
    i=0
    while [ $i -lt 10 ] && ! listening "$NEW"; do sleep 1; i=$((i + 1)); done
    if ! listening "$NEW"; then
      restore_backup "$BACKUP"; apply_ssh || true
      die "sshd не начал слушать порт $NEW, конфигурация восстановлена"
    fi
    echo "SSH слушает порты: $OLD $NEW"
    echo "BACKUP=$BACKUP"
    echo "OLD=$OLD"
    ;;
  finalize)
    write_ports "$NEW"
    sshd -t || die "Проверка sshd -t не прошла"
    apply_ssh || die "Не удалось перезапустить sshd"
    if [ "$CLOSE_OLD" = 1 ]; then
      for p in $OLD; do [ "$p" != "$NEW" ] && close_fw "$p"; done
    fi
    echo "SSH теперь слушает только порт $NEW"
    ;;
  revert)
    case "$BACKUP" in /etc/ssh/sshd_config.srvmanager.*) ;; *) die "Неверный путь резервной копии" ;; esac
    [ -f "$BACKUP" ] || die "Резервная копия не найдена: $BACKUP"
    restore_backup "$BACKUP"
    apply_ssh || die "Не удалось перезапустить sshd после восстановления"
    close_fw "$NEW"
    echo "Конфигурация SSH восстановлена из $BACKUP"
    ;;
  *)
    die "Неизвестное действие: $ACTION"
    ;;
esac
