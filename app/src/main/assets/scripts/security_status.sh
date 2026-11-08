# Состояние ping и SSH для экрана «Безопасность». Root (sshd -T требует root).
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
echo '@@PING4'
cat /proc/sys/net/ipv4/icmp_echo_ignore_all 2>/dev/null
echo '@@PING6'
cat /proc/sys/net/ipv6/icmp/echo_ignore_all 2>/dev/null
echo '@@PINGOTHER'
grep -rlsE '^[[:space:]]*net\.ipv4\.icmp_echo_ignore_all' /etc/sysctl.conf /etc/sysctl.d /run/sysctl.d /usr/lib/sysctl.d /lib/sysctl.d 2>/dev/null | grep -v srvmanager
echo '@@SSHPORTS'
P=$(sshd -T 2>/dev/null | awk '$1=="port"{print $2}' | sort -un)
if [ -z "$P" ]; then
  P=$(cat /etc/ssh/sshd_config /etc/ssh/sshd_config.d/*.conf 2>/dev/null | awk 'tolower($1)=="port"{print $2}' | sort -un)
fi
echo "${P:-22}"
echo '@@SOCKET'
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet ssh.socket 2>/dev/null; then echo active; else echo no; fi
echo '@@UFW'
if command -v ufw >/dev/null 2>&1; then ufw status 2>/dev/null | head -n 1; else echo none; fi
echo '@@FIREWALLD'
if command -v firewall-cmd >/dev/null 2>&1; then firewall-cmd --state 2>/dev/null || echo 'not running'; else echo none; fi
echo '@@SELINUX'
if command -v getenforce >/dev/null 2>&1; then getenforce; else echo none; fi
echo '@@END'
