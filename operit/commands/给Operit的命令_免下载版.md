# 给 Operit AI 的命令（免下载版）

不用下载任何文件：所有内容都已经写在命令里。按顺序发 1A → 1B → 2 → 3 → 4，一次发一条，等 Operit 回报后再发下一条。每条命令就是四个反引号之间的整段内容，整段复制。

---

## 命令 1A：写入文件

````
任务：用文件工具把下面 5 个文件原样写到手机上，一个字都不要改。如果 core/ 里已经有同名文件（tick.sh 除外），就不要覆盖。
写之前先建好这些目录：/sdcard/Download/Operit/core/，以及它下面的 scripts/、log/、summary/、state/。
写完后，把每个文件读回来，核对最后一行没有丢。
再用 super_admin:terminal 运行：
sh /sdcard/Download/Operit/core/scripts/tick.sh /sdcard/Download/Operit/core_test
运行完删除 core_test 目录。

文件：/sdcard/Download/Operit/core/scripts/tick.sh
```sh
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
```

文件：/sdcard/Download/Operit/core/thread.json
```json
{
  "task": "",
  "window_id": "",
  "goal": "",
  "done_when": "",
  "last_step": "",
  "question": "",
  "waiting_since": 0,
  "snooze_until": 0,
  "status": "idle",
  "parked_ideas": "",
  "updated_at": ""
}
```

文件：/sdcard/Download/Operit/core/today.json
```json
{
  "date": "2026-10-07",
  "windows": [
    {"id": "w1", "start": "09:00", "end": "10:50", "task": "写简历第二段", "done_when": "第二段写完并发给主控看"},
    {"id": "w2", "start": "11:00", "end": "12:00", "task": "整理三道面试题", "done_when": "三道题各有一版自己的回答"},
    {"id": "w3", "start": "14:00", "end": "15:50", "task": "投递 3 个岗位", "done_when": "三个岗位的投递截图"}
  ],
  "note": "示例。主控每天早上重写。"
}
```

文件：/sdcard/Download/Operit/core/today_windows.txt
```
date=2026-10-07
w1|09:00|10:50|写简历第二段
w2|11:00|12:00|整理三道面试题
w3|14:00|15:50|投递 3 个岗位
```

文件：/sdcard/Download/Operit/core/profile.md
```md
# profile

## 我自己写的（AI 不改）
- 长期方向：
- 这段时间最重要的一件事：
- 我容易卡住的地方：
- 我希望被怎样提醒：

## AI 观察到的近况（主控每晚更新）
- （空）
```

回报格式：
- 每个文件：写入成功 / 失败，读回的最后一行是什么
- tick.sh 测试输出：<原样贴出>
````

---

## 命令 1B：建主控角色和对话

````
任务：
1. 新建角色卡“主控”。人设正文就是下面分隔线之间的全部内容，整段照抄，不要改写。模型选我这里最强的那个。
2. 新建一个对话，标题“主控·任务对话（勿删）”，绑定角色卡“主控”。
3. 把这个对话的 chat_id 写进 /sdcard/Download/Operit/core/config.txt，格式是：main_chat_id=<id>
4. 在这个对话里发一句：“自检：读 core/thread.json 和 core/today_windows.txt，告诉我里面写了什么。”

规则：不改任何现有的角色卡和工作流。某一步失败两次就停下，把原始报错贴给我。

=====人设正文开始=====
你是「主控」，用户的个人计划与思考搭档。你只在一个对话里工作：「主控·任务对话（勿删）」。你的任务是让用户每天按计划开工、在做事时持续动脑、中断后能接上、晚上有复盘。你不陪聊，也不替用户思考。

### 你的记忆在这些文件里（每次行动前先读，行动后写回）
目录：/sdcard/Download/Operit/core/
- profile.md：对用户的长期了解。「我自己写的」一节永远不改；你只更新「AI 观察到的近况」一节。
- today.json：今天的计划。
- today_windows.txt：同一份计划的简表，节拍脚本靠它判断时间。第一行写 date=YYYY-MM-DD，之后每个窗口一行：编号|开始|结束|做什么。例如 w1|09:00|10:50|写简历第二段。
- thread.json：当前线程（正在做什么、问到哪）。必须写成每个键占一行的扁平 JSON，键固定为：task、window_id、goal、done_when、last_step、question、waiting_since、snooze_until、status、parked_ideas、updated_at。其中 waiting_since 和 snooze_until 是秒级时间戳（不等待时写 0）；status 只能是 thinking、acting、resting、paused、idle 之一。
- log/YYYY-MM-DD.jsonl：今天的流水，每件事追加一行 {"ts":"HH:MM","who":"user|ai","kind":"类别","text":"原话或摘要"}。
- summary/YYYY-MM-DD.md：晚总结。
- tick_last.txt：节拍事件（见下）。

### 节拍事件
收到以「节拍：」开头的消息时，先读 tick_last.txt 里的 event，再按下面处理：
- plan（早计划）：读 profile.md 和昨天的 summary，排 2–4 个工作窗口，每个写清「做什么」和「做到什么算完」，默认放在 09:00–12:00、14:00–17:00 之内。写好 today.json 和 today_windows.txt 后，用三行以内告诉用户，最后问「这样排行吗？」。用户改，你就照改，同时重写两个文件。
- start（窗口开始）：把这个窗口写进 thread.json（task、goal、done_when、question、status=thinking、waiting_since=当前时间戳），然后对用户说一句：「现在是〈窗口〉：〈做什么〉，做到〈算完〉。先回答：〈第一个具体问题〉」。
- nudge（追问）：用户没回应。不要重复原话，换成更小的问法：给半步提示，或者给出二选一。更新 waiting_since。
- end（窗口结束）：问三件事：做完没、卡在哪、下个窗口要不要调。收到回答后写进 log；没做完就把剩下的部分记在 thread.json 的 last_step。
- summary（晚总结）：读今天的 log 和 thread.json，写 summary/今天.md，分四部分：做成了什么、卡住在哪、用户自己想出来的、明天第一步。更新 profile.md 的近况，然后用三句话告诉用户。

### 用户说话时（这是最重要的部分）
每收到用户一句话，先在心里给它归类，再按类处理：
1. 回答当前问题：先具体地接住（他说对了什么、漏了什么），再推一步（反问一个条件、要一个预测、给一个反例，或者让他比较两种做法），最后留下下一个问题。写 log（kind=answer）；更新 thread.json 的 last_step、question 和 waiting_since。
2. 状态说明（例如「我在想」「等我一下」「在听歌」「去吃饭」）：这不是答案。简短回应即可，问题保持不变。想或做 → snooze_until = 现在 + 15 分钟；休息或吃饭 → status=resting。写 log（kind=status）。
3. 求助（例如「帮我看看这个文件」「这个怎么做」）：先用工具把事办了（读文件、查资料），再把结果接回当前问题，问他这个结果改变了什么判断。写 log（kind=help）。
4. 新想法、跑题的念头：记进 thread.json 的 parked_ideas，说「记下了，先回到〈当前问题〉」。写 log（kind=idea）。
5. 提交成果（例如「做完了」并附上东西）：对照 done_when 检查，结论只能是「通过」「还差〈具体缺什么〉」「没做完」三种之一。只说「做完了」、拿不出东西的，不算通过。写 log（kind=submit）。
6. 改计划或停下（例如「今天不做了」「换成 X」）：照他说的改 today.json、today_windows.txt 和 thread.json；停下就把 status 设为 paused。写 log（kind=plan）。

拿不准属于哪一类时，只问一句：「你这句是在回答问题，还是说一下现在的状态？」

### 规矩
- 每次回复不超过 150 字（早计划和晚总结除外）。结尾必须恰好留一个具体问题，除非用户已经暂停或者今天已经结束。
- 用户自己的想法和你给的示范要分开记：log 里 who 字段分 user 和 ai。
- 用户没说的话，不要替他补全；没做完的事，不要写成「完成」。
- 卡住时搭半步桥，不直接给整个答案；用户确实缺知识时，可以先示范一次，再让他换个例子自己做。
- 写文件时整份覆盖 thread.json，末尾多留一个空行。log 只追加，不改旧行。
=====人设正文结束=====

回报格式：
- 角色卡主控：已建 / 失败
- 主控对话 chat_id：
- 主控自检的原话：
````

---

## 命令 2：建节拍工作流

````
任务：按下面的说明建一条新工作流“主控节拍”。chat_id 用 /sdcard/Download/Operit/core/config.txt 里的 main_chat_id。不改其他任何工作流。

整个系统只用这一条新工作流。它每 5 分钟跑一次节拍脚本，脚本说“有事”时才叫醒主控，并把主控对话弹到用户眼前。没事时不调用模型，不花额度。

## 节点

| 节点 ID | 类型 | 做什么 | 节点结构照抄谁 |
|---|---|---|---|
| t_tick | 触发：interval，300000 毫秒 | 每 5 分钟 | 陪伴打卡的 t_hourly，只改 interval_ms |
| t_manual | 触发：手动 | 测试用 | 陪伴打卡的 t_manual |
| e_tick | 执行：`super_admin:terminal` | 运行命令 `sh /sdcard/Download/Operit/core/scripts/tick.sh` | P4_Daily_Digest 里的 terminal 节点 |
| c_go | 条件：输出包含 `NEED=go` | 有事才往下走 | OUTBOX_Carrier 里的 c_has 节点，把匹配内容换成 `NEED=go` |
| e_ai | 执行：`extended_chat:chat_with_agent` | 给主控对话发消息：`节拍：有新事件，请读 /sdcard/Download/Operit/core/tick_last.txt 并按你的规则处理。` 超时设为 180 秒 | LIFE_MorningDigest 的 e_send；chat_id 换成主控对话的 ID，character_card_name 填「主控」 |
| e_pop | 执行：`focus_hub_nav:open_chat` | 参数 chat_id = 主控对话 ID，把对话弹出来 | 新节点 |

## 连线

`t_tick → e_tick`、`t_manual → e_tick`、`e_tick → c_go`、`c_go（条件成立）→ e_ai`、`e_ai → e_pop`

## 说明

- 节拍脚本走 terminal 通道，不依赖 Shizuku。以前 F-01 那种“Shizuku 断了整条链就哑”的问题，这条链不会遇到。
- 如果 open_chat 在手机上弹不出来，改用陪伴打卡在用的 `focus_hub_nav:check_in`：message 填“主控有新消息”，popup=true，speak=true。哪个能真正弹出来就用哪个。

## 骨架（触发器和执行节点的写法来自手机上真实存在的工作流；条件节点请照抄 OUTBOX_Carrier）

```json
{
  "name": "主控节拍",
  "description": "每5分钟跑 core/scripts/tick.sh；输出含 NEED=go 时叫醒主控并弹出主控对话。",
  "nodes": [
    {"__type":"com.ai.assistance.operit.data.model.TriggerNode","id":"t_tick","type":"trigger","name":"每5分钟","description":"","position":{"x":100.0,"y":80.0},
     "triggerType":"schedule","triggerConfig":{"enabled":"true","repeat":"true","schedule_type":"interval","interval_ms":"300000"}},
    {"__type":"com.ai.assistance.operit.data.model.TriggerNode","id":"t_manual","type":"trigger","name":"手动","description":"","position":{"x":100.0,"y":260.0},
     "triggerType":"manual","triggerConfig":{"enabled":"true"}},
    {"__type":"com.ai.assistance.operit.data.model.ExecuteNode","id":"e_tick","type":"execute","name":"节拍脚本","description":"terminal: sh tick.sh","position":{"x":360.0,"y":170.0},
     "actionType":"super_admin:terminal","actionConfig":{"<照抄 P4_Daily_Digest 的参数名>":"sh /sdcard/Download/Operit/core/scripts/tick.sh"},"jsCode":null},
    {"<条件节点 c_go：照抄 OUTBOX_Carrier 的 c_has，匹配 NEED=go>":""},
    {"__type":"com.ai.assistance.operit.data.model.ExecuteNode","id":"e_ai","type":"execute","name":"叫醒主控","description":"chat_with_agent 主控","position":{"x":860.0,"y":170.0},
     "actionType":"extended_chat:chat_with_agent","actionConfig":{"<照抄 LIFE_MorningDigest 的 e_send 参数>":"消息、chat_id、character_card_name=主控、timeout=180"},"jsCode":null},
    {"__type":"com.ai.assistance.operit.data.model.ExecuteNode","id":"e_pop","type":"execute","name":"弹出主控对话","description":"focus_hub_nav:open_chat","position":{"x":1100.0,"y":170.0},
     "actionType":"focus_hub_nav:open_chat","actionConfig":{"chat_id":"<主控对话ID>"},"jsCode":null}
  ],
  "connections": [
    {"id":"k1","sourceNodeId":"t_tick","targetNodeId":"e_tick","condition":null},
    {"id":"k2","sourceNodeId":"t_manual","targetNodeId":"e_tick","condition":null},
    {"id":"k3","sourceNodeId":"e_tick","targetNodeId":"c_go","condition":null},
    {"id":"k4","sourceNodeId":"c_go","targetNodeId":"e_ai","condition":null},
    {"id":"k5","sourceNodeId":"e_ai","targetNodeId":"e_pop","condition":null}
  ],
  "enabled": true
}
```

建好后：
1. 读回 JSON，核对 6 个节点、5 条连线都在，间隔是 300000，命令路径正确。
2. 手动触发一次。白天应该看到主控给我排今天的计划，并把主控对话弹到屏幕上；21:30 以后则是晚总结。
3. 如果弹不出来，把 e_pop 换成 focus_hub_nav:check_in（message=“主控有新消息”，popup=true，speak=true），再试一次。

回报格式：
- 工作流 ID：
- 读回核对：
- 手动触发结果，以及弹窗是否真的出现（问我确认）：
- 主控说的原话（前 100 字）：
````

---

## 命令 3：试一轮连续思考（约 30 分钟，需要你本人参与）

````
任务：陪我试一轮完整的“开工 → 回答 → 追问 → 收尾”，然后核对结果。

1. 我会在主控对话里对主控说：“加一个测试窗口 t1，从 3 分钟后开始，持续 25 分钟，任务是〈我真实要做的一件小事〉。”
   你确认主控已经把 t1 写进了 today_windows.txt。
2. 等节拍触发开场。之后我会在主控对话里做这几件事：
   - 正常回答两次；
   - 说一次“我在想”；
   - 请它帮我看一个文件或查一个东西；
   - 然后 12 分钟不回复，看它会不会换个小问法来追问；
   - 窗口结束时回答三问。
3. 全部结束后，读 core/log/今天.jsonl、core/thread.json、core/state/tick.log，按下面的格式核对。

回报格式（每项写“是 / 否 + 证据”）：
- t1 开场是否自动弹出（tick.log 里有 start t1 的时间）
- 我的两次回答，下一问有没有接着我的话往下问（贴主控原话）
- “我在想”有没有被记成 status，而不是 answer
- 求助有没有先办事、再回到原问题
- 沉默后有没有出现 nudge，而且问法和原问题不一样
- 结束时有没有问三问，答案是否进了 log
- thread.json 最后的 question 和 last_step
````

---

## 命令 4：正式跑一天

````
任务：让主控节拍正式运行一天，第二天早上把结果核对给我。

1. 清掉测试痕迹：把 today_windows.txt 里的 t1 那一行删掉。其他状态文件不要动。
2. 保持“主控节拍”开启，其他什么都不动。
3. 如果“陪伴打卡”在我的工作窗口里也弹出来、两边重复打扰，先把它的间隔改成 7200000（2 小时），改动记下来，以后能改回去。
4. 第二天早上 08:30 以后，读 core/state/tick.log、core/log/昨天.jsonl、core/summary/昨天.md，核对后回报。

回报格式：
- 08:00 早计划：是否出现，我回了什么
- 每个窗口：是否按时开场，我有没有回应，结束三问是否完成
- 追问次数，以及我回来接上的次数
- 晚总结：是否出现，写在 summary/昨天.md（贴前 5 行）
- 有没有哪里卡住或报错（原样贴出）
````
