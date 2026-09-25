#!/system/bin/sh
# 只读导出：给"整体流程线"用。只写 00_AGENT_HANDOVER/ 下的一个导出文件，不改其他任何东西。
R=/sdcard/Download/Operit
D=$(date +%Y%m%d)
OUT=$R/00_AGENT_HANDOVER/flow_export_$D.txt
mkdir -p "$R/00_AGENT_HANDOVER"

# 打码：token/secret/key/Bearer 后面的值、sk- 开头的密钥
redact() {
  sed -E 's/(Bearer|bearer) +[[:alnum:]._+\/=-]{6,}/\1 <REDACTED>/g; s/(token|TOKEN|Token|secret|SECRET|Secret|api_key|API_KEY|apikey|apiKey|ApiKey|password|PASSWORD)([^[:alnum:]]{1,4})[[:alnum:]._+\/=-]{6,}/\1\2<REDACTED>/g; s/sk-[[:alnum:]_-]{8,}/sk-<REDACTED>/g'
}

# dump 路径 [只取末尾 N 行]
dump() {
  if [ -f "$1" ]; then
    echo "===== $1 ===== size=$(wc -c <"$1") mtime=$(date -r "$1" '+%F %T' 2>/dev/null)"
    if [ -n "$2" ]; then tail -n "$2" "$1"; else cat "$1"; fi | redact
  else
    echo "===== $1 ===== NOT_FOUND"
  fi
  echo
}

{
echo "##### FLOW_EXPORT $(date '+%F %T')"

echo "##### A. 目录结构（两层，不展开每日事件目录）"
find "$R" -maxdepth 2 -not -path "$R/events/20*/*" 2>/dev/null | sort
echo

echo "##### B. 全部 .sh 脚本"
find "$R" -maxdepth 4 -name '*.sh' -not -path '*00_AGENT_HANDOVER*' -not -path '*backup*' -not -path '*_bak*' 2>/dev/null | sort | while read -r f; do dump "$f"; done

echo "##### C. 规则和通道配置"
for f in drift/task_state.txt drift/channel.txt events/EXEC_RULES.tsv events/ACTIVE_RULES.md \
         events/UNLOCK_POLICY.tsv events/README.md judge/sched_chains.tsv p2/ent.list p2/protect.deny; do
  dump "$R/$f"
done
for f in drift/channel_health.tsv drift/outbox_unsent.tsv events/ACTION_LOG.tsv lock_freeze/freeze_queue.tsv; do
  dump "$R/$f" 40
done

echo "##### D. judge/ 和 drift/ 下最近 3 天改过的非脚本文件（每个取末尾 40 行）"
find "$R/judge" "$R/drift" -maxdepth 2 -type f -mtime -3 -not -name '*.sh' 2>/dev/null | sort | while read -r f; do dump "$f" 40; done

echo "##### E. 工作流盘上 JSON"
for id in b3b65845 8f675f75 4008f0e8 5d51a05b b078ed53 c9ae5f71; do
  set -- "$R"/workflow/"$id"*.json
  if [ -f "$1" ]; then for f in "$@"; do dump "$f"; done; else echo "===== $R/workflow/$id*.json ===== NOT_FOUND"; echo; fi
done

echo "##### F. 最近两天事件"
for d in $(ls -d "$R"/events/20* 2>/dev/null | tail -n 2); do dump "$d/digest.txt"; done
LAST=$(ls -d "$R"/events/20* 2>/dev/null | tail -n 1)
[ -n "$LAST" ] && dump "$LAST/events.jsonl" 150

echo "##### G. 用户进展 progress/"
ls -la "$R/progress" 2>&1
for f in $(ls "$R"/progress/*.jsonl 2>/dev/null | tail -n 3); do dump "$f"; done

echo "##### H. 执行日志里 G2 最近的记录"
ls -la "$R/workflow/_execution_logs" 2>&1 | tail -n 20
for f in $(ls -t "$R"/workflow/_execution_logs/* 2>/dev/null | head -n 60 | xargs grep -l b3b65845 2>/dev/null | head -n 3); do dump "$f" 80; done

echo "##### I. 谁在对用户说话：关键词和会话 ID 在脚本/工作流里的出现位置"
find "$R" -maxdepth 4 \( -name '*.sh' -o -name '*.json' -o -name 'channel*.txt' \) -not -path '*/events/20*' -not -path '*_execution_logs*' -not -path '*00_AGENT_HANDOVER*' 2>/dev/null \
  | xargs grep -n -E 'chat_with_agent|speak|tts|TTS|VOICE|show_floating|sendNotification|startService|简历|投递|ded96924|28f6fbb9|f0953479' 2>/dev/null \
  | cut -c1-240 | redact

echo "##### END"
} >"$OUT" 2>&1

echo "OUT=$OUT"
echo "SIZE=$(wc -c <"$OUT")"
echo "FILES=$(grep -c '^===== ' "$OUT")"
echo "NOT_FOUND=$(grep -c ' NOT_FOUND$' "$OUT")"
# END_OF_FLOW_EXPORT
