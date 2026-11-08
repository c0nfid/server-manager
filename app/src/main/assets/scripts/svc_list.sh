# Службы systemd: загруженные юниты и состояние unit-файлов. Без root.
export LC_ALL=C
if ! command -v systemctl >/dev/null 2>&1 || [ ! -d /run/systemd/system ]; then
  echo '@@NOSYSTEMD'
  echo '@@END'
  exit 0
fi
echo '@@UNITS'
systemctl list-units --type=service --all --no-legend --no-pager --plain 2>/dev/null
echo '@@FILES'
systemctl list-unit-files --type=service --no-legend --no-pager 2>/dev/null
echo '@@END'
