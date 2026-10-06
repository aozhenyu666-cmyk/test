#!/bin/sh
# 模拟一整天跑 tick.sh：sh test_tick.sh   （可用 dash/bash/busybox sh）
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
fail=0
# 2026-10-07 00:00 北京时间 = 2026-10-06 16:00 UTC
DAY0=1791302400
at() { NOW_EPOCH=$((DAY0 + $1 * 3600 + $2 * 60)) sh "$HERE/tick.sh" "$T"; }
expect() { # 期望 实际 说明
  case "$2" in *"$1"*) echo "ok   $3" ;; *) echo "FAIL $3: 期望含 [$1] 实际 [$2]"; fail=1 ;; esac
}

expect "NEED=none" "$(at 7 55)" "早于 08:00 无事"
expect "EVENT=plan" "$(at 8 0)" "08:00 早计划"
expect "NEED=none" "$(at 8 5)" "早计划只触发一次"

cat > "$T/today_windows.txt" <<EOF
date=2026-10-06
w1|09:00|10:00|昨天的窗口
EOF
expect "NEED=none" "$(at 9 0)" "日期不是今天的窗口表不触发"

cat > "$T/today_windows.txt" <<EOF
date=2026-10-07
# 注释行
w1|09:00|10:00|写简历第二段
w2|10:00|11:50|整理面试题
EOF
expect "EVENT=start ID=w1" "$(at 9 0)" "09:00 开始 w1"
grep -q "title=写简历第二段" "$T/tick_last.txt" && echo "ok   tick_last 写入标题" || { echo "FAIL tick_last"; fail=1; }
expect "NEED=none" "$(at 9 5)" "没在等回答时不追问"

w=$((DAY0 + 9 * 3600 + 10 * 60))
printf '{\n  "status": "thinking",\n  "waiting_since": %s,\n  "snooze_until": 0\n}\n' "$w" > "$T/thread.json"
expect "NEED=none" "$(at 9 15)" "提问 5 分钟内不追问"
expect "EVENT=nudge ID=w1" "$(at 9 20)" "提问 10 分钟没回应就追问"
expect "NEED=none" "$(at 9 25)" "两次追问至少隔 10 分钟"

s=$((DAY0 + 9 * 3600 + 50 * 60))
printf '{\n  "status": "thinking",\n  "waiting_since": %s,\n  "snooze_until": %s\n}\n' "$w" "$s" > "$T/thread.json"
expect "NEED=none" "$(at 9 40)" "用户说了状态（snooze）期间不追问"
printf '{\n  "status": "resting",\n  "waiting_since": %s,\n  "snooze_until": 0\n}\n' "$w" > "$T/thread.json"
expect "NEED=none" "$(at 9 45)" "休息状态不追问"

expect "EVENT=end ID=w1" "$(at 10 0)" "10:00 先结束 w1"
expect "EVENT=start ID=w2" "$(at 10 5)" "下一拍再开始 w2"
expect "EVENT=end ID=w2" "$(at 11 50)" "11:50 结束 w2"
expect "NEED=none" "$(at 12 0)" "窗口都结束后无事"

cat > "$T/today_windows.txt" <<EOF
date=2026-10-07
w3|14:08|14:09|前导零与错过的窗口
EOF
expect "NEED=none" "$(at 14 30)" "已错过的窗口不再开场"
cat > "$T/today_windows.txt" <<EOF
date=2026-10-07
w4|08:09|23:00|前导零 08:09
EOF
expect "EVENT=start ID=w4" "$(at 15 0)" "能解析 08:09 这类前导零"

expect "EVENT=end ID=w4" "$(at 23 0)" "w4 结束"
expect "EVENT=summary" "$(at 23 5)" "21:30 之后补发晚总结"
expect "NEED=none" "$(at 23 10)" "晚总结只触发一次"

rm -rf "$T"
[ "$fail" = 0 ] && echo "全部通过" || { echo "有失败"; exit 1; }
