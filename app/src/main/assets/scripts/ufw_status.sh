# Состояние UFW для менеджера в приложении. Root.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export LC_ALL=C
echo '@@INSTALLED'
if command -v ufw >/dev/null 2>&1; then echo yes; else echo no; fi
echo '@@PKG'
for p in apt-get dnf yum apk pacman zypper; do
  if command -v "$p" >/dev/null 2>&1; then echo "$p"; break; fi
done
if command -v ufw >/dev/null 2>&1; then
  echo '@@VERSION'
  ufw version 2>/dev/null | head -n 1
  echo '@@STATUS'
  ufw status verbose 2>/dev/null
  echo '@@ADDED'
  ufw show added 2>/dev/null | grep '^ufw '
  echo '@@DEFAULTS'
  grep -E '^DEFAULT_(INPUT|OUTPUT|FORWARD)_POLICY=' /etc/default/ufw 2>/dev/null | tr -d '"'
  echo '@@LOGLEVEL'
  grep -E '^LOGLEVEL=' /etc/ufw/ufw.conf 2>/dev/null | tr -d '"'
  echo '@@APPS'
  ufw app list 2>/dev/null | sed -n 's/^  *//p'
fi
echo '@@SSHPORTS'
P=$(sshd -T 2>/dev/null | awk '$1=="port"{print $2}' | sort -un)
echo "${P:-22}"
echo '@@DOCKER'
if command -v docker >/dev/null 2>&1; then echo yes; else echo no; fi
echo '@@PORTBIND'
if [ -f /var/lib/portbind/rules.tsv ]; then cat /var/lib/portbind/rules.tsv; fi
echo '@@END'
