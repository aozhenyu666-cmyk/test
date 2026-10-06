"""把 kit/ 里的文件内嵌进命令，生成“免下载版”命令：python3 build_commands.py"""
import pathlib

HERE = pathlib.Path(__file__).parent
KIT = HERE / "kit"
CORE = "/sdcard/Download/Operit/core/"


def read(rel):
    return (KIT / rel).read_text(encoding="utf-8").rstrip()


def file_block(path, rel, lang=""):
    return f"文件：{path}\n```{lang}\n{read(rel)}\n```\n"


files = "\n".join([
    file_block(CORE + "scripts/tick.sh", "scripts/tick.sh", "sh"),
    file_block(CORE + "thread.json", "templates/thread.json", "json"),
    file_block(CORE + "today.json", "templates/today.json", "json"),
    file_block(CORE + "today_windows.txt", "templates/today_windows.txt"),
    file_block(CORE + "profile.md", "templates/profile.md", "md"),
    file_block(CORE + "ledger.md", "templates/ledger.md", "md"),
])
role = read("roles/主控.md").split("## 人设正文", 1)[1].strip()
xiaoman = read("roles/小满_调整.md").split("### 新的分工（追加）", 1)[1].strip()
workflow = read("workflows/主控节拍.md").split("\n", 1)[1].strip()

out = f"""# 给 Operit AI 的命令（免下载版 v2：合伙人）

不用下载任何文件，所有内容都写在命令里。
按顺序发：1A → 1B → 1C → 2 → 3 → 4，一次发一条，等 Operit 回报后再发下一条。每条命令就是四个反引号之间的整段内容，整段复制。
如果之前已经发过 v1 的命令，照样从 1A 发起：已经存在的数据文件不会被覆盖，tick.sh 和角色卡会更新成新版。

---

## 命令 1A：写入文件

````
任务：用文件工具把下面 6 个文件原样写到手机上，一个字都不要改。

写之前先建好这些目录：/sdcard/Download/Operit/core/，以及它下面的 scripts/、log/、report/、summary/、state/。

除 tick.sh 以外，如果 core/ 里已经有同名文件，就不要覆盖。唯一的例外：如果已有的 thread.json 里没有 last_user_at 这个键，就用新模板覆盖它。tick.sh 一律覆盖成新版。

写完后把每个文件读回来，核对最后一行没有丢。

最后用 super_admin:terminal 运行：
sh /sdcard/Download/Operit/core/scripts/tick.sh /sdcard/Download/Operit/core_test
运行完删除 core_test 目录。

{files}
回报格式：
- 每个文件：写入 / 跳过（已存在）/ 失败；读回的最后一行
- tick.sh 测试输出：<原样贴出>
````

---

## 命令 1B：建主控（合伙人）和它的对话

````
任务：
1. 角色卡“主控”：没有就新建，已经有就把人设整段替换。人设正文就是下面分隔线之间的全部内容，整段照抄，不要改写。模型选我这里最强的那个。
2. 对话“主控·任务对话（勿删）”：没有就新建，并绑定角色卡“主控”；已经有就保留原来的。把它的 chat_id 写进 /sdcard/Download/Operit/core/config.txt，格式是：main_chat_id=<id>
3. 在这个对话里发一句：“自检：读 core/thread.json 和 core/ledger.md，告诉我里面写了什么，然后用一句话介绍你自己是谁。”

规则：不改任何其他角色卡和工作流。某一步失败两次就停下，把原始报错贴给我。

=====人设正文开始=====
{role}
=====人设正文结束=====

回报格式：
- 角色卡主控：新建 / 已更新 / 失败
- 主控对话 chat_id：
- 主控自检的原话：
````

---

## 命令 1C：让小满回去当朋友

````
任务：在角色卡“小满”现有人设的末尾，原样追加下面这一段。原来的内容一个字都不删、不改。

=====追加开始=====
### 新的分工（追加）
{xiaoman}
=====追加结束=====

如果陪伴打卡等工作流里有专门写“催求职 / 催投递”的提示语，只列出来给我看，先不要改。

回报：
- 追加：成功 / 失败
- 列出的催促类提示语（原文）
````

---

## 命令 2：建节拍工作流

````
任务：按下面的说明建一条新工作流“主控节拍”。如果已经有同名工作流，先读回它，只改有差别的部分。chat_id 用 /sdcard/Download/Operit/core/config.txt 里的 main_chat_id。不改其他任何工作流。

{workflow}

建好后：
1. 读回 JSON，核对 6 个节点、5 条连线都在，间隔是 300000，命令路径正确。
2. 手动触发一次：
   - 白天：应该看到主控排今天的计划，并把主控对话弹到屏幕上；
   - 21:30 以后：是晚总结。
3. 如果弹不出来，把 e_pop 换成 focus_hub_nav:check_in（message=“主控有新消息”，popup=true，speak=true），再试一次。

回报格式：
- 工作流 ID：
- 读回核对：
- 手动触发结果，以及弹窗是否真的出现（问我确认）：
- 主控说的原话（前 100 字）：
````

---

## 命令 3：试一轮（约 40 分钟，需要你本人参与，最好戴耳机）

````
任务：陪我试一轮完整的流程：开工 → 拆步 → 点名 → 追问 → 收尾记账。之后核对结果。

1. 我会在主控对话里说：“加一个测试窗口 t1，从 3 分钟后开始，持续 35 分钟，任务是〈我真实要做的一件小事〉，押金份额 10 元。”你确认 today_windows.txt 和 ledger.md 里都有 t1。
2. 等节拍自动开场。之后我会：
   - 回答两次；
   - 说一次“我在做”；
   - 请它帮一次忙；
   - 然后 20 分钟左右不理它，看它会不会来点名，或者换个小说法来追问；
   - 窗口结束时交东西给它核对。
3. 全部结束后，读 core/log/今天.jsonl、core/thread.json、core/ledger.md、core/state/tick.log，按下面的格式核对。

回报格式（每项写“是 / 否 + 证据”）：
- t1 开场是否自动弹出，有没有问“专注模式开了吗”，有没有只给第一步
- 我回答后，它有没有接着我的话给下一步（贴原话）
- “我在做”之后，是否 15 分钟内没有打扰我
- 有没有出现 checkin 或 nudge，说法是不是每次都不一样
- 求助时有没有先把事办了；log 里它做的部分是不是记成 who=ai
- 结束时有没有核对证据，ledger 里记的是挣回还是未挣回
````

---

## 命令 4：正式跑一天

````
任务：让主控节拍正式运行一天，第二天早上把结果核对给我。

1. 把 today_windows.txt 里 t1 那一行删掉；ledger 里 t1 那一行保留。其他状态文件不要动。
2. 保持“主控节拍”开启。
3. 如果陪伴打卡在我的工作窗口里也弹出来，跟主控重复打扰，就把它的间隔改成 7200000，改动记下来，以后能改回去。
4. 第二天早上 08:30 以后，读 core/state/tick.log、core/log/昨天.jsonl、core/report/昨天.md、core/ledger.md，核对后回报。

回报格式：
- 早计划：是否出现，我回了什么
- 每个窗口：是否按时开场，我回应了几次，点名和追问各几次
- 押金：挣回多少 / 一共多少
- 每日报告前 5 行
- 卡住或报错的地方（原样贴出）
````
"""

(HERE / "commands" / "给Operit的命令_免下载版.md").write_text(out, encoding="utf-8")
print(len(out))
