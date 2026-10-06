# 给 Operit AI 的命令（按顺序发，一次一条）

准备工作：把 `operit_kit_v1.zip` 放到手机的 `/sdcard/Download/Operit/` 下。每条命令整块复制，发给 Operit AI 的普通对话（不要发到主控对话里）。等它回报后，再发下一条。

---

## 命令 1：搭底座（约 10 分钟）

```
任务：按套件搭好“主控”系统的底座。只做下面这些，做完按格式回报，不需要解释。

1. 把 /sdcard/Download/Operit/operit_kit_v1.zip 解压到 /sdcard/Download/Operit/kit/。
2. 建目录 /sdcard/Download/Operit/core/，以及它下面的 scripts/、log/、summary/、state/。
3. 把 kit/templates/ 里的 profile.md、thread.json、today.json、today_windows.txt 复制到 core/。
   如果 core/ 里已经有同名文件，就不要覆盖。
4. 把 kit/scripts/tick.sh 复制到 core/scripts/tick.sh。
5. 用 super_admin:terminal 运行：
   sh /sdcard/Download/Operit/core/scripts/tick.sh /sdcard/Download/Operit/core_test
   输出应该是以 NEED= 开头的一行。运行完删除 core_test 目录。
6. 新建角色卡“主控”，人设正文用 kit/roles/主控.md 里「人设正文」那一节，整段照抄，不要改写。
   模型选我这里最强的那个。
7. 新建一个对话，标题“主控·任务对话（勿删）”，绑定角色卡“主控”。
   把这个对话的 chat_id 写进 core/config.txt，格式是：main_chat_id=<id>
8. 在这个对话里发一句：“自检：读 core/thread.json 和 core/today_windows.txt，告诉我里面写了什么。”
   确认主控能读到文件。

规则：不改现有的任何工作流和角色卡。某一步失败两次就停下，把原始报错贴给我，不要换别的办法绕过去。

回报格式（每项一行）：
- 解压：成功 / 失败（附报错）
- core 目录：已建，文件清单
- tick.sh 测试输出：<原样>
- 角色卡主控：已建
- 主控对话 chat_id：<id>
- 主控自检：读到了 / 没读到（附它的原话）
```

---

## 命令 2：建节拍工作流（约 15 分钟）

```
任务：照 /sdcard/Download/Operit/kit/workflows/主控节拍.md 建一条新工作流“主控节拍”。

1. 节点结构照表里写的去抄：
   - 触发器抄“陪伴打卡”；
   - terminal 节点抄“P4_Daily_Digest”；
   - 条件节点抄“OUTBOX_Carrier”的 c_has；
   - chat_with_agent 抄“LIFE_MorningDigest”的 e_send。
   chat_id 用 core/config.txt 里的 main_chat_id，character_card_name 填“主控”。
2. 建好后读回整条工作流的 JSON，核对：
   - 6 个节点、5 条连线都在；
   - 间隔是 300000；
   - 命令是 sh /sdcard/Download/Operit/core/scripts/tick.sh。
3. 手动触发一次。预期：
   - 白天（08:00–21:30）：主控会给我排今天的计划，并把主控对话弹出来；
   - 晚上 21:30 以后：主控会写晚总结。
   如果 open_chat 弹不出来，把 e_pop 换成 focus_hub_nav:check_in（message="主控有新消息"，popup=true，speak=true），再试一次。
4. 不改其他任何工作流。

回报格式：
- 工作流 ID：
- 读回核对：6 节点 5 连线，正确 / 有偏差（写出偏差）
- 手动触发：e_tick 输出 / e_ai 结果 / 弹窗是否真的出现在屏幕上（问我确认）
- 主控实际说了什么（原文前 100 字）
- core/today_windows.txt 现在的内容
```

---

## 命令 3：试一轮连续思考（约 30 分钟，需要你本人参与）

```
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
```

---

## 命令 4：正式跑一天

```
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
```
