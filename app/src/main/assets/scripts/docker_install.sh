# Установка Docker официальным скриптом get.docker.com. Root. ADDUSER — пользователь для группы docker (необязательно).
set -e
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
if command -v docker >/dev/null 2>&1; then
  echo "Docker уже установлен: $(docker --version)"
else
  TMP=$(mktemp)
  trap 'rm -f "$TMP"' EXIT
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL https://get.docker.com -o "$TMP"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$TMP" https://get.docker.com
  else
    echo "Нужен curl или wget" >&2
    exit 1
  fi
  sh "$TMP" </dev/null
fi
if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
  systemctl enable --now docker >/dev/null 2>&1 || true
fi
if [ -n "$ADDUSER" ] && [ "$ADDUSER" != root ]; then
  usermod -aG docker "$ADDUSER" && echo "Пользователь $ADDUSER добавлен в группу docker (действует после переподключения)"
fi
docker --version
