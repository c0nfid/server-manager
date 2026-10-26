# Внешний IP и геолокация, определённые самим сервером.
if command -v curl >/dev/null 2>&1; then
  curl -fsS --max-time 8 https://ipinfo.io/json
elif command -v wget >/dev/null 2>&1; then
  wget -qO- -T 8 https://ipinfo.io/json
else
  echo "На сервере нет curl или wget" >&2
  exit 1
fi
