# Установка зависимостей portbind через пакетный менеджер. Root.
set -e
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -q
  apt-get install -y -q nftables libc-bin util-linux coreutils procps gawk
elif command -v dnf >/dev/null 2>&1; then
  dnf install -y nftables util-linux coreutils procps-ng gawk glibc-common
elif command -v yum >/dev/null 2>&1; then
  yum install -y nftables util-linux coreutils procps-ng gawk
elif command -v apk >/dev/null 2>&1; then
  apk add --no-cache bash nftables util-linux coreutils procps gawk musl-utils
elif command -v pacman >/dev/null 2>&1; then
  pacman -Sy --noconfirm --needed nftables util-linux coreutils procps-ng gawk
elif command -v zypper >/dev/null 2>&1; then
  zypper --non-interactive install nftables util-linux coreutils procps gawk
else
  echo "Не найден поддерживаемый пакетный менеджер (apt-get, dnf, yum, apk, pacman, zypper)" >&2
  exit 1
fi
echo "Зависимости установлены"
