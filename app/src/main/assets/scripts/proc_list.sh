# Список процессов (до 400, по убыванию CPU). Обычно без root; с root — если /proc скрыт (hidepid).
export LC_ALL=C
echo "@@ME"
id -un
echo "@@PS"
ps -ww -eo pid,ppid,user:32,pcpu,pmem,rss,etimes,stat,args --sort=-pcpu --no-headers 2>/dev/null | head -n 400 \
  || echo "ERROR: нужен ps из procps (procps-ng)"
echo "@@END"
