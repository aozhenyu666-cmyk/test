# 给 Operit AI 的指令：判断官（G2）失败诊断

> 用法：把"指令正文"整段复制给 Operit AI。本轮**只读**，不写任何文件。
> 背景：`G2_Judge_Flow` 自 2026-09-23 00:06 起再没保存过判断；`judge_save2.sh` 没有任何 REJECTED 记录，说明失败发生在"交给判断官"（`extended_chat:chat_with_agent`）或"写原文"（`create_file`）这一步。本轮要拿到失败原文。

---

## 指令正文

本轮只做只读诊断，共 4 步。不写文件、不触发或修改任何工作流、不向任何会话发消息、不调用 `app_suspender`。每一步照原样执行，输出原文，不做分析。

**第 1 步：G2 最近的执行日志。** 用 `super_admin:shell` 执行：

```sh
cd /sdcard/Download/Operit/workflow/_execution_logs/b3b65845-74ad-4834-9806-73d859775284 && ls -lt | head -6 && for f in $(ls -t | head -2); do echo "=== $f"; tail -c 4000 "$f"; echo; done
```

**第 2 步：角色卡。** 调用 `extended_chat:list_character_cards`。只报告：卡片总数，以及名字里含"判断"两个字的卡片的完整名字和 id。

**第 3 步：判断官会话。** 调用 `extended_chat:find_chat`，参数 `query` = `28f6fbb9-e08c-4536-8500-60cde7647094`，`match` = `exact`。只报告：是否找到、标题、绑定的角色卡名字、消息数量（如果返回里有）。

**第 4 步：时区。** 用 `super_admin:shell` 执行：

```sh
date; date -u; getprop persist.sys.timezone
```

### 输出格式

按第 1～4 步分四段，每段贴原文或按要求报告，不要写其他内容。
