#!/system/bin/sh
# judge_save2.sh —— G2 结论落盘（F 步：判断输出走文件传递，白名单校验，模型原文不进 shell）
# 读取: judge/.judge_raw.txt（由工作流 e_raw 节点用文件工具原样写入）
# 校验: VERDICT ∈ 白名单，CONF ∈ 0..100；非法 -> REJECTED_OUTPUT，不产生副作用
# 落盘: judge/<YYYYMMDD>.jsonl（append-only, TSV 两列）+ eventd JUDGE + save_log.tsv
J=/sdcard/Download/Operit/judge
E=/sdcard/Download/Operit/events
RAW=$J/.judge_raw.txt
D=$(date +%Y%m%d)
NOW=$(date '+%F %T')
mkdir -p "$J" 2>/dev/null
if [ ! -s "$RAW" ]; then
  printf '%s\tREJECTED_OUTPUT\treason=NO_RAW_FILE\n' "$NOW" >> "$J/save_log.tsv"
  echo "verdict=REJECTED_OUTPUT conf=0 reason=NO_RAW_FILE"
  exit 0
fi
LINE=$(sed -n 's/.*VERDICT=//p' "$RAW" | tail -1 | head -1 | sed 's/","receivedAt.*$//; s/\\"/"/g' | cut -c1-1200)
VD=$(printf '%s' "$LINE" | sed -n 's/^\([A-Za-z_]*\).*/\1/p')
case "$VD" in
  ON_TRACK|DRIFT_RISK|STUCK|NO_TASK|INSUFFICIENT|OVERRUN) : ;;
  *) VD=REJECTED_OUTPUT ;;
esac
if [ "$VD" = "REJECTED_OUTPUT" ]; then
  printf '%s\tREJECTED_OUTPUT\treason=BAD_VERDICT\n' "$NOW" >> "$J/save_log.tsv"
  echo "verdict=REJECTED_OUTPUT conf=0 reason=BAD_VERDICT"
  exit 0
fi
CF=$(printf '%s' "$LINE" | sed -n 's/^[A-Za-z_]* *CONF=\([0-9]\{1,3\}\).*/\1/p')
[ -n "$CF" ] || CF=0
[ "$CF" -gt 100 ] 2>/dev/null && CF=100
WHY=$(printf '%s' "$LINE" | sed -n 's/.*WHY=//p' | sed 's/NEXT=.*//; s/EVIDENCE=.*//; s/RECOMMENDED_LEVEL=.*//' | tr -d '\r' | tr '\t' ' ' | cut -c1-200)
[ -n "$WHY" ] || WHY=NOT_CAPTURED
NXT=$(printf '%s' "$LINE" | sed -n 's/.*NEXT=//p' | sed 's/EVIDENCE=.*//; s/RECOMMENDED_LEVEL=.*//' | tr -d '\r' | tr '\t' ' ' | cut -c1-160)
EVID=$(printf '%s' "$LINE" | sed -n 's/.*EVIDENCE=\([A-Za-z0-9_,-]*\).*/\1/p' | cut -c1-120)
REC=$(printf '%s' "$LINE" | sed -n 's/.*RECOMMENDED_LEVEL=\(L[0-3]\).*/\1/p')
[ -n "$REC" ] || REC=$(printf '%s' "$LINE" | sed -n 's/.*LEVEL=\(L[0-3]\).*/\1/p')
V="$VD CONF=$CF WHY=$WHY NEXT=$NXT EVIDENCE=$EVID RECOMMENDED_LEVEL=$REC"
printf '%s\t%s\n' "$NOW" "$V" >> "$J/$D.jsonl"
SLOT=$(date +%Y%m%d-%H)-$(( $(date +%M | sed 's/^0*//;s/^$/0/') / 30 ))
sh "$E/eventd.sh" add JUDGE verdict="$VD" conf="$CF" src=g2_judge --dedup "judge-$SLOT-$VD" >/dev/null 2>&1
printf '%s\tSAVED\tverdict=%s\tconf=%s\n' "$NOW" "$VD" "$CF" >> "$J/save_log.tsv"
# 判断官只负责落盘；一切外向动作统一由 engine_tick.sh 编排（2026-09-18 整编）
echo "verdict=$VD conf=$CF"

# 引擎拍（2026-09-18 整编）：统一入口，替代原先逐个追加的 6 段
sh "$J/engine_tick.sh" >/dev/null 2>&1 &
