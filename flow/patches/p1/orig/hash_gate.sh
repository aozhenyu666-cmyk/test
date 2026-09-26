#!/system/bin/sh
# hash_gate.sh —— D 步：evidence_hash 闸门（相同证据不重复叫判断官）
# S2(2026-09-19) 哈希输入面收窄：只对 gen_pack.sh 打出的 == HASH-BEGIN == / == HASH-END == 之间区域
#   做剥时间戳 + 去表头 + sha256 前16；区域外的易变噪声（包头时间、今日事件计数、
#   最近判断记录、§10 游标标题行）不再影响"同证据"判定。
# 三道保险：
#   1) 无 pack 文件或无 HASH 标记 → 回退整包哈希（保持旧行为，只放宽不收紧）
#   2) 标记区为空或过短(<40字节) → 无法判定变化，fail-open 放行 GATE=JUDGE，且游标不推进
#   3) 哈希计算失败 → 沿用现有 fail-open：GATE=JUDGE reason=HASH_FAIL，游标不推进
# 比较语义不变：不同 → GATE=JUDGE（放行并推进游标）；相同 → GATE=SKIP（只记事件，--dedup 防重复行）
J=/sdcard/Download/Operit/judge
E=/sdcard/Download/Operit/events
PACK=$J/.pack_last.txt
STORE=$J/.last_evidence_hash
STRIP='s/[0-9]\{4\}-[0-9][0-9]-[0-9][0-9][ T][0-9][0-9]:[0-9][0-9]:[0-9][0-9]//g'
[ -f "$PACK" ] || { echo "GATE=JUDGE hash=NA mode=whole reason=NO_PACK"; exit 0; }
B=$(grep -n '^== HASH-BEGIN ==$' "$PACK" | head -1 | cut -d: -f1)
F=$(grep -n '^== HASH-END ==$' "$PACK" | head -1 | cut -d: -f1)
MODE=whole
if [ -n "$B" ] && [ -n "$F" ] && [ "$F" -gt "$B" ] 2>/dev/null; then
  MODE=region
  BODY=$(sed -n "$((B + 1)),$((F - 1))p" "$PACK" | sed "$STRIP" | grep -v '^-- ')
else
  BODY=$(sed "$STRIP" "$PACK" | grep -v '^== 证据包')
fi
if [ "$MODE" = "region" ] && [ "$(printf '%s' "$BODY" | wc -c)" -lt 40 ]; then
  echo "GATE=JUDGE hash=NA mode=region reason=EMPTY_REGION fail_open=1"
  exit 0
fi
H=$(printf '%s' "$BODY" | sha256sum | cut -c1-16)
[ -n "$H" ] || { echo "GATE=JUDGE hash=NA mode=$MODE reason=HASH_FAIL"; exit 0; }
PREV=$(cat "$STORE" 2>/dev/null | head -1)
if [ -n "$PREV" ] && [ "$H" = "$PREV" ]; then
  sh "$E/eventd.sh" add JUDGE_SKIP_SAME_EVIDENCE hash="$H" src=g2_gate --dedup "gsk-$H" >/dev/null 2>&1
  echo "GATE=SKIP hash=$H mode=$MODE"
  exit 0
fi
printf '%s\n' "$H" > "$STORE"
echo "GATE=JUDGE hash=$H mode=$MODE"
exit 0
