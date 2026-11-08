# Сигнал процессу. PID, SIG=TERM|KILL|HUP|INT|STOP|CONT, EXPECT — ожидаемая командная строка (защита от повторного PID).
export LC_ALL=C
case "$PID" in ''|*[!0-9]*) echo "Неверный PID" >&2; exit 1 ;; esac
case "$SIG" in TERM|KILL|HUP|INT|STOP|CONT) ;; *) echo "Неверный сигнал" >&2; exit 1 ;; esac
CUR=$(ps -ww -o args= -p "$PID" 2>/dev/null | sed 's/[[:space:]]*$//')
if [ -z "$CUR" ]; then echo "Процесс $PID уже завершён"; exit 0; fi
if [ "$CUR" != "$EXPECT" ]; then echo "PID $PID теперь занят другим процессом — обновите список" >&2; exit 1; fi
kill -s "$SIG" "$PID" || exit 1
case "$SIG" in
  TERM|KILL|INT)
    i=0
    while [ $i -lt 8 ] && kill -0 "$PID" 2>/dev/null; do sleep 0.5; i=$((i + 1)); done
    if kill -0 "$PID" 2>/dev/null; then echo "Сигнал $SIG отправлен, процесс $PID ещё работает"; else echo "Процесс $PID завершён"; fi
    ;;
  *) echo "Сигнал $SIG отправлен процессу $PID" ;;
esac
