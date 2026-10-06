#!/bin/sh
# 主控节拍：每 5 分钟由工作流调用一次，只做时间判断，不调用模型。
# 有事要办时输出 "NEED=go EVENT=<类型> ID=<窗口>" 并把详情写进 tick_last.txt；
# 没事时输出 "NEED=none"。工作流只在输出含 NEED=go 时叫醒主控。
# 只用 date +%s 和 sh 算术，Android sh 与终端里的 Ubuntu 都能跑；不依赖时区数据库。
#
# 用法：sh tick.sh [核心目录]
# 测试时可用环境变量 NOW_EPOCH 指定当前时间。

CORE=${1:-/sdcard/Download/Operit/core}
STATE="$CORE/state"
mkdir -p "$STATE" "$CORE/log"

# ---- 可调参数（北京时间，分钟） ----
PLAN_AT=480        # 08:00 早计划
SUMMARY_AT=1290    # 21:30 晚总结
NUDGE_AFTER=600    # 提问后 10 分钟没回应才追问
NUDGE_GAP=600      # 两次追问至少隔 10 分钟

NOW=${NOW_EPOCH:-$(date +%s)}
LOCAL=$((NOW + 28800))             # 北京时间 = UTC+8
MIN_OF_DAY=$(( (LOCAL / 60) % 1440 ))

# 由天数推出 年-月-日（不依赖 date -d 或时区文件）
days=$((LOCAL / 86400))
z=$((days + 719468)); era=$((z / 146097)); doe=$((z - era * 146097))
yoe=$(( (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 ))
y=$((yoe + era * 400)); doy=$((doe - (365 * yoe + yoe / 4 - yoe / 100)))
mp=$(( (5 * doy + 2) / 153 )); d=$((doy - (153 * mp + 2) / 5 + 1))
if [ "$mp" -lt 10 ]; then m=$((mp + 3)); else m=$((mp - 9)); fi
if [ "$m" -le 2 ]; then y=$((y + 1)); fi
TODAY=$(printf '%04d-%02d-%02d' "$y" "$m" "$d")
HHMM=$(printf '%02d:%02d' $((MIN_OF_DAY / 60)) $((MIN_OF_DAY % 60)))

FIRED="$STATE/fired_$TODAY.txt"
touch "$FIRED"
fired() { grep -qx "$1" "$FIRED"; }

to_min() { # "08:09" -> 489；容忍前导零
  h=${1%%:*}; mm=${1##*:}
  h=${h#0}; mm=${mm#0}
  echo $(( ${h:-0} * 60 + ${mm:-0} ))
}

json_num() { sed -n "s/.*\"$1\"[^0-9]*\([0-9][0-9]*\).*/\1/p" "$CORE/thread.json" 2>/dev/null | head -n 1; }
json_str() { sed -n "s/.*\"$1\" *: *\"\([^\"]*\)\".*/\1/p" "$CORE/thread.json" 2>/dev/null | head -n 1; }

emit() { # 类型 窗口ID 标题 开始 结束
  {
    echo "event=$1"
    echo "id=$2"
    echo "title=$3"
    echo "start=$4"
    echo "end=$5"
    echo "at=$TODAY $HHMM"
  } > "$CORE/tick_last.txt"
  echo "$TODAY $HHMM $1 $2" >> "$STATE/tick.log"
  echo "NEED=go EVENT=$1 ID=$2"
}

# 1. 早计划
if [ "$MIN_OF_DAY" -ge "$PLAN_AT" ] && [ "$MIN_OF_DAY" -lt "$SUMMARY_AT" ] && ! fired plan; then
  echo plan >> "$FIRED"; emit plan - "生成今天的计划" - -; exit 0
fi

# 2/3. 工作窗口（只认当天的 today_windows.txt）
WIN="$CORE/today_windows.txt"
ACTIVE_ID=""
if [ -f "$WIN" ] && grep -qx "date=$TODAY" "$WIN"; then
  # 先处理到点结束的窗口，再处理到点开始的窗口
  while IFS='|' read -r id st en title; do
    case "$id" in date=*|''|'#'*) continue ;; esac
    s=$(to_min "$st"); e=$(to_min "$en")
    if fired "start:$id" && ! fired "end:$id" && [ "$MIN_OF_DAY" -ge "$e" ]; then
      echo "end:$id" >> "$FIRED"; emit end "$id" "$title" "$st" "$en"; exit 0
    fi
  done < "$WIN"
  while IFS='|' read -r id st en title; do
    case "$id" in date=*|''|'#'*) continue ;; esac
    s=$(to_min "$st"); e=$(to_min "$en")
    if ! fired "start:$id" && [ "$MIN_OF_DAY" -ge "$s" ] && [ "$MIN_OF_DAY" -lt "$e" ]; then
      echo "start:$id" >> "$FIRED"; emit start "$id" "$title" "$st" "$en"; exit 0
    fi
    if fired "start:$id" && ! fired "end:$id"; then ACTIVE_ID=$id; fi
  done < "$WIN"
fi

# 4. 追问：只在进行中的窗口里，问了问题又一直没回应时
if [ -n "$ACTIVE_ID" ]; then
  waiting=$(json_num waiting_since); snooze=$(json_num snooze_until); status=$(json_str status)
  last=$(cat "$STATE/last_nudge" 2>/dev/null); last=${last:-0}
  case "$status" in paused|resting|idle) waiting=0 ;; esac
  if [ "${waiting:-0}" -gt 0 ] && [ $((NOW - waiting)) -ge "$NUDGE_AFTER" ] \
     && [ "$NOW" -ge "${snooze:-0}" ] && [ $((NOW - last)) -ge "$NUDGE_GAP" ]; then
    echo "$NOW" > "$STATE/last_nudge"
    emit nudge "$ACTIVE_ID" "回应超时，换个小问法" - -; exit 0
  fi
fi

# 5. 晚总结
if [ "$MIN_OF_DAY" -ge "$SUMMARY_AT" ] && ! fired summary; then
  echo summary >> "$FIRED"; emit summary - "写今天的总结" - -; exit 0
fi

echo "NEED=none"
