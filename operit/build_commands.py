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
    file_block(CORE + "scripts/create_workflow.js", "scripts/create_workflow.js", "js"),
    file_block(CORE + "thread.json", "templates/thread.json", "json"),
    file_block(CORE + "today.json", "templates/today.json", "json"),
    file_block(CORE + "today_windows.txt", "templates/today_windows.txt"),
    file_block(CORE + "profile.md", "templates/profile.md", "md"),
    file_block(CORE + "成果.md", "templates/成果.md", "md"),
])
card = read("roles/司南.md")
role = card.split("## 人设正文", 1)[1].split("## 语音场景补充", 1)[0].strip()
voice = card.split("## 语音场景补充（粘贴到“其他内容（语音）”）", 1)[1].strip()
coach = read("roles/骰子教练.md").split("## 人设正文", 1)[1].strip()
import hashlib
hub_hash = hashlib.sha256((HERE / "dist" / "focus_hub-0.8.0.toolpkg").read_bytes()).hexdigest()
xiaoman = read("roles/小满_调整.md").split("### 新的分工（追加）", 1)[1].strip()

out = f"""# 给 Operit AI 的命令（免下载版 v3：司南）

不用下载任何文件，所有内容都写在命令里。
按顺序发：1A → 1B → 1C → 2 → 3 → 4；主控台升级是命令 5，可以在 2 之后任何时候发。一次只发一条，等 Operit 回报后再发下一条。
每条命令就是四个反引号之间的全部内容，整段复制。

---

## 命令 1A：写入文件

````
任务：用文件工具把下面 7 个文件原样写到手机上，一个字都不要改。

1. 先建好目录：/sdcard/Download/Operit/core/，以及它下面的 scripts/、log/、report/、summary/、state/。
2. 写入规则：
   - tick.sh 和 create_workflow.js 一律覆盖成新版；
   - thread.json：如果已经存在，并且里面有 last_user_at 这个键，就保留不动；否则用新模板覆盖；
   - 其他文件：已经存在就不覆盖。
3. 写完后把每个文件读回来，核对最后一行没有丢。
4. 如果手机上有名为“主控节拍”的旧工作流，把它停用（不要删除）。
5. 用 super_admin:terminal 运行下面这条命令，运行完删除 core_test 目录：
   sh /sdcard/Download/Operit/core/scripts/tick.sh /sdcard/Download/Operit/core_test

{files}
回报格式：
- 每个文件：写入 / 跳过 / 失败；读回的最后一行
- 旧“主控节拍”：已停用 / 不存在
- tick.sh 测试输出：<原样贴出>
````

---

## 命令 1B：建司南

````
任务：
1. 角色卡“司南”：用 operit_editor 的工具来建（没有就 create_character_card，已经有就 update_character_card 整段替换）：
   - character_setting：下面“人设正文”分隔线之间的全部内容，整段照抄，不要改写；
   - other_content_voice：“语音补充”分隔线之间的内容；
   - chat_model_binding_mode 设为 FIXED_CONFIG，chat_model_config_id 和 chat_model_index 指向 Grok 4.6（先用 list_model_configs 查；找不到这个模型就把模型列表告诉我，不要自己换）；
   - tool_access_enabled 设为 false，让她能用文件、浏览器、读屏这些工具。
2. 对话“司南·任务对话（勿删）”：没有就新建，并绑定角色卡“司南”。
   把它的 chat_id 写进 /sdcard/Download/Operit/core/config.txt，格式是：main_chat_id=<id>
3. 语音设置：
   - TTS 选一个女声；
   - 如果有“自动朗读 AI 回复”这类开关，打开它；
   - 告诉我改了哪些设置，以及每项原来的值，方便以后改回去。
4. 在这个对话里发一句：“自检：读 core/thread.json 和 core/profile.md，然后用两句话介绍你自己，以及你打算怎么和我一起干活。”

规则：
- 不改其他角色卡和工作流；
- 同一步失败两次就停下，把原始报错贴给我。

=====人设正文开始=====
{role}
=====人设正文结束=====

=====语音补充开始=====
{voice}
=====语音补充结束=====

回报格式：
- 角色卡司南：已建 / 失败；绑定的模型：
- 司南对话 chat_id：
- 语音设置改动：（每项写原值 → 新值）
- 司南自检的原话：
````

---

## 命令 1C：让小满回去当朋友

````
任务：在角色卡“小满”现有人设的末尾，原样追加下面这一段。原来的内容一个字都不删、不改。

=====追加开始=====
### 新的分工（追加）
{xiaoman}
=====追加结束=====

如果有哪条工作流专门写了“催求职 / 催投递”的提示语，只把它列出来给我看，先不要改。

回报：
- 追加：成功 / 失败
- 列出的催促类提示语（原文）：
````

---

## 命令 2：用官方接口建节拍工作流

````
任务：
1. 用 operit_editor:debug_run_sandbox_script 运行脚本，source_path 设为 /sdcard/Download/Operit/core/scripts/create_workflow.js。
   如果当前没有直接暴露这个工具名，先用 use_package 加载 operit_editor 再调用。
   脚本会按官方 Tools.Workflow 接口建立或更新“司南节拍”工作流。
2. 读回这条工作流，核对：
   - 6 个节点、5 条连线；
   - c_go 是 CONTAINS NEED=go；
   - e_ai 的 chat_id 等于 config.txt 里的 main_chat_id。
3. 手动触发一次：
   - 白天：应该看到司南排今天的计划，并且她的对话被弹到屏幕上；
   - 21:30 以后：应该是晚上回顾。
   如果弹不出来，把 e_pop 换成 focus_hub_nav:check_in（message=“司南有新消息”，popup=true，speak=true），再试一次。
4. 不改其他任何工作流。

回报格式：
- 脚本返回：<原样贴出>
- 读回核对：
- 手动触发：弹窗是否真的出现（问我确认）；我是否收到了回复通知；是否用语音念出来了
- 司南说的原话（前 100 字）：
````

---

## 命令 3：试一个工作段（约 50 分钟，需要我本人参与，戴耳机）

````
任务：陪我试一个完整的工作段，然后核对结果。

1. 我会在司南对话里说：“加一个测试段 t1，从 3 分钟后开始，持续 45 分钟，任务是〈我真实要做的一件事〉。”你确认 today_windows.txt 里已经有 t1。
2. 节拍开场后，我会正常干活：
   - 回答她几次；
   - 说一次“我在做”；
   - 请她帮一次忙；
   - 中间 20 分钟左右不理她。
3. 结束后，读 core/log/今天.jsonl、core/thread.json、core/成果.md、core/state/tick.log，按下面的格式核对。

回报格式（每项写“是 / 否 + 证据”）：
- 开场时，她有没有说清楚“你做什么、我做什么”
- 她有没有真的去做自己那份（查了什么、写了什么，贴出结果）
- 她有没有把我那一环放到她的下一步之前，并且明说她在等我
- “我在做”之后，15 分钟内有没有打扰我
- 点名和追问的说法，每次是否都不一样（贴原话）
- 结束时有没有核对我交出的东西，成果.md 里有没有新增一行
- 语音：她说的话我能不能听到，我说的话她能不能收到
````

---

## 命令 4：正式跑一天

````
任务：让司南节拍正式运行一天，第二天早上把结果核对给我。

1. 把 today_windows.txt 里 t1 那一行删掉，其他状态文件不要动。
2. 保持“司南节拍”开启。
3. 如果陪伴打卡在我的工作段里也弹出来，跟司南重复打扰，就把它的间隔改成 7200000，并把改动记下来，以后能改回去。
4. 第二天早上 08:30 以后，读 core/state/tick.log、core/log/昨天.jsonl、core/report/昨天.md、core/profile.md，核对后回报。

回报格式：
- 早计划：有没有出现，我回了什么
- 每个工作段：是否按时开场；我的启动用时；我回应了几次；她自己做了哪些事
- 成果：交出了几样（列出来）
- 使用说明书里新增的那条观察（原文）
- 卡住或报错的地方（原样贴出）
````
"""

out += f"""
---

## 命令 5：升级主控台到 0.8.0，建骰子教练

先把我发给你的 focus_hub-0.8.0.toolpkg 保存到手机的 Download 文件夹，再发这条。

````
任务：升级主控台，并建好骰子教练。

1. 在 /sdcard/Download/ 里找到 focus_hub-0.8.0.toolpkg，核对 SHA256 是否等于：
   {hub_hash}
   不一致就停下告诉我。
2. 先备份：把当前已安装的主控台（local.focus_hub，0.7.0）复制一份到 /sdcard/Download/Operit/backup/。找不到安装文件就告诉我它在哪。
3. 用 operit_editor:debug_install_toolpkg 安装：source_path 设为那个文件，reset_subpackage_states=false。
   装好后确认子包 focus_hub_dice（六骰子思维训练）已经启用，没启用就启用它；其他子包原来是什么状态就保持什么状态。
4. 角色卡“骰子教练”：用 operit_editor:create_character_card 新建（已经有就 update_character_card），character_setting 是下面分隔线之间的全部内容，模型用便宜、快的那个。
5. 打开主控台，确认：
   - 底栏是 今天 / 司南 / 骰子 / 会话 / 设置；
   - 设置页顶部有 系统 / 省流 / 督促 三个分页。
6. 调用一次 focus_hub_dice:roll，确认：
   - 手机收到了骰子通知；
   - 出现了一个“骰子训练·第1轮（自动清理）”对话，骰子教练在里面给了示范开头。
7. 提醒我：去骰子页点“建立「骰子节拍」工作流”，再打开“定时推送”，间隔自己选。

=====骰子教练人设开始=====
{{coach}}
=====骰子教练人设结束=====

回报格式：
- SHA256：一致 / 不一致
- 备份位置：
- 安装结果：版本号，以及各子包的启用状态
- 骰子教练：已建 / 已更新 / 失败
- 底栏和设置页：是否和描述一致（截图或说明）
- 骰子试掷：通知是否收到；对话标题；教练的原话（前 80 字）
````
""".replace("{{coach}}", coach)

(HERE / "commands" / "给Operit的命令_免下载版.md").write_text(out, encoding="utf-8")
print(len(out))
