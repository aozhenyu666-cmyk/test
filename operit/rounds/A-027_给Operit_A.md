# A｜A-027｜现在就装：后台 preview.3 + 核心对话台 v0.2.1，用户先用起来

**为什么现在装**：Codex 的 preview.4 还要一段时间。核心对话台已经能在 preview.3 上工作，先让用户用起来。已知限制：AI 接着问时，每一题只能试一次，失败后不能重试（C-1 修复中）。
**用户要做的**：约 10 分钟，第 4 步。
**交付目录**：`/sdcard/Download/Operit/acceptance/round1/A-027/`
**不做**：不冻结或解冻任何 App；不启用任何工作流；不改 S4/P4/Strict、名单和规则；不改小满的角色卡。

## 1. 下载并核对（机器步骤）

仓库是公开的，直接下载到 `/sdcard/Download/Operit/acceptance/round1/A-027/pkg/`：

```
基础地址 https://raw.githubusercontent.com/aozhenyu666-cmyk/test/ccr-6193e40d-cxxw8a/operit/

后台（Codex 交付的原文件）
  vendor/cognitive-continuity-0.3.0-preview.3/cognitive-continuity-0.3.0-preview.3.toolpkg
  vendor/cognitive-continuity-0.3.0-preview.3/cognitive-continuity-skill-0.3.0-preview.3.zip
  vendor/cognitive-continuity-0.3.0-preview.3/SHA256SUMS

核心对话台
  dist/zhukong-0.2.1.toolpkg
  dist/zhukong-skill-0.2.1.zip
  dist/SHA256SUMS
```

逐个核对 SHA256，与同目录 `SHA256SUMS` 中的值一致才继续；不一致就停止，回报实际值。

## 2. 安装后台 preview.3

- 先备份当前状态文件。
- 以同一包 ID `com.community.cognitive_continuity` 升级，启用 `cognitive_core` 和 `control_plane`。
- 同步它的 Skill，替换旧版，不要留两份。
- 读 `cognitive_core:status`，确认 release 为 `0.3.0-preview.3`，原有数据保留。
- 读 `control_plane:live_status`，记录 `capabilities.model` 是 true 还是 false。为 false 也继续，在回执里写明。

## 3. 安装核心对话台，建好「核心对话」

- 安装 `zhukong-0.2.1.toolpkg`，启用子包 `zhukong`，同步它的 Skill。
- 新建一个对话，标题设为「核心对话」。如果用户已经建好「主控」角色卡（设定见仓库 `operit/roles/角色卡说明.md` 第零节），就绑定它；没有就先不绑。
- 用 `zhukong:configure` 登记两个会话 ID：
  - `core_chat_id`：刚建的「核心对话」；
  - `companion_chat_id`：小满的会话（标题含"小满"的那个，完整 ID）。
- 调用 `zhukong:console_state`，确认 `backend` 为 `cognitive_core`、`managed` 为 true。
- 从侧边栏打开「核心对话台」并截图：中间应是对话区，旁边是面板。如果报错或对话区不显示，保留原文，改用工具箱里的「主控台（面板版）」继续。

## 4. 用户真实使用（请用户来做，约 10 分钟）

请用户依次完成：
1. 打开「核心对话台」，在面板的"现在"里写下现在真正要做的一件生活里的事，点"开始"。
2. 如果提示"去认知主控台开始"，就点那个按钮，在那里填同一件事，点"开始真实任务并请AI接续"，然后回到核心对话台。
3. 读问题，写下自己的回答，点"保存并继续"，看 AI 有没有接着问。
4. 离开 Operit，过一会儿再打开核心对话台，确认这件事、刚才的回答和下一个问题都还在。
5. 可选：点"找小满聊"，确认中间的对话切到了小满；再点"回到核心对话"。

你不能代替用户写回答，也不能代替用户点击。

## 5. 回执

开头 `A｜A-027｜阶段`。前四行依次写：
1. 用户写入了几条回答；
2. 其中几条被 AI 接着问了；
3. 嵌入的对话区是否正常显示；
4. `capabilities.model` 的值。

之后附原始证据：status 原文、截图、出错原文。
