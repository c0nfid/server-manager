# Действия с UFW. Root.
# ACTION=enable|disable|add|delete|default|logging|reset|install|route_portbind
#   add:     RULE — аргументы ufw, уже экранированные приложением (каждый в кавычках)
#   delete:  INDEX — номер строки в `ufw show added`, EXPECT — ожидаемый текст этой строки
#   default: DIRECTION=incoming|outgoing|routed, POLICY=allow|deny|reject
#   logging: LEVEL=off|low|medium|high|full
set -e
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export LC_ALL=C
die() { echo "$*" >&2; exit 1; }

if [ "$ACTION" != install ]; then
  command -v ufw >/dev/null 2>&1 || die "UFW не установлен"
fi

allow_tcp() {   # $1 — порт, $2 — комментарий
  ufw allow "$1/tcp" comment "$2" 2>/dev/null || ufw allow "$1/tcp"
}

case "$ACTION" in
  enable)
    # Сначала разрешаем SSH, чтобы не потерять доступ к серверу.
    P=$(sshd -T 2>/dev/null | awk '$1=="port"{print $2}' | sort -un)
    for p in ${P:-22}; do
      allow_tcp "$p" 'SSH (Server Manager)'
      echo "Разрешён SSH-порт $p/tcp"
    done
    ufw --force enable
    ;;
  disable)
    ufw disable
    ;;
  add)
    [ -n "$RULE" ] || die "Пустое правило"
    eval "ufw $RULE"
    ;;
  delete)
    case "$INDEX" in ''|*[!0-9]*) die "Неверный номер правила" ;; esac
    LINE=$(ufw show added 2>/dev/null | grep '^ufw ' | sed -n "${INDEX}p")
    [ -n "$LINE" ] || die "Правило не найдено, обновите список"
    [ "$LINE" = "$EXPECT" ] || die "Список правил изменился, обновите его и повторите"
    SPEC=${LINE#ufw }
    # Комментарий не входит в идентификацию правила.
    SPEC=$(printf '%s' "$SPEC" | sed "s/ comment '.*'\$//")
    case "$SPEC" in
      route\ *) eval "ufw --force route delete ${SPEC#route }" ;;
      *) eval "ufw --force delete $SPEC" ;;
    esac
    ;;
  default)
    case "$DIRECTION" in incoming|outgoing|routed) ;; *) die "Неверное направление" ;; esac
    case "$POLICY" in allow|deny|reject) ;; *) die "Неверная политика" ;; esac
    ufw default "$POLICY" "$DIRECTION"
    ;;
  logging)
    case "$LEVEL" in off|low|medium|high|full) ;; *) die "Неверный уровень" ;; esac
    ufw logging "$LEVEL"
    ;;
  reset)
    ufw --force reset
    ;;
  install)
    export DEBIAN_FRONTEND=noninteractive
    if command -v apt-get >/dev/null 2>&1; then
      apt-get update -q && apt-get install -y -q ufw
    elif command -v dnf >/dev/null 2>&1; then
      dnf install -y ufw || die "ufw не найден в репозиториях (для RHEL/Alma/Rocky подключите EPEL)"
    elif command -v yum >/dev/null 2>&1; then
      yum install -y ufw || die "ufw не найден в репозиториях (подключите EPEL)"
    elif command -v apk >/dev/null 2>&1; then
      apk add --no-cache ufw
    elif command -v pacman >/dev/null 2>&1; then
      pacman -Sy --noconfirm --needed ufw
    elif command -v zypper >/dev/null 2>&1; then
      zypper --non-interactive install ufw
    else
      die "Не найден поддерживаемый пакетный менеджер"
    fi
    echo "UFW установлен (пока выключен)"
    ;;
  route_portbind)
    [ -f /var/lib/portbind/rules.tsv ] || die "Нет правил PortBind"
    n=0
    while IFS="$(printf '\t')" read -r in_port proto bh bi th tip tport; do
      [ -n "$in_port" ] || continue
      for pr in tcp udp; do
        [ "$proto" = both ] || [ "$proto" = "$pr" ] || continue
        ufw route allow proto "$pr" to "$tip" port "$tport" comment "portbind :$in_port" 2>/dev/null \
          || ufw route allow proto "$pr" to "$tip" port "$tport"
        n=$((n + 1))
      done
    done < /var/lib/portbind/rules.tsv
    echo "Добавлено правил пересылки: $n"
    ;;
  *)
    die "Неизвестное действие: $ACTION"
    ;;
esac
