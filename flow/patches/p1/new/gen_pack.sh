#!/system/bin/sh
# gen_pack.sh —— G2 分析判断流 · 证据包生成器
# 只读：不写任何业务文件，只把证据包打到 stdout，交给判断官
# 出口：stdout（多行文本）
# 2026-09-18: 第6段改为"硬过滤"——按块判来源，只有 SRC=EXTERNAL 归入外部事实
E=/sdcard/Download/Operit/events
J=/sdcard/Download/Operit/judge
D=$(date +%Y%m%d)
TSV=$E/$D/events.tsv
DB=/data/local/tmp/operit_core/core.db
SQ=/system/bin/sqlite3

echo "== 证据包 $(date '+%F %T') =="

# 1) 当前任务与明确意图（优先 P1/task_state 投影，普通聊天不自动升级为 REAL_USER）
TASK=$(tail -200 "$TSV" 2>/dev/null | awk -F'\t' '$4=="FOREGROUND"{for(i=5;i<=NF;i++){if($i~/^task=/)t=substr($i,6)}} END{print t}')
INTENT=/sdcard/Download/Operit/state/user_intent.json
if [ -s "$INTENT" ]; then
  echo "当前意图来源: DISK_TASK_CARD_PROJECTION"
  grep -E '"(intent_id|task|next_action|status|source|source_type)"' "$INTENT" | cut -c1-240
fi
echo "声明任务: ${TASK:-未声明}"

# 2) 棋盘状态
if [ -f "$DB" ]; then
  $SQ "$DB" "SELECT '棋盘任务: '||COALESCE(current_object,'-')||' | 状态: '||status||' | 下一步: '||COALESCE(next_action,'-') FROM tasks WHERE task_id=(SELECT value FROM board WHERE key='current_task_id');" 2>/dev/null
  $SQ "$DB" "SELECT '棋盘版本: '||value FROM board WHERE key='version';" 2>/dev/null
else
  echo "棋盘: 不可读"
fi

echo "== HASH-BEGIN =="
# 3) 最近 12 条前台采样
echo "-- 最近前台采样 --"
tail -12 "$TSV" 2>/dev/null | awk -F'\t' '$4=="FOREGROUND"{p="";o="";for(i=5;i<=NF;i++){if($i~/^pkg=/)p=substr($i,5);if($i~/^ontask=/)o=substr($i,8)} print $3, p, "ontask="o}'

# 3b) [LP轮 2026-09-20] 截屏事实进证据包：屏幕内容=那一刻行为的直接证据
#   只收严格合法行（LP-3 后新行零污染；旧污染行按同一正则自然被挡），近3条，限长。
if [ -s "$E/shot_facts.tsv" ]; then
  echo "-- 截屏事实(近3) --"
  tail -3 "$E/shot_facts.tsv" | grep -oE 'FACT\|[0-9]+\|app=(boss|zhipin|qiancheng|other)\|resume=(yes|no|unknown)\|applied=([0-9]+|unknown)\|interview=([0-9]+|unknown)' | cut -c1-120
fi

# 3c) [P1 2026-09-26] 用户本人进展（focus_hub 写入 progress/*.jsonl，origin=REAL_USER）
#   按 ts（绝对时间）取最近 24 小时，不依赖文件名日期（设备时区与事件时区不一致）。
#   指纹行进 HASH 区：用户新报进展 → 证据变化 → 判断官重新判断。
P1_NOW=$(date +%s)
p1_prog() {
  ls /sdcard/Download/Operit/progress/*.jsonl 2>/dev/null | tail -n 2 | while read -r f; do cat "$f"; done \
    | awk -v now="$P1_NOW" -v mode="$1" '
      function g(k,   m) {
        if (match($0, "\"" k "\":\"([^\"\\\\]|\\\\.)*\"")) { m = substr($0, RSTART + length(k) + 4, RLENGTH - length(k) - 5); gsub(/\\"/, "\"", m); return m }
        if (match($0, "\"" k "\":[0-9]+")) return substr($0, RSTART + length(k) + 3, RLENGTH - length(k) - 3)
        return ""
      }
      g("origin") == "REAL_USER" && (now - g("ts")) <= 86400 && (now - g("ts")) >= 0 {
        n++; id[n] = g("id"); k[n] = g("kind"); q[n] = g("user_quote"); nt[n] = g("note"); age[n] = int((now - g("ts")) / 60)
      }
      END {
        s = (n > 5) ? n - 4 : 1
        for (i = n; i >= s; i--) {
          if (mode == "id") { print id[i], "kind=" k[i] }
          else {
            kn = k[i]; lb = (kn=="done")?"提交/完成":(kn=="stuck")?"卡住":(kn=="pause")?"暂停":(kn=="progress")?"继续":(kn=="note")?"说明":kn
            a = (age[i] < 60) ? age[i] " 分钟前" : int(age[i] / 60) " 小时前"
            line = "[" a "] " lb "(" kn ")：「" q[i] "」"
            if (nt[i] != "") line = line " 备注：" nt[i]
            print substr(line, 1, 240)
          }
        }
        if (n == 0 && mode != "id") print "（最近 24 小时无）"
      }'
}
echo "-- 用户本人进展指纹（REAL_USER）--"
p1_prog id

echo "== HASH-END =="

# [P1 2026-09-26] 可读版进展（含"几分钟前"，放 HASH 区外，避免时间流逝本身触发重判）
echo "-- 用户本人说的进展（最近 24 小时，最新在前；这是用户亲口说的，优先于推测）--"
p1_prog text
echo "-- 进展判读准则 --"
echo "用户说已完成(done)或暂停(pause)的事，不得再判 DRIFT_RISK 催这件事；用户说卡住(stuck)时优先判 STUCK，WHY 写卡在哪。"
echo "采样与用户说法矛盾时，在 WHY 里写明矛盾，不要无视用户说法；用户说法之后又长时间在娱乐 App，才可判 DRIFT_RISK。"

# 4) 今日事件计数
echo "-- 今日事件计数 --"
awk -F'\t' '{c[$4]++} END{for(k in c) printf "%s=%d ", k, c[k]; print ""}' "$TSV" 2>/dev/null

# 5) 近 5 条非采样事件
echo "-- 近期非采样事件 --"
awk -F'\t' '$4!="FOREGROUND"{print $3, $4, $5}' "$TSV" 2>/dev/null | tail -5
# 6) 外部信息入口（若存在）—— 硬过滤：按"### ["块判定来源，只有 SRC=EXTERNAL 才算外部事实
IN=/sdcard/Download/Operit/judge/inbox.md
if [ -s "$IN" ]; then
  TMP=/data/local/tmp/_inb.$$
  : > "$TMP.ext"; : > "$TMP.self"
  CUR=self
  while IFS= read -r L; do
    case "$L" in
      '### ['*)
        case "$L" in
          *SRC=EXTERNAL*) CUR=ext ;;
          *LINK:*http*)   CUR=ext ;;
          *)              CUR=self ;;
        esac
        ;;
    esac
    printf '%s\n' "$L" >> "$TMP.$CUR"
  done < "$IN"
  echo "-- 外部事实（SRC=EXTERNAL，含来源凭证；可作 WHY 依据）--"
  if [ -s "$TMP.ext" ]; then tail -40 "$TMP.ext"; else echo "（无）"; fi
  echo "-- 自产/未核（SELF/UNVERIFIED，★禁止作为 WHY 依据）--"
  if [ -s "$TMP.self" ]; then tail -40 "$TMP.self"; else echo "（无）"; fi
  rm -f "$TMP.ext" "$TMP.self"
else
  echo "-- 外部信息条目: 无 --"
fi

# 7) E 步：已批准硬规则清单（只读；空则显式声明"仅建议级"）
#    [2026-09-20 解除限制] head -8 → head -${PACK_RULES_MAX:-20}，按 effect_level 分节展示，
#    并把"无动作授权"写进包内，避免判断官把 ADVICE 误读成许可。
echo "-- 已批准硬规则（ACTIVE_RULES；ADVICE 不含任何动作授权）--"
if [ -s "$E/ACTIVE_RULES.md" ]; then
  # [E9 2026-09-20] 过期规则不再进包：旧版所有读者都无视 expires_at，
  #   到期条目继续影响判断 = "规则永不失效"。此处按日期字段过滤（字段缺失者保留并标注）。
  TODAY_R=$(date '+%F')
  grep -v '^#' "$E/ACTIVE_RULES.md" 2>/dev/null | grep -v '^[[:space:]]*$' | cut -c1-200 \
    | awk -v td="$TODAY_R" '{
        if (match($0, /expires_at=[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/)) {
          ex = substr($0, RSTART+11, 10);
          if (ex < td) { printf "[已过期·仅存档，不得作为本拍依据] %s\n", $0; next }
        }
        print
      }' | head -"${PACK_RULES_MAX:-20}"
else
  echo "-- 无已批准规则：本拍不得执行任何冻结/限制类动作 --"
fi

# 8) E 步：最近 3 条判断（防同一证据反复得同一结论）
echo "-- 最近判断记录 --"
if [ -f "$J/$(date +%Y%m%d).jsonl" ]; then tail -3 "$J/$(date +%Y%m%d).jsonl" | cut -c1-200; else echo "（今日无）"; fi

# 9) 判断官边界声明（写进证据包，约束输出格式；判断官不改任务/不冻结/不发消息）
echo "-- 输出格式要求 --"
echo "VERDICT=<ON_TRACK|DRIFT_RISK|STUCK|NO_TASK|INSUFFICIENT> CONF=<0-100> WHY=<一句事实> EVIDENCE=<id1,id2> RECOMMENDED_LEVEL=<L0|L1|L2>"

# 10) 上次提醒 + 提醒之后的前台采样（供判断官确认"上次响应是否生效"）
# S2(2026-09-19 裁决1): 左边界取 max(last_alert.state, events.tsv 最新 ALERT 的 ts)；
#   无 last_alert.state 或值非数字则回退现有 tsv-ALERT 口径 —— 防游标倒退。
LA_TS=$(awk -F'\t' '$4=="ALERT"{t=$2} END{print t+0}' "$TSV" 2>/dev/null)
case "$LA_TS" in ''|*[!0-9]*) LA_TS=0 ;; esac
LA_STATE=$(sed -n '2p' "$J/last_alert.state" 2>/dev/null | tr -cd '0-9')
if [ -n "$LA_STATE" ] && [ "$LA_STATE" -gt "$LA_TS" ] 2>/dev/null; then
  LA="$LA_STATE"; LA_SRC="last_alert.state"
else
  LA="$LA_TS"; LA_SRC="tsv_ALERT"
fi
if [ "${LA:-0}" -gt 0 ]; then
  echo "-- 上次提醒 epoch=$LA (src=$LA_SRC) 之后的前台采样 --"
  awk -F'\t' -v la="$LA" 'BEGIN{la+=0} $4=="FOREGROUND" && ($2+0)>la{ p="";o=""; for(i=6;i<=NF;i++){if($i~/^pkg=/)p=substr($i,5); if($i~/^ontask=/)o=substr($i,8)} print $3, p, "ontask="o }' "$TSV" | tail -6
else
  echo "-- 上次提醒: 无 --"
fi
# 11) [E2 2026-09-20] 知识接血：把"学到的东西"注进包内（放在 HASH 区外，不参与同证据判定，
#     因此不会因历史经验变化而放大判断官调用量；判断官"只看包内"禁令依然成立）
echo "-- 候选教训（近 3 条，来自判断流水聚合；仅供参考，非事实）--"
if [ -f "$E/$D/lessons.jsonl" ] && [ -s "$E/$D/lessons.jsonl" ]; then
  tail -3 "$E/$D/lessons.jsonl" | cut -c1-200
else
  echo "（今日无候选教训）"
fi
echo "-- 今日 App 用量（分钟，发布器新鲜戳有效才展示；无=数据缺失，不得当作0）--"
FU=$E/../selfreview/usage_$D.tsv
FSD=$(sed -n 's/.*window=\([0-9-]\{10\}\).*/\1/p' "$E/../selfreview/.usage_fresh" 2>/dev/null | head -1)
if [ "$FSD" = "$(date '+%Y-%m-%d')" ] && [ -s "$FU" ]; then
  grep -E 'tv.danmaku.bili|com.baidu.tieba|com.ss.android.ugc.aweme|com.xingin.xhs|com.twitter.android|com.hpbr.bosszhipin' "$FU" | head -8
  echo "（来源窗口 $(sed -n 's/.*window=//p' "$E/../selfreview/.usage_fresh" | head -1)）"
else
  echo "（今日用量未发布 —— 涉及用量的判断必须写 INCONCLUSIVE，不得引用昨日电报）"
fi
echo "-- 施工硬约束条目索引（LESSONS.md 标题，判断可引用其编号）--"
grep -E '^## L[0-9]+' "$E/LESSONS.md" 2>/dev/null | head -24 || echo "（LESSONS.md 不可读）"

# 5b) 截屏取证（shot_facts.tsv：是否招聘App / 简历 / 已投 / 待面试）
if [ -s "$E/shot_facts.tsv" ]; then
  echo "-- 截屏取证（最近 5 条）--"
  tail -5 "$E/shot_facts.tsv"
fi
echo "== 证据包结束 =="
