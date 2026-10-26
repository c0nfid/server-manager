# Снимок состояния сервера для экрана мониторинга. Выполняется без root.
export LC_ALL=C
echo '@@HOST'
hostname 2>/dev/null || cat /proc/sys/kernel/hostname
echo '@@OS'
if [ -r /etc/os-release ]; then
  . /etc/os-release
  echo "${PRETTY_NAME:-$NAME}"
else
  uname -s
fi
echo '@@KERNEL'
uname -rm
echo '@@CPUMODEL'
grep -m1 -E '^(model name|Hardware|Processor|cpu model)' /proc/cpuinfo 2>/dev/null | cut -d: -f2-
echo '@@UPTIME'
cat /proc/uptime
echo '@@LOAD'
cat /proc/loadavg
echo '@@NPROC'
nproc 2>/dev/null || grep -c '^processor' /proc/cpuinfo
echo '@@STAT1'
head -n 1 /proc/stat
echo '@@NET1'
cat /proc/net/dev
sleep 1
echo '@@STAT2'
head -n 1 /proc/stat
echo '@@NET2'
cat /proc/net/dev
echo '@@MEM'
cat /proc/meminfo
echo '@@DISK'
df -Pk 2>/dev/null
echo '@@PS'
ps -eo pid,comm,%cpu,%mem --sort=-%cpu 2>/dev/null | head -n 9
echo '@@USERS'
who 2>/dev/null | wc -l
echo '@@END'
