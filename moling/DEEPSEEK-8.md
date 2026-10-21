# DeepSeek 第八批：发布稳定与真机验收收口

> 2026-10-21。用户现在要实际安装体验；先把下载、版本和反馈闭环做稳。
> 第四、五批（Codex）以及第六、七批（DeepSeek）已经合入，不要重做。
> 本批不新增大脑能力，不改两边 core，不改总控，不加权限。

## 1. 从哪里开始

仓库：`aozhenyu666-cmyk/test`。
工作分支：`ccr-b3c7616e-xlohkd`；不要用仓库默认分支。
从该工作分支的最新提交新建 `deepseek/moling-release-qa`；已有同名分支时先比较提交，保留已有工作。

先读根目录 `AGENTS.md`、`moling/CODEX.md`、`partner-blueprint/CONTEXT.md`，然后本文件、`moling/TESTING-QUICK.md` 和 `moling/TESTING.md`。
CODEX 前部的第四、五批施工说明是历史交接；以顶部的当前状态和本批范围为准。

交接时的 App 基线：`b8383112ab00b3934a209648aef4b3d30a569c3d`。
已有键盘适配、返回手势、重复点选保护；这些需要真机验证，不能当成待实现功能。

## 2. R1：修复共享下载包被旧任务覆盖的风险（先做）

文件：`.github/workflows/moling-android.yml`，必要时在 `tools/` 增加专门的发布辅助脚本。
不要动总控 workflow、签名文件、包名和 core。

已观察到：
- 最新源码 b838311 的 moling-android 第 54 次运行（39607863628）成功。
- 较旧源码 b298681 的第 53 次运行（39607814565）也成功。
- 检查时 moling-latest 却指向 b298681，标题是 0.12.53；按 tag 取 release 返回过 404，release 列表中资产地址出现过 untagged。
- 当前发布步骤是先删除 release 和 tag，再创建；没有发布串行控制，也没有最新提交校验。
日志已确认第 54 次发布在 13:28:44 UTC 完成，第 53 次却在 13:29:33 UTC 完成，旧包后写入。
补充检查：重跑第 54 次发布后，任务成功但 release 仍为 draft，官方 APK 直链返回 404；已用 GitHub API 将该 release 的 draft 设为 false，供用户先试用 0.12.54。发布收尾状态也需要检查。

实现要求：
1. 所有分支保留测试、打包和 Actions APK artifact。
2. 共享的 `moling-latest` 仅由工作分支 `ccr-b3c7616e-xlohkd` 发布；开发分支不覆盖用户正在试用的包。
3. 发布任务使用整个仓库内固定的 concurrency group 串行化。不要只按分支分组，也不要取消一个正在更新 release 的任务。
4. 取得发布锁以后、更新共享下载以前，核对本次 SHA 是否仍是远端工作分支 HEAD。旧运行明确记为“跳过过期发布”，不能覆盖最新包。
5. 不再先删除整条 release/tag。更新现有 release、替换同名 APK；第一次没有 release 时才创建。固定地址保持：
   https://github.com/aozhenyu666-cmyk/test/releases/download/moling-latest/moling.apk
   tag、release target、标题中的提交和实际 APK 必须对应同一次构建。
6. 下载 / APK 文件校验失败时立即失败，不要把现有可用包删掉。不要用无条件 `|| true` 吞掉发布错误。
7. 发布说明保留版本、完整源码 SHA、run 链接；附 APK SHA-256（可为说明文字或独立校验文件）。保留现有调试签名和覆盖安装能力。
8. 完成后用 API 核对 release 为 draft=false、tag 指向本次提交、资产是固定 tag 地址，并检查 APK 直链可取到文件。CLI 退出码为 0 不足以说明发布完整；草稿或 untagged 资产不能当作成功。
9. 不为修本批擅自改应用版本规则；当前 app 和 release 都应沿用 CI 的 0.12.N。

验证这些情形，给出实际结果或明确的待验证项：
- 工作分支最新构建：正常发布。
- 同一分支的旧构建晚完成：跳过发布。
- 开发分支：仍有 APK artifact，共享 release 不变。
- 已有 release / 首次无 release：分别更新 / 创建。
- 失败：不能误报成功或提前删掉现有 release。
可以对辅助脚本用 mock gh/git 验证这些分支；不要写只检查 YAML 字符串的空测试。
本分支的 Actions 只验证构建和“开发分支不发布”；最终正式发布要在审查合入工作分支后验证，不要为此提前覆盖用户下载。

## 3. R2：把交接文档中的现状写准确

仅做少量、明确的更正：
- `moling/CODEX.md`、`partner-blueprint/CONTEXT.md` 中不要再把已合入的第四至七批当作新任务。
- 连续对讲和 Notion 镜子已有 App 代码（例如 `app/mirror/`），应标为“已有实现，真机 / 服务配置待验”，不能写“完全未做”，也不能写“已经真机可用”。
- 保留历史设计和未做事项的来源；测试数量只写实际运行的结果，或注明是旧记录。
- `moling/TESTING.md` 的反馈接收人改为“发回当前协作会话，或附在本批问题记录”，不再依赖 Claude 余额。
- 不更改隐私、授权、总控铁律，也不扩展功能范围。

## 4. 测试和交付

- CI：`moling/ ./gradlew --no-daemon testDebugUnitTest assembleDebug`。本地没有 SDK 就明确写明，以 CI 结果为准。
- 不改 core；无需为文档更改跑全仓 Android 构建。
- 在 `moling/DEVLOG.md` 追加本批：修改、验证证据、未完成项、用户怎么验收。
- 分里程碑提交，推送分支，建立指向 `ccr-b3c7616e-xlohkd` 的 PR；不强推，不自行合入。
- 最后交付：分支 / 提交 / PR、CI 链接、发布行为变化、还需合入后验证的项目。

真机反馈尚未返回时，完成 R1、R2 即可。不要假设用户遇到了某个 UI bug，不要自行重写界面。
