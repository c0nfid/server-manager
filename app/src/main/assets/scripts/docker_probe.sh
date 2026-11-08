# Проверка Docker и агента srvmgr-docker. Без root.
export LC_ALL=C
A=/usr/local/bin/srvmgr-docker
if [ -x "$A" ]; then
  "$A" info
  exit 0
fi
echo '@@NOAGENT'
echo '@@DOCKER'
if command -v docker >/dev/null 2>&1; then echo yes; else echo no; fi
echo '@@USER'
id -un
echo '@@END'
