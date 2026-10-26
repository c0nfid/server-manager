# Состояние установки portbind. Выполняется без root.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
echo '@@VERSION'
if [ -x /usr/local/sbin/portbind ]; then /usr/local/sbin/portbind --version 2>/dev/null || echo 'portbind unknown'; fi
echo '@@BASH'
bash -c 'echo "${BASH_VERSINFO[0]}.${BASH_VERSINFO[1]}"' 2>/dev/null
echo '@@DEPS'
for c in nft getent flock timeout sysctl awk sort mktemp stat; do
  if command -v "$c" >/dev/null 2>&1; then echo "$c ok"; else echo "$c missing"; fi
done
echo '@@SYSTEMD'
if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
  echo "enabled=$(systemctl is-enabled portbind.service 2>/dev/null)"
  echo "active=$(systemctl is-active portbind.service 2>/dev/null)"
else
  echo 'none'
fi
echo '@@CRON'
if [ -f /etc/cron.d/portbind-refresh ]; then grep -v '^#' /etc/cron.d/portbind-refresh | grep 'portbind' | head -n 1; fi
echo '@@CROND'
if [ -d /etc/cron.d ]; then echo yes; else echo no; fi
echo '@@PKG'
for p in apt-get dnf yum apk pacman zypper; do
  if command -v "$p" >/dev/null 2>&1; then echo "$p"; break; fi
done
echo '@@PRIV'
if [ "$(id -u)" = 0 ]; then echo root; elif command -v sudo >/dev/null 2>&1; then echo sudo; else echo none; fi
echo '@@END'
