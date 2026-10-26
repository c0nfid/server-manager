#!/usr/bin/env bash
# Linux / Bash 4.4+ / nftables. Only the ip portbind table is managed.
set -Eeuo pipefail
export LC_ALL=C
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
STATE=${PORTBIND_STATE_DIR:-/var/lib/portbind}
TABLE=portbind
DB=$STATE/rules.tsv
WORK= BACKUP= CHANGED=0 COMMITTED=0

error() { printf 'Ошибка: %s\n' "$*" >&2; }
die() { error "$*"; exit 1; }
usage() {
    cat <<'EOF'
portbind — перенаправление входящих TCP/UDP соединений (IPv4).

  portbind.sh                         меню из 5 пунктов
  portbind.sh add IN_PORT HOST OUT_PORT [tcp|udp|both] [BIND_HOST]
  portbind.sh list [--tsv]            таблица или машинный формат
  portbind.sh refresh [DOMAIN]        обновить все или конкретный домен
  portbind.sh delete IN_PORT
  portbind.sh reset --yes             удалить все правила portbind
  portbind.sh apply                   применить сохраненные адреса (после reboot)
  portbind.sh help

HOST: IP или домен выходной ноды. BIND_HOST: IP/домен входного адреса,
либо '*' (по умолчанию: все локальные IPv4-адреса).
Протокол по умолчанию: both. Один входной порт — одна запись.
Без GUI; для работы нужны root, nft, getent, flock, timeout, sysctl.
EOF
}
ipv4() {
    local v=$1 a n
    local -a oct
    [[ $v =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
    IFS=. read -r -a oct <<< "$v"
    for a in "${oct[@]}"; do
        [[ ${#a} -le 3 && ( $a == 0 || $a != 0* ) ]] || return 1
        n=$((10#$a)); ((n <= 255)) || return 1
    done
}
host() {
    local v=${1,,} label
    v=${v%.}
    if ipv4 "$v"; then printf '%s\n' "$v"; return; fi
    [[ $v != *:* && $v != *[!0-9.]* ]] && return 1
    [[ ${#v} -ge 1 && ${#v} -le 253 && $v != *..* ]] || return 1
    local -a labels
    IFS=. read -r -a labels <<< "$v"
    for label in "${labels[@]}"; do
        [[ ${#label} -le 63 && $label =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]] || return 1
    done
    printf '%s\n' "$v"
}
port() {
    [[ $1 =~ ^[0-9]{1,5}$ ]] || return 1
    local n=$((10#$1))
    ((n >= 1 && n <= 65535)) || return 1
    printf '%s\n' "$n"
}
resolve() {
    local name=$1 old=${2:-} output ip rest
    if [[ $name == '*' ]]; then printf '*\n'; return; fi
    if ipv4 "$name"; then printf '%s\n' "$name"; return; fi
    output=$(timeout 15 getent ahostsv4 "$name") || return 1
    local -a found=()
    while read -r ip rest; do
        ipv4 "$ip" && found+=("$ip")
    done <<< "$output"
    ((${#found[@]})) || return 1
    # Keep an existing answer while DNS still advertises it (avoid round-robin churn).
    for ip in "${found[@]}"; do
        if [[ -n $old && $ip == "$old" ]]; then printf '%s\n' "$ip"; return; fi
    done
    printf '%s\n' "${found[@]}" | sort -Vu | awk 'NR == 1 {print}'
}
validate_db() {
    local file=$1 p proto bh bi dh di dp extra
    local -A seen=()
    while IFS=$'\t' read -r p proto bh bi dh di dp extra; do
        [[ -n $p ]] || die 'Пустая строка в rules.tsv.'
        [[ -z $extra && -n $dp ]] || die 'Неверный формат rules.tsv.'
        [[ $(port "$p") == "$p" && $(port "$dp") == "$dp" ]] || die 'Неверный порт в rules.tsv.'
        [[ $proto == tcp || $proto == udp || $proto == both ]] || die 'Неверный протокол в rules.tsv.'
        [[ -z ${seen[$p]+x} ]] || die "Повторный входной порт: $p"
        seen[$p]=1
        [[ $(host "$dh") == "$dh" ]] && ipv4 "$di" || die 'Неверная выходная нода в rules.tsv.'
        if [[ $bh == '*' ]]; then
            [[ $bi == '*' ]] || die 'Неверный входной адрес в rules.tsv.'
        else
            [[ $(host "$bh") == "$bh" ]] && ipv4 "$bi" || die 'Неверный входной адрес в rules.tsv.'
        fi
    done < "$file"
}
table_exists() {
    local listing line
    listing=$(nft list tables) || return 2
    while IFS= read -r line; do
        [[ $line == "table ip $TABLE" ]] && return 0
    done <<< "$listing"
    return 1
}
backup() {
    BACKUP=$(mktemp -d "$STATE/backups/$(date -u +%Y%m%dT%H%M%SZ).XXXXXX")
    cp -- "$DB" "$BACKUP/rules.tsv"
    nft list ruleset > "$BACKUP/full-ruleset.nft"
    local rc=0
    table_exists || rc=$?
    ((rc != 2)) || die 'Не удалось прочитать таблицы nftables.'
    if ((rc == 0)); then nft list table ip "$TABLE" > "$BACKUP/table.nft";
    else : > "$BACKUP/table.nft"; fi
    sysctl -n net.ipv4.ip_forward > "$BACKUP/ip_forward"
    printf 'Резервная копия: %s\n' "$BACKUP" >&2
}
render() {
    local file=$1 output=$2 p proto bh bi dh di dp pr match orig
    {
        [[ ! -s $BACKUP/table.nft ]] || printf 'delete table ip %s\n' "$TABLE"
        printf 'table ip %s {\n' "$TABLE"
        printf ' chain prerouting {\n  type nat hook prerouting priority dstnat; policy accept;\n'
        while IFS=$'\t' read -r p proto bh bi dh di dp; do
            match='fib daddr type local'
            [[ $bi == '*' ]] || match="ip daddr $bi"
            for pr in tcp udp; do
                [[ $proto == both || $proto == "$pr" ]] || continue
                printf '  %s %s dport %s counter dnat to %s:%s\n' "$match" "$pr" "$p" "$di" "$dp"
            done
        done < "$file"
        printf ' }\n chain postrouting {\n  type nat hook postrouting priority srcnat; policy accept;\n'
        while IFS=$'\t' read -r p proto bh bi dh di dp; do
            orig=''
            [[ $bi == '*' ]] || orig="ct original ip daddr $bi "
            for pr in tcp udp; do
                [[ $proto == both || $proto == "$pr" ]] || continue
                # Establish the transport protocol before parsing a conntrack port.
                # Older nft versions leave proto-dst untyped without this context.
                printf '  ct status dnat ip daddr %s %s dport %s %sct original proto-dst %s counter masquerade\n' "$di" "$pr" "$dp" "$orig" "$p"
            done
        done < "$file"
        printf ' }\n}\n'
    } > "$output"
}
rollback() {
    local rc=0 status=0
    { 
        table_exists || status=$?
        if ((status == 0)); then printf 'delete table ip %s\n' "$TABLE"; fi
        cat -- "$BACKUP/table.nft"
    } > "$WORK/rollback.nft"
    ((status != 2)) || return 1
    if [[ -s $WORK/rollback.nft ]]; then nft -f "$WORK/rollback.nft" || rc=1; fi
    sysctl -q -w "net.ipv4.ip_forward=$(cat "$BACKUP/ip_forward")" || rc=1
    cp -- "$BACKUP/rules.tsv" "$WORK/old.tsv" && mv -f -- "$WORK/old.tsv" "$DB" || rc=1
    return "$rc"
}
cleanup() {
    local rc=$?
    trap - EXIT INT TERM HUP
    if ((CHANGED && !COMMITTED)); then
        error 'Изменение не завершено; восстанавливаю предыдущую таблицу.'
        if ! rollback; then error "Откат не удался. Резервная копия: $BACKUP"; rc=1; fi
    fi
    [[ -z $WORK ]] || rm -rf -- "$WORK"
    exit "$rc"
}
commit() {
    local candidate=$1
    validate_db "$candidate"
    backup
    render "$candidate" "$WORK/new.nft"
    nft -c -f "$WORK/new.nft" || die 'Проверка nftables не пройдена; правила не изменены.'
    CHANGED=1
    if [[ -s $candidate ]]; then sysctl -q -w net.ipv4.ip_forward=1; fi
    # The table replacement is one atomic nftables transaction.
    nft -f "$WORK/new.nft"
    mv -f -- "$candidate" "$DB"
    COMMITTED=1
    printf 'Правила сохранены и применены.\n'
}
add_rule() {
    (($# >= 3 && $# <= 5)) || die 'add: IN_PORT HOST OUT_PORT [tcp|udp|both] [BIND_HOST]'
    local p dh dp proto=${4:-both} bh=${5:-*} bi di
    p=$(port "$1") || die 'Входной порт: 1–65535.'
    dh=$(host "$2") || die 'Выходная нода: IPv4 или домен (IDN в punycode).'
    dp=$(port "$3") || die 'Выходной порт: 1–65535.'
    proto=${proto,,}
    [[ $proto == tcp || $proto == udp || $proto == both ]] || die 'Протокол: tcp, udp или both.'
    awk -F '\t' -v p="$p" '$1 == p {found=1} END {exit !found}' "$DB" && die "Порт $p уже занят записью. Сначала удалите ее."
    [[ $bh == '*' ]] || bh=$(host "$bh") || die 'Входной адрес: *, IPv4 или домен.'
    bi=$(resolve "$bh") || die "Не удалось определить IPv4: $bh"
    di=$(resolve "$dh") || die "Не удалось определить IPv4: $dh"
    cp -- "$DB" "$WORK/candidate.tsv"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$p" "$proto" "$bh" "$bi" "$dh" "$di" "$dp" >> "$WORK/candidate.tsv"
    sort -t $'\t' -k1,1n "$WORK/candidate.tsv" > "$WORK/sorted.tsv"
    commit "$WORK/sorted.tsv"
}
list_rules() {
    # ASCII borders with dynamically sized columns remain readable over SSH.
    awk -F '\t' '
      BEGIN {n=split("IN PORT|PROTO|BIND HOST|BIND IPv4|OUT HOST|OUT IPv4|OUT PORT",h,"|");
             for(i=1;i<=n;i++) w[i]=length(h[i])}
      {for(i=1;i<=n;i++) {v[NR,i]=$i; if(length($i)>w[i])w[i]=length($i)}; rows=NR}
      function bar( i,j) {printf "+"; for(i=1;i<=n;i++){for(j=0;j<w[i]+2;j++)printf "-"; printf "+"};print ""}
      END {bar();printf "|";for(i=1;i<=n;i++)printf " %-*s |",w[i],h[i];print "";bar();
           for(r=1;r<=rows;r++){printf "|";for(i=1;i<=n;i++)printf " %-*s |",w[i],v[r,i];print ""}
           bar();if(!rows)print "Нет перенаправлений."}
    ' "$DB"
    printf 'Примененная таблица: nft list table ip %s\n' "$TABLE"
}
refresh_rules() {
    (($# <= 1)) || die 'refresh: можно передать один домен.'
    local filter=${1:-} p proto bh bi dh di dp nb nd changed=0 failed=0 found=0
    if [[ -n $filter ]]; then
        filter=$(host "$filter") || die 'Неверный домен.'
        ipv4 "$filter" && die 'refresh принимает домен, а не IP.'
    fi
    : > "$WORK/candidate.tsv"
    local -A cache=() bad=()
    local name old resolved
    while IFS=$'\t' read -r p proto bh bi dh di dp; do
        nb=$bi; nd=$di
        for name in "$bh" "$dh"; do
            [[ $name != '*' ]] && ! ipv4 "$name" || continue
            [[ -z $filter || $name == "$filter" ]] || continue
            found=1
            if [[ -z ${cache[$name]+x} && -z ${bad[$name]+x} ]]; then
                old=$di; [[ $name != "$bh" ]] || old=$bi
                if resolved=$(resolve "$name" "$old"); then cache[$name]=$resolved;
                else bad[$name]=1; error "DNS $name: сохраняю прежний IP."; fi
            fi
            if [[ -n ${bad[$name]+x} ]]; then failed=1; continue; fi
            [[ $name != "$bh" ]] || nb=${cache[$name]}
            [[ $name != "$dh" ]] || nd=${cache[$name]}
        done
        [[ $nb == "$bi" && $nd == "$di" ]] || changed=1
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$p" "$proto" "$bh" "$nb" "$dh" "$nd" "$dp" >> "$WORK/candidate.tsv"
    done < "$DB"
    [[ -z $filter || $found == 1 ]] || die "Домен $filter отсутствует в записях."
    if ((changed)); then commit "$WORK/candidate.tsv";
    else printf 'Адреса не изменились.\n'; fi
    # Partial DNS failure is visible to cron; successfully resolved updates are retained.
    ((failed == 0)) || return 2
}
delete_rule() {
    (($# == 1)) || die 'delete: укажите входной порт.'
    local p
    p=$(port "$1") || die 'Неверный входной порт.'
    awk -F '\t' -v p="$p" '$1 == p {found=1} END {exit !found}' "$DB" || die "Порт $p отсутствует."
    awk -F '\t' -v p="$p" '$1 != p' "$DB" > "$WORK/candidate.tsv"
    commit "$WORK/candidate.tsv"
}
dispatch() (
    # Separate process/lock per operation: leaving the menu open does not block cron.
    local command=$1; shift
    exec 9> "$STATE/lock"
    flock -w 30 9 || die 'Другой процесс занят; повторите позже.'
    WORK=$(mktemp -d "$STATE/.work.XXXXXX")
    trap cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM HUP
    [[ -e $DB ]] || : > "$DB"
    validate_db "$DB"
    case $command in
        add) add_rule "$@" ;;
        list)
            if (($# == 0)); then list_rules;
            elif [[ $# == 1 && $1 == --tsv ]]; then cat -- "$DB";
            else die 'list: допустим только --tsv.'; fi
            ;;
        refresh) refresh_rules "$@" ;;
        delete) delete_rule "$@" ;;
        reset) [[ $# == 1 && $1 == --yes ]] || die 'Для сброса: reset --yes'; : > "$WORK/candidate.tsv"; commit "$WORK/candidate.tsv" ;;
        apply) (($# == 0)) || die 'apply без аргументов.'; cp -- "$DB" "$WORK/candidate.tsv"; commit "$WORK/candidate.tsv" ;;
        *) die "Неизвестная команда: $command (см. help)" ;;
    esac
)
menu() {
    local choice p dh dp proto bh domain action answer
    while :; do
        printf '\n1. Бинд порта\n2. Просмотр перенаправлений\n3. Обновление IP доменов\n4. Удаление записей\n5. Выход\n'
        read -r -p 'Выбор: ' choice || return 0
        case $choice in
            1)
                read -r -p 'Входной IP/домен [* = все локальные IPv4]: ' bh || return 0
                read -r -p 'Входной порт: ' p || return 0
                read -r -p 'IP/домен выходной ноды: ' dh || return 0
                read -r -p 'Выходной порт: ' dp || return 0
                read -r -p 'Протокол: tcp / udp / both [both]: ' proto || return 0
                run_menu add "$p" "$dh" "$dp" "${proto:-both}" "${bh:-*}"
                ;;
            2) run_menu list ;;
            3)
                read -r -p 'Домен [Enter = обновить все]: ' domain || return 0
                if [[ -n $domain ]]; then run_menu refresh "$domain"; else run_menu refresh; fi
                ;;
            4)
                printf '1. Удалить по входному порту\n2. Полный сброс правил portbind\n'
                read -r -p 'Выбор: ' action || return 0
                case $action in
                    1) read -r -p 'Входной порт: ' p || return 0; run_menu delete "$p" ;;
                    2) read -r -p 'Удалить ВСЕ правила portbind? Введите YES: ' answer || return 0
                       [[ $answer != YES ]] || run_menu reset --yes ;;
                    *) error 'Неизвестный пункт.' ;;
                esac
                ;;
            5) return 0 ;;
            *) error 'Выберите пункт 1–5.' ;;
        esac
    done
}
run_menu() {
    # A child shell preserves errexit even though its status is tested here.
    local rc=0
    bash "$0" "$@" || rc=$?
    ((rc == 0)) || printf 'Команда завершилась с кодом %s.\n' "$rc" >&2
    return 0
}
main() {
    case ${1:-} in
        help|-h|--help) usage; return 0 ;;
        --version) printf 'portbind 1.0.1\n'; return 0 ;;
    esac
    ((BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4))) || die 'Нужен Bash 4.4+.'
    ((EUID == 0)) || die 'Запустите через sudo.'
    local cmd path
    for cmd in nft getent flock timeout sysctl awk sort mktemp cp mv date stat; do
        command -v "$cmd" >/dev/null || die "Не найдена утилита: $cmd"
    done
    [[ $STATE == /* && $STATE != / ]] || die 'PORTBIND_STATE_DIR должен быть абсолютным каталогом.'
    mkdir -p -- "$STATE/backups"
    for path in "$STATE" "$STATE/backups"; do
        [[ ! -L $path && $(stat -c %u "$path") == 0 ]] || die "Каталог должен принадлежать root: $path"
        [[ $(stat -c %a "$path") == 700 ]] || die "Установите chmod 700 $path"
    done
    for path in "$DB" "$STATE/lock"; do
        [[ ! -L $path ]] || die "Не допускается символическая ссылка: $path"
        if [[ -e $path ]]; then
            [[ -f $path && $(stat -c %u "$path") == 0 ]] || die "Неверный файл: $path"
            [[ $(stat -c %a "$path") == 600 ]] || die "Установите chmod 600 $path"
        fi
    done
    if (($#)); then dispatch "$@";
    else [[ -t 0 ]] || die 'Для запуска без терминала укажите команду (см. help).'; menu; fi
}
main "$@"
