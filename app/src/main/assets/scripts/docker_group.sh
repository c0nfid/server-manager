# Добавление пользователя в группу docker. Root. ADDUSER.
set -e
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
case "$ADDUSER" in ''|root|-*|*[!A-Za-z0-9._-]*) echo "Неверное имя пользователя" >&2; exit 1 ;; esac
getent group docker >/dev/null 2>&1 || groupadd docker
usermod -aG docker "$ADDUSER"
echo "Пользователь $ADDUSER добавлен в группу docker. Права начнут действовать после переподключения."
echo "Внимание: группа docker фактически даёт права root на сервере."
