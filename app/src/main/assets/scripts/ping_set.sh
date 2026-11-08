# Включение/отключение ответа на ping. Root. DISABLE=1|0, IPV6=1|0.
# Значение пишется в собственный файл, который читается последним и переопределяет другие.
set -e
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
umask 022
F=/etc/sysctl.d/99-zz-srvmanager-icmp.conf
V6=/proc/sys/net/ipv6/icmp/echo_ignore_all
case "$DISABLE" in 0|1) ;; *) echo "Неверное значение DISABLE" >&2; exit 1 ;; esac
sysctl -q -w "net.ipv4.icmp_echo_ignore_all=$DISABLE"
{
  echo "# Managed by Server Manager (Android)"
  echo "net.ipv4.icmp_echo_ignore_all = $DISABLE"
} > "$F"
if [ -e "$V6" ]; then
  V=0
  [ "$DISABLE" = 1 ] && [ "$IPV6" = 1 ] && V=1
  sysctl -q -w "net.ipv6.icmp.echo_ignore_all=$V"
  echo "net.ipv6.icmp.echo_ignore_all = $V" >> "$F"
fi
chmod 0644 "$F"
if [ "$DISABLE" = 1 ]; then echo "Ping отключён (сохранено в $F)"; else echo "Ping включён (сохранено в $F)"; fi
