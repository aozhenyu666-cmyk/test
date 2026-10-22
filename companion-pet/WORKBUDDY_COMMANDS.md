# WorkBuddy / Deepseek 执行指令：文字清洗与 Gemini 文本模板

> 日期：2026-10-22。把本文完整交给 WorkBuddy 中的 Deepseek。执行 W1、W2 并交付结果，不扩展为整款 App 开发。
> 仓库：https://github.com/aozhenyu666-cmyk/test
> 需求依据：用户提供的《伴生桌宠 V1.5 需求文档（双系统版）》与 `companion-pet/PLAN.md`。本文已包含本任务所需规则，无须访问外部 Claude artifact。

## 1. 直接给执行器的任务

你负责两个规模小、规则明确的纯 Kotlin 模块：屏幕文字清洗、Gemini 桥接文本拼装，以及对应 JVM 单元测试。请实际实现、验证并提交，不只输出方案。

这是一个自用 Android 桌宠。DeepSeek 负责主动轻聊，官方 Gemini App 负责深聊。你实现的是供上层调用的文字工具，不是无障碍服务或 Gemini 自动化。核心状态机和 Android 集成由 Claude 在额度恢复后完成。

先读本文件与 PLAN.md，再按 W1 → W2 → 验证 → 交接执行。没有 SDK、骨架尚未发布或远端无写权限时，继续能完成的代码和检查，把阻塞原因如实交接。不编造测试成功或 APK。

## 2. 分工、边界与共同约束

| 执行方 | 本轮工作 | 所有权 |
|---|---|---|
| WorkBuddy / Deepseek | W1 清洗、W2 模板、对应测试 | `core/text`、`core/bridge` |
| Deepseek Harness | H1 工程和 CI、H2 网络、H3 密钥存储 | 构建、最小 Activity、网络、密钥存储 |
| Claude | 核心行为、Android 功能、整合、真机验收 | 场景、节奏、服务、角色、交互、记忆、设置等 |

允许新增或修改的源码目录仅为：

```text
companion-pet/app/src/main/java/cn/companion/pet/core/text/
companion-pet/app/src/main/java/cn/companion/pet/core/bridge/
companion-pet/app/src/test/java/cn/companion/pet/core/text/
companion-pet/app/src/test/java/cn/companion/pet/core/bridge/
```

不要修改 Gradle、CI、Manifest、Activity、网络、密钥存储、其他 core 包或旧工程；不要自行增加依赖。使用 Kotlin/JDK 标准库和 H1 提供的 JUnit 4。不要创建另一套工程来绕过骨架依赖。不要提交真实屏幕文字、API Key、SDK 路径或构建产物。

以下是两份执行文档共同采用的原计划修正；本任务只落实与文字工具有关的部分，其余交给 Claude：

1. 窗口事件判断前台，滚动事件记录时间与来源；收到滚动事件不代表读取正文。
2. 排除名单与游荡判断先于读屏；命中后不读正文。其他候选场景准备开口时可按需读一次以判断长文，只有最终学习场景携带正文请求 AI。
3. 系统回收、划掉最近任务、用户强制停止分别处理；WorkManager 不保证随时重启前台服务。24 小时常驻须目标真机验证。
4. 150 tokens / 10 秒是探针参数；夜间整理使用独立参数。统一调度层管理日调用预算，网络层不自动重试。
5. skip 后一分钟重试仍检查安静时段、锁屏、排除名单与预算；停止或权限撤回取消待执行工作。
6. 夜间三个输出全部验证成功才提交，失败保留旧文件；删除记录、并发编辑和归档一致性由 Claude 实现。
7. Compose 占位形象只用于开发联调，完整 V1.5 仍需原创角色和要求中的动画。

## 3. 分支和开始命令

基准分支：`ccr-d35383d9-k0acmh`。
已审核的基准提交：`00df214ad5c9bee3a19c39991212262acc271c6f`。
你的工作分支：`pet-v15/workbuddy-utils`。

在自己的独立目录开始。下面命令适用于终端；Windows 使用 Git Bash 或 PowerShell 均可：

```bash
git clone --single-branch --branch ccr-d35383d9-k0acmh https://github.com/aozhenyu666-cmyk/test.git companion-pet-workbuddy
cd companion-pet-workbuddy
git cat-file -t 00df214ad5c9bee3a19c39991212262acc271c6f
git switch -c pet-v15/workbuddy-utils 00df214ad5c9bee3a19c39991212262acc271c6f
git status --short
```

已有工作区时先检查 `git remote -v`、`git status --short` 和当前分支；自己的同名分支已存在就继续使用，不重建、不 reset、不覆盖已有改动。不要和 Harness 共用同一目录。本文可以作为外部任务指令使用，代码分支以审核过的基准提交起步，不要求命令文档本身已经合入该基准。

可以马上编写 W1/W2；Harness 尚未发布 H1 时，构建暂时受阻是预期状态。先完成并提交纯工具代码，然后按第 6 节引入 H1。不要因为等待骨架而实现构建系统。

## 4. W1：文字清洗

包名：`cn.companion.pet.core.text`。对外接口固定为：

```kotlin
data class TextCandidate(
    val text: String,
    val inScrollableContainer: Boolean
)

object UnicodeText {
    fun count(text: String): Int
    fun truncate(text: String, maxChars: Int): String
}

object ScreenTextCleaner {
    fun clean(candidates: List<TextCandidate>, maxChars: Int): String
}
```

以上只是签名，需补全实现。所有字数统一按 Unicode 码点计算；这不等同于完整字素簇计数，不引入 ICU。`maxChars < 0` 抛出 `IllegalArgumentException`，等于 0 返回空字符串。截断不追加省略号。

清洗顺序固定：

1. 每条文字先把连续空白折叠为一个普通空格，并去除首尾空白。空白包括 `Character.isWhitespace` 或 `Character.isSpaceChar` 判定的字符。
2. 丢弃清理后长度 ≤ 3 码点的条目。
3. 按清理后的完整字符串去重。同一文字任一来源属于可滚动容器，就将合并条目标记为属于可滚动容器；保存首次出现位置用于稳定排序。
4. 可滚动容器条目优先；组内按文字码点数降序，同长度按首次出现位置排序。
5. 用一个换行符连接条目，最后截断至 `maxChars` 码点。换行符也计入额度。

探针调用方传 1200，桥接调用方传 600。输入为空或全部被过滤时返回空字符串。不得把无障碍节点、Android Context、场景识别、网络请求或磁盘读写放进此模块。

“忽略之前的指令”等文字也是素材，清洗器不执行、不改写为指令，也不声称能靠删除关键词防止提示词注入；提示词隔离由 Claude 的探针层负责。

W1 测试至少覆盖：空集合、纯空白、3/4 字边界、多类空白、去重时合并滚动标记、两级排序与稳定排序、1200/600 的边界、换行计数、中文与补充平面表情、零和负额度，以及含指令样式文字仍作为普通文本保留。

## 5. W2：Gemini 桥接文本

包名：`cn.companion.pet.core.bridge`。只复用 W1 的 `UnicodeText`，不定义全局 Scene 枚举，以免与 Claude 的状态机冲突。

```kotlin
data class BridgeContext(
    val timeText: String,
    val trajectorySummary: String,
    val isLearning: Boolean,
    val contextSummary: String,
    val excerpt: String,
    val hookText: String,
    val userInput: String
)

object GeminiBridgeTextBuilder {
    fun buildText(context: BridgeContext): String
    fun buildLiveOpening(hookText: String): String
}
```

时间、轨迹摘要由调用方提供，模块内不读系统时钟，不获取应用列表。`excerpt` 是调用方传入的已清洗文字；此处仍强制截断至 600 码点，不依赖上游保证。

文字深聊必须使用以下模板，换行使用 `\n`，保留字段顺序和中文标点，不增加标题或 Markdown 围栏：

```text
[来自我的桌宠 | {时间}]
最近轨迹：{过去 30 分钟的 App 切换与停留，一两句}
我在看的内容：{context_summary}
原文节选：{清洗后的关键段落，≤ 600 字}
桌宠刚才问我：「{hook_text}」
我想说：{用户的语音或文字；没有就写“我还没想好怎么说，你先接着它的问题聊”}
——
请像熟人一样直接接话，不要客套，不要复述以上信息，不要提任务或计划。如果我在看学习内容，带我一起拆解；如果我在走神，帮我把念头说清楚。
```

字段处理规则：

- 所有输入先去首尾空白；保留用户输入的内部文字和换行。
- `userInput` 空白时填入：`我还没想好怎么说，你先接着它的问题聊`。
- 学习场景：使用传入的 `contextSummary` 与最多 600 码点的 `excerpt`；正文为空就保留空字段，不编造内容。
- 非学习场景：无论传入值是什么，“我在看的内容”固定为 `当前不是学习场景，不提供屏幕内容`，“原文节选”留空。`contextSummary` 和 `excerpt` 两个输入均不得泄漏到结果中。
- 其他空字段保持为空；模板结果末尾不额外加换行。
- Live 开场白固定为 `我在看这个，帮我一起想想 {hookText}`；hook 为空时使用 `我在看这个，帮我一起想想`。模板只拼文字，不执行任何 Live 界面操作。

不要实现剪贴板、Gemini 包名查询、Intent、分屏、麦克风、自动点击、调用计数或文件存储。这些由 Claude 接入。

W2 测试至少覆盖：学习场景模板全文相等、空用户输入默认句、600/601 字边界和表情、非学习场景故意传入正文及摘要仍被隔离、学习场景读不到正文、用户内部换行、Live 有/无 hook。测试使用合成数据。

## 6. 引入骨架、验证与提交

先保存工具代码，避免带着未提交改动合并：

```bash
git add companion-pet/app/src/main/java/cn/companion/pet/core/text companion-pet/app/src/main/java/cn/companion/pet/core/bridge companion-pet/app/src/test/java/cn/companion/pet/core/text companion-pet/app/src/test/java/cn/companion/pet/core/bridge
git diff --cached --check
git commit -m "feat(pet): add text cleaning and Gemini bridge templates"
```

Harness 完成 H1 后会发布不可覆盖的标签 `pet-v15-h1`。只引入这个骨架检查点，不引入其仍在开发的网络/存储提交：

```bash
git ls-remote --tags origin refs/tags/pet-v15-h1
git fetch origin refs/tags/pet-v15-h1:refs/tags/pet-v15-h1
git show --stat pet-v15-h1
git merge --no-edit pet-v15-h1
cd companion-pet
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

标签尚不存在时，不执行后续 fetch/merge；继续完成工具模块并报告“等待 H1”。不要循环频繁请求远端。标签存在但内容不符合 H1 分工时停止引入并记录原因。若出现意外冲突，不改他人的构建或业务逻辑；保留冲突信息交接。

Windows PowerShell 将最后一条替换为：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

本地缺少 SDK 时，把分支推送后检查 H1 提供的 CI。JUnit 通过、lint 通过、APK 构建通过是三种独立结果。工具层测试不代表 Android 读屏、Gemini 启动或真机行为已经通过。

修正自己的测试问题后，用明确文件路径提交；在仓库根目录完成：

```bash
git diff --check
git status --short
git diff --stat 00df214ad5c9bee3a19c39991212262acc271c6f...HEAD
git log --oneline -8
git push -u origin pet-v15/workbuddy-utils
git rev-parse HEAD
```

合入 H1 后，分支总 diff 会包含 Harness 的基础文件，这是正常依赖；你的原创修改仍须局限在四个白名单目录。不要 push 到基准分支，不 force push，不提交 APK/缓存。如果 push 无权限，交付本地提交 SHA 与 `git format-patch` 产生的补丁文件，明确未推送。

## 7. 交接时间和输出格式

两小时左右进入交接检查；Claude 额度实际恢复时交付当时可验证的成果。它不是强制完工时限。未完成项如实列出，不用空实现冒充完成，不再开新范围。

最终回复请按下面格式，方便用户原样转给 Claude：

```text
执行方：WorkBuddy / Deepseek
仓库 / 分支：
HEAD SHA：
已引入 H1 SHA（或尚未引入）：
W1：完成 / 部分完成 / 未完成，说明
W2：完成 / 部分完成 / 未完成，说明
自己的修改文件：
验证：逐条列命令、退出码、测试数量或 CI 链接
未执行的检查及原因：
已知问题与假设：
是否已推送：
Claude 下一步：先整合 Harness，再合并本分支；将 TextCandidate 接到节点读取器，将 BridgeContext 接到用户触发的 Gemini 桥接。
```

Claude 的完整接手指令位于另一份 `DEEPSEEK_HARNESS_COMMANDS.md` 的最后一节。用户把两份执行结果一起交给 Claude 即可。
