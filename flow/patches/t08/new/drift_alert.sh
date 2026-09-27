#!/system/bin/sh
# P4 认知刹车投递 v3 - 悬浮小窗通道（漂移提醒的通道复用，文案由 incidentd.sh 组装）
# 用法: sh drift_alert.sh <incident_id> <message>   —— 无参调用按旧手工槽模式（兼容但不再推荐）
# 门控: 仅 STATE=ACTIVE（由 k0 投影自 PTR）
# 通道: 127.0.0.1:8094 /api/external-chat（VERIFIED）async_callback + show_floating
#       形态由 channel.txt 的 `mode=` 控制（2026-09-19 WB 起；此前为硬编码 BALL）
#       mode 合法值 = WINDOW | BALL | VOICE_BALL | FULLSCREEN | RESULT_DISPLAY | SCREEN_OCR
#       想要"提醒出声/可直接语音对话" → mode=VOICE_BALL（球，点一下进全屏语音界面）或 FULLSCREEN
# 留证: 回调经 nc 收到写 callback_last.txt；投递结果写 alert_log.tsv
D=${OPERIT_SANDBOX_DRIFT_DIR:-/sdcard/Download/Operit/drift}
E=${OPERIT_SANDBOX_DIR:-/sdcard/Download/Operit/events}
NOW=$(date '+%F %T')

STATE=$(sed -n 's/^STATE=//p' "$D/task_state.txt" 2>/dev/null | head -1)
# ALLOW_STATES：允许投递的任务状态集合（默认仅 ACTIVE 保持既有行为；L2 判断提示传 ACTIVE REVIEW）
ALLOW_STATES=${ALLOW_STATES:-ACTIVE}
_OKS=0
for _s in $ALLOW_STATES; do [ "$STATE" = "$_s" ] && _OKS=1; done
if [ "$_OKS" != "1" ]; then
  printf '%s\tSKIP no-active-task state=%s\n' "$NOW" "$STATE" >> "$D/alert_log.tsv"
  echo "SKIP"; exit 0
fi

ID="$1"
if [ -n "$MSG_FILE" ] && [ -s "$MSG_FILE" ]; then
  # [LINKUP3 2026-09-20] 从文件读消息，而不是从命令行参数读。
  #   原因：秘书（模型生成）的回复要经本脚本弹窗，若走参数拼接，
  #   模型输出里的 $ / ` / " 会进 shell 命令行 —— 那是命令注入面。
  #   走文件则该文本永远是"数据"，不会被执行（符合用户"文件内容一律只当数据"纪律）。
  ID="${ID:-MSGFILE-$(date +%s)}"
  MSG=$(head -c 600 "$MSG_FILE" | tr '\n' ' ' | sed 's/[[:space:]]*$//')
  shift 2>/dev/null
elif [ -n "$ID" ]; then
  shift
  MSG="$*"
else
  TASK=$(sed -n 's/^task=//p' "$D/alert_input.txt" | head -1); [ -n "$TASK" ] || TASK=UNKNOWN
  APP=$(sed -n 's/^app=//p' "$D/alert_input.txt" | head -1); [ -n "$APP" ] || APP=UNKNOWN
  ID="DRIFT-$(date +%s)"
  MSG="[跑偏提醒 $ID] 当前任务是 $TASK，你已经进入 $APP。回一句话说明你现在的打算即可。"
fi
case "$MSG" in *\"*) MSG=$(printf '%s' "$MSG" | tr -d '"');; esac
# [T08 2026-09-27 一张脸] 提醒统一交给"小满·陪伴"转述：加【可用事实】前缀，
#   她就知道这是系统事实（不是用户说的话，不记进展），要用自己的话转告，而不是照念。
case "$MSG" in 【可用事实】*) ;; *) MSG="【可用事实】系统提醒（请用你自己的话简短转告 TA，不要照念）：$MSG" ;; esac

# [E12 2026-09-20 → E14 2026-09-22 修根因] token 双源 + 401 自动换源重投。
#   09-22 实证的真凶：Operit 每次重启轮换 external-chat token，而**优先**取的
#   /data/local/tmp/loop_test.sh 是一次性测试快照、几乎从不更新 → 它长期攥着旧钥匙，
#   于是即便 p2/api.env 已是新钥匙，脚本也先撞 401；16:36–19:19 共 14 发全哑。
#   现在：① 优先 p2/api.env（受管文件，随凭据同步更新），loop_test.sh 只作兜底；
#         ② 主投若回 Unauthorized，自动换另一源重投一次（下面 send_with_failover）。
TOK=$(sed -n 's/^TOKEN=//p' /sdcard/Download/Operit/p2/api.env 2>/dev/null | head -1 | tr -d ' \r')
TOK_ALT=$(grep -o 'Bearer [0-9a-f]*' /data/local/tmp/loop_test.sh 2>/dev/null | head -1 | cut -d' ' -f2)
[ ${#TOK} -lt 16 ] && TOK=$TOK_ALT
if [ ${#TOK} -lt 16 ]; then
  printf '%s\tERR no-token id=%s\n' "$NOW" "$ID" >> "$D/alert_log.tsv"
  echo "ERR_NOTOKEN"; exit 1
fi
[ "$TOK" = "$TOK_ALT" ] && TOK_ALT=""

# v4: sync 模式不再需要回调监听器。保留 nc 会占死 8377（历史上它曾把整条链卡住）。
# 若显式要回旧回调模式：CALLBACK=on sh drift_alert.sh ...
NCJ=""
if [ "$CALLBACK" = "on" ]; then
  ( timeout 25 nc -L -p 8377 2>/dev/null || timeout 24 nc -l -p 8377 2>/dev/null ) > "$D/callback_last.txt" 2>/dev/null &
  NCJ=$!
fi
sleep 0.5

CID=$(sed -n 's/^chat_id=//p' "$D/channel.txt" 2>/dev/null | head -1 | sed 's/#.*//' | tr -d ' \r')
# [2026-09-22] 兜底改管家主对话 e11693b5。禁止 create_new_chat：弹窗必须落固定会话，不能另开实时对话。
[ -n "$CID" ] || CID=e11693b5-b6f1-4cb1-8210-13bb59590700
# 2026-09-19 WB：提醒形态改为**从 channel.txt 读**（原为硬编码 "BALL"）。
#   原因：硬编码导致「改配置无效」——用户以为改了通道就有声，其实形态根本没被读。
#   白名单严格对齐 FloatingMode 枚举（WINDOW/BALL/VOICE_BALL/FULLSCREEN/RESULT_DISPLAY/SCREEN_OCR），
#   非法值会被平台以 `Invalid parameter: initial_mode is invalid` 拒收 → 故此处兜底为 VOICE_BALL。
MODE=$(sed -n 's/^mode=//p' "$D/channel.txt" 2>/dev/null | head -1)
# [LINKUP2 2026-09-20 用户反馈驱动的送达改造]
#   用户原话：弹窗「每次我一律叉掉」，因为多条对话同时在弹、根本看不清是什么。
#   → 悬浮窗对该用户已被证明是**无效送达**（不是美观问题，是触达失败）。
#   做法：从 channel.txt 读 show_floating（缺省仍为 true 保持旧行为）；
#         置 false 时只把消息落进固定会话 → 靠会话本身与系统通知触达，不再抢屏。
#   回滚：channel.txt 改回 show_floating=true 即恢复原形态。
FLOAT=$(sed -n 's/^show_floating=//p' "$D/channel.txt" 2>/dev/null | head -1 | tr -d ' \r')
# 行内注释剥离：配置里写 `show_floating=false  # 原因` 时，值必须取到 false 而不是 "false#原因"
FLOAT=$(printf '%s' "$FLOAT" | sed 's/#.*//' | tr -d ' \r')
case "$FLOAT" in true|false) : ;; *) FLOAT=true ;; esac
# 显式覆盖（人工试投用，不改配置文件）：FLOAT_FORCE=on 强制弹窗 / FLOAT_FORCE=off 强制不弹
case "$FLOAT_FORCE" in on) FLOAT=true ;; off) FLOAT=false ;; esac
SPK=$(sed -n 's/^speak=//p' "$D/channel.txt" 2>/dev/null | head -1 | sed 's/#.*//' | tr -d ' \r')
case "$SPK" in true|false) : ;; *) SPK=true ;; esac
# 2026-09-20 WB07 形态分层：允许调用方按「档位」临时指定形态（MODE_OVERRIDE），
#   使「可见度/打扰度」与 channel.txt 的全局默认解耦——阶梯档 1 用球、档 >=2 可换形态。
#   不传该变量时行为与旧版完全一致（仍读 channel.txt），零破坏。
[ -n "$MODE_OVERRIDE" ] && MODE="$MODE_OVERRIDE"
case "$MODE" in
  WINDOW|BALL|VOICE_BALL|FULLSCREEN|RESULT_DISPLAY|SCREEN_OCR) ;;
  *) MODE=VOICE_BALL ;;
esac
# [VOICE 2026-09-23 v5] 陪伴窗 ≠ 抢屏弹窗。
#   出声跟 show_floating 解耦：speak=true 就念一句；FLOAT 只决定是否抢屏。
#   不拉 AssistActivity、不改全局 auto_read、不进语音通话。
# [T08 2026-09-27] 不再念系统原文；改为拿到"小满·陪伴"的回复后念她那句（见下方 AIR 之后）。
# 弹窗默认 WINDOW；仅调用方 MODE_OVERRIDE 显式指定时才用其它形态
case "$MODE_OVERRIDE" in
  WINDOW|BALL|VOICE_BALL|FULLSCREEN|RESULT_DISPLAY|SCREEN_OCR) MODE="$MODE_OVERRIDE" ;;
  *) MODE=WINDOW ;;
esac
# v4 (2026-09-19 WB修通道): 原 async_callback 的监听器只活 25s，
# 而 AI 实测要 14~40s 才回，回复 POST 回来时端口已关 -> 回复 100% 丢，
# 这就是「弹窗里发了字毫无反应」的机制。改用 sync：回复直接进 HTTP 响应。
_BODY="{\"message\":\"$MSG\",\"response_mode\":\"sync\",\"show_floating\":$FLOAT,\"initial_mode\":\"$MODE\",\"chat_id\":\"$CID\",\"create_new_chat\":false,\"request_id\":\"$ID\"}"
OUT=$(curl -s -m 90 -w 'HTTPCODE=%{http_code}' -X POST http://127.0.0.1:8094/api/external-chat \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "$_BODY")
RESP=$(echo "$OUT" | sed 's/HTTPCODE=[0-9]*$//')
HTTP=$(echo "$OUT" | grep -o 'HTTPCODE=[0-9]*' | cut -d= -f2)
# [E14] 401 自动换钥匙重投一次（两源不同时才试，避免重复撞同一把）
if [ "$HTTP" = "401" ] && [ -n "$TOK_ALT" ]; then
  printf '%s\tRETRY_ALT_KEY id=%s http=401\n' "$NOW" "$ID" >> "$D/alert_log.tsv"
  OUT=$(curl -s -m 90 -w 'HTTPCODE=%{http_code}' -X POST http://127.0.0.1:8094/api/external-chat \
    -H "Authorization: Bearer $TOK_ALT" -H 'Content-Type: application/json' -d "$_BODY")
  RESP=$(echo "$OUT" | sed 's/HTTPCODE=[0-9]*$//')
  HTTP=$(echo "$OUT" | grep -o 'HTTPCODE=[0-9]*' | cut -d= -f2)
  [ "$HTTP" = "200" ] || [ "$HTTP" = "202" ] && TOK=$TOK_ALT
fi
# 把 AI 回复正文回投成一条可见消息（你弹窗里就能看到它说话）
AIR=$(printf '%s' "$RESP" | sed -n 's/.*"ai_response":"\(.*\)","*$/\1/p' | head -c 400)
[ -z "$AIR" ] && AIR=$(printf '%s' "$RESP" | sed -n 's/.*"ai_response":"\([^"]*\).*/\1/p' | head -c 400)
# [T08 2026-09-27] 念她的回复（和会话里看到的是同一句）
if [ -n "$AIR" ] && { [ "$SPK" = "true" ] || [ "$SPEAK_FORCE" = "on" ]; }; then
  SAY=$(printf '%s' "$AIR" | sed -e 's/\\n/ /g' -e 's/<think.*<\/think>//g' | tr -d '\134')
  SPEAK_FORCE=on sh "$D/speak.sh" "$SAY" >/dev/null 2>&1 &
fi
# 2026-09-20 形态修复：球态（BALL/VOICE_BALL）不再补发 RESULT_DISPLAY 回显窗。
#   原因：回显是第二次 POST，会再开一个窗 → 一次提醒叠两个窗，加剧「关不掉」。
#   球态下用户点开球即可在同一会话里看到 AI 回复（external-chat 已落库），无需第二个窗。
#   非球态（WINDOW/FULLSCREEN 等）保持原行为不变。要恢复旧行为：ECHO_REPLY=force
# [2026-09-24 治理套娃] 默认阻断把 AI 回应伪装成「用户消息」再次投回会话的链路（避免触发模型二次接话产生刷屏）。
#   默认禁用：ECHO_REPLY 缺省为 off。
#   显式恢复旧行为：在环境中传入 ECHO_REPLY=force 或 ECHO_REPLY=on 即可恢复。
ECHO_REPLY="${ECHO_REPLY:-off}"
if [ -n "$AIR" ] && [ "$ECHO_REPLY" != "off" ]; then
  case "$MODE" in BALL|VOICE_BALL) : ;;
  *)
  timeout 6 curl -s -m 5 -X POST http://127.0.0.1:8094/api/external-chat \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d "{\"message\":\"[系统回显，勿再回复] AI刚才对你说：$AIR\",\"response_mode\":\"sync\",\"show_floating\":true,\"initial_mode\":\"RESULT_DISPLAY\",\"chat_id\":\"$CID\",\"request_id\":\"ECHO-$ID\"}" >/dev/null 2>&1 &
  ;;
  esac
fi

case "$HTTP" in
  200|202) RC=0 ;;
  *) RC=1 ;;
esac
# [E7 2026-09-20 新增] 提醒通道失效探测器（用户"直接大量执行"授权轮）
#   背景：09-20 16:36 起 external-chat 全量返回 401 Unauthorized（Bearer token 失效），
#   此后判断/冻结/锁屏照常自动执行，但**每一条给用户的提醒都没出去** —— 整整 3 小时无人发现。
#   一个"手脚还在动、嘴已经哑"的监督系统必须自己知道嘴哑了。此处把失效从"埋在 RESP 行里"
#   升级为显式事件 + 累计时长游标，供引擎拍/巡检/日报读取。
# 注意：toybox grep 的 BRE 不认 \| 作或运算，必须用多个 -e（首版写错导致探测器不触发）
if printf '%s' "$RESP" | grep -qi -e Unauthorized -e '"error"' -e Forbidden -e Invalid; then
  ST=$D/alert_state.tsv
  printf '%s\tALERT_OUTAGE\trc=%s\thttp=%s\treason=api_rejected\tid=%s\n' "$NOW" "$RC" "$HTTP" "$ID" >> "$ST"
  [ -f "$D/.outage_since" ] || printf '%s\t%s\n' "$(date +%s)" "$NOW" > "$D/.outage_since"
  OSINCE=$(sed -n '1p' "$D/.outage_since" 2>/dev/null | cut -f1)
  OM=$(awk -v s="$OSINCE" -v n="$(date +%s)" 'BEGIN{ if (s ~ /^[0-9]+$/) printf "%d", (n - s) / 60; else print "NA" }')
  sh "$E/eventd.sh" add ALERT_OUTAGE http="$HTTP" id="$ID" outage_min="$OM" token_hint="见 /data/local/tmp/loop_test.sh 或 p2/api.env" \
    --dedup "ao-$(date +%Y%m%d%H)" >/dev/null 2>&1
elif [ "$RC" = "0" ]; then
  # 通道恢复：清游标并留一条恢复事件（若曾有 outage_since）
  if [ -f "$D/.outage_since" ]; then
    rm -f "$D/.outage_since"
    sh "$E/eventd.sh" add ALERT_RECOVERED id="$ID" chan=floating_window --dedup "ar-$(date +%Y%m%d%H%M)" >/dev/null 2>&1
  fi
fi
APP=$(sed -n 's/^app=//p' "$D/alert_input.txt" | head -1)
printf '%s\tALERT\trc=%s\tid=%s\ttask=%s\tapp=%s\n' "$NOW" "$RC" "$ID" "$(sed -n 's/^TASK=//p' "$D/task_state.txt" | head -1)" "$APP" >> "$D/alert_log.tsv"
[ $RC -ne 0 ] && printf '%s\tRESP\t%s\n' "$NOW" "$RESP" >> "$D/alert_log.tsv"

# 同步登记进每日事件流（提醒发生 = 一个事件）
sh "$E/eventd.sh" add ALERT id="$ID" chan=floating_window rc="$RC" \
  task="$(sed -n 's/^TASK=//p' "$D/task_state.txt" | head -1)" app="$APP" target_chat="$CID" --dedup "alert-$ID"

wait $NCJ 2>/dev/null
# v4: CB 语义重定义。旧口径=「回调端口收到 POST」；sync 模式无监听器，若仍按旧口径恒为 0，
#   会让 judge_deliver.sh 每次都判定「未送达」→ 重发两遍 + 冷却游标永不写入（本脚本自己引入的回归，已修）。
#   新口径=「AI 回音真的拿到了」：HTTP 2xx 且响应体含非空 ai_response → CB=1。
if [ "$CALLBACK" = "on" ]; then
  if grep -q "$ID" "$D/callback_last.txt" 2>/dev/null; then CB=1; else CB=0; fi
else
  CB=0
  case "$HTTP" in 200|202) printf '%s' "$RESP" | grep -q '"ai_response":"[^"]' && CB=1 ;; esac
fi
printf '%s\tCB\tgot=%s\tid=%s\n' "$(date '+%F %T')" "$CB" "$ID" >> "$D/alert_log.tsv"

# [LINKUP 2026-09-20] 通道健康自证 + 未送达兜底出口
#   起因：Operit 每次重启会轮换 external-chat token，而本脚本的 token 取自应用私有目录之外的
#         硬编码快照（loop_test.sh / p2/api.env）。09-20 11:26 重启后 token 失效，
#         16:36–19:19 共 14 发全部 Unauthorized，但 alert_log 只留 rc=1、无人聚合 →
#         整条"判断→开口"链静默断了一个多小时，用户零感知，判断链却以为自己说了。
#   做法1（自证）：把最近一次投递的通道状态写进 channel_health.tsv，供上游与日报消费。
#   做法2（兜底出口）：CB!=1 时把消息原文落进 outbox_unsent.tsv。该文件不需要任何 token，
#         任何在 AI 侧轮询的通路都能读到它 → 提醒不会因凭据过期而彻底消失。
CH_REASON=OK
case "$HTTP" in
  200|202) : ;;
  401) CH_REASON=UNAUTHORIZED ;;
  0|000|"") CH_REASON=NO_RESPONSE ;;
  *) CH_REASON="HTTP_$HTTP" ;;
esac
if [ "$CB" = "1" ]; then
  CH_STATE=OK
elif [ "$CH_REASON" = "OK" ]; then
  CH_STATE=SENT_NO_ECHO
else
  CH_STATE=BROKEN
fi
printf '%s\t%s\tstate=%s\thttp=%s\tcb=%s\tid=%s\ttoken_src=%s\n' "$(date '+%F %T')" "$ID" \
  "$CH_STATE" "${HTTP:-NA}" "$CB" "$ID" "${TOKSRC:-legacy_snapshot}" >> "$D/channel_health.tsv" 2>/dev/null
if [ "$CB" != "1" ] && [ -n "$MSG" ]; then
  printf '%s\t%s\t%s\tstate=%s\t%s\n' "$(date '+%F %T')" "$ID" "$CH_STATE" "$CH_STATE" \
    "$(printf '%s' "$MSG" | tr '\t\n' '  ' | cut -c1-600)" >> "$D/outbox_unsent.tsv" 2>/dev/null
fi

# 2026-09-20 WB07：动作账本（L4 审计专用，规格由 WB06 定死）
#   写在这里而不是各调用方 —— 因为本脚本是所有"开口/弹窗/语音"的唯一最底层出口
#   （incidentd / ladder_deliver / judge_deliver / 手工槽 全走这里），一处覆盖全路径、不重复记账。
#   readback_result 口径：CB=1 才算真送达；CB=0/rc=1 一律 UNKNOWN，不用 false 冒充"没送达"当结论。
_J=${OPERIT_JUDGE_DIR:-/sdcard/Download/Operit/judge}
if [ "$CB" = "1" ]; then RB="CB=1 rc=$RC http=$HTTP mode=$MODE"; else RB="UNKNOWN cb=$CB rc=$RC http=$HTTP"; fi
# type 取枚举值：语音形态=voice，其余悬浮窗=float（不新造词，便于 L4 聚合）
case "$MODE" in VOICE_BALL) ATYPE=voice ;; *) ATYPE=float ;; esac
sh "$_J/acts_ledger.sh" action "$ATYPE" "${APP:-none}" "drift_alert_send id=$ID" "$RB" "task=$(sed -n 's/^TASK=//p' "$D/task_state.txt" | head -1) chat=$CID" >/dev/null 2>&1

echo "ALERT_RC=$RC"
echo "CB=$CB"
echo "ID=$ID"
echo "MSG=$MSG"