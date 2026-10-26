# Резервные копии portbind. Root. ACTION=list|show|restore|prune, NAME, KEEP.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"
umask 077
S=${PORTBIND_STATE_DIR:-/var/lib/portbind}
B="$S/backups"
case "$ACTION" in
  list)
    [ -d "$B" ] || exit 0
    for d in "$B"/*/; do
      [ -d "$d" ] || continue
      n=$(basename "$d")
      c=0
      if [ -f "$d/rules.tsv" ]; then c=$(grep -c . "$d/rules.tsv" || true); fi
      s=$(du -sk "$d" 2>/dev/null | cut -f1)
      printf '%s\t%s\t%s\n' "$n" "${c:-0}" "${s:-0}"
    done
    ;;
  show)
    cat "$B/$NAME/rules.tsv"
    ;;
  restore)
    [ -f "$B/$NAME/rules.tsv" ] || { echo "Копия не найдена: $NAME" >&2; exit 1; }
    flock -w 30 "$S/lock" sh -c 'cp -- "$1" "$2.restore" && chmod 600 "$2.restore" && mv -f -- "$2.restore" "$2"' sh "$B/$NAME/rules.tsv" "$S/rules.tsv" || exit 1
    echo "rules.tsv восстановлен из $NAME, применяю…"
    /usr/local/sbin/portbind apply
    ;;
  prune)
    [ -d "$B" ] || { echo "Копий нет"; exit 0; }
    ls -1 "$B" | sort -r | tail -n +$((KEEP + 1)) | while read -r d; do
      [ -n "$d" ] && rm -rf -- "${B:?}/$d"
    done
    echo "Оставлено копий: $(ls -1 "$B" | wc -l)"
    ;;
  *)
    echo "Неизвестное действие: $ACTION" >&2
    exit 1
    ;;
esac
