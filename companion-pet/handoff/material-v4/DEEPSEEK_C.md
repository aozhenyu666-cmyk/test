# 给 DeepSeek：C 阶段命令

你在 `aozhenyu666-cmyk/test` 继续开发桌宠，只修改 `companion-pet/`。从 **`pet-v15/codex-material-review-v4`** 开始，保留本轮修复和全部 246 个测试，不要回到旧 `ae19ab9`。

先检查工作区并保留已有改动，用独立 worktree：

```sh
git fetch origin pet-v15/codex-material-review-v4
git worktree add -b pet-v15/deepseek-tools-ui-v4 ../pet-tools-ui-v4 FETCH_HEAD
```

进入新 worktree 后读取适用的 AGENTS.md（如果存在），以及 `companion-pet/handoff/material-v4/STATE.md`、`REVIEW.md`、`DEVICE_CHECKLIST.md`。检查 `ActionRunner`、`SpeechActionAdapter`、`VisibleMemoryReader`、`AndroidActionAdapters`、`MaterialInbox` 和来源可见性规则的现状，再写代码。

## C1：让用户真正能使用工具

独立提交完成一个可操作入口，沿用现有四页导航和视觉风格。可以从“设置”进入一页能力面板，不增加外部自动化权限。

- 入口提供：查记忆、看材料、保存念头、打开已配置的 AI App、朗读文字。使用中文动作名，展示必要参数和结果，不把执行 ID、枚举名或 JVM 细节放在默认用户流程里。
- 所有真实调用走 `PetApp.actions`。这是同步 API，必须在后台任务执行；不要在 Compose 组合体或点击回调里直接阻塞调用。
- 展示待执行、执行中及四态结果；失败/不可用显示短原因。只读取当前可见记忆，保留“我推测”标记；不回退到直接拼 profile + facts 绕开 VisibleMemoryReader。
- SAVE_NOTE 先让用户确认实际文字；OPEN_ASSISTANT 显示目标；SPEAK 遵守现有开关和系统可用性。“系统已接受播报”不写成“你已经听到了”。
- 点击后冻结参数，执行中防止重复提交。重试同一次写操作继续使用同一个 ID，修改参数应创建新 ID，不把同 ID 换参数当重试。
- 每个界面/操作有明确作用域。点击取消或离开工具页面时，只取消该作用域的在途 ID；不要靠全局 cancelAll 误取消别处的念头写入。
- 使用取消感知的 `execute(request, cancellation)` 接口；新 adapter 必须检查取消并注册必要的停止回调，不要只覆写旧接口然后声称效果可停止。
- 可使用 `PetApp.actionPlanner` 展示有依据的 dry-run，但先做“预览决定”，不从 timer/tick 自动触发写入、拉起应用或朗读。生产 planner 必须核对真实事件来源，不使用永远为 true 的 sourceAvailable。
- 当前进程身份记录有上限；写入的“只执行一次”不代表跨进程也成立。C2 完成前不得开启无人值守的自动写动作。

验收：用 fake adapter 做 UI/作用域测试，覆盖双击、重试、改参数、返回、旋转、取消、不可用、晚到结果。fake 成功只证明接线，不作为真实 App/TTS 的设备证据。设备没有连接时提供 instrumentation APK 与明确待测项。

## C2：补跨重启写动作账本

另一个独立提交实现持久化写动作记录，再将它接入当前执行器。

- 首批覆盖 SAVE_NOTE；OPEN_ASSISTANT/SPEAK 至少能保留“上次结果未确认”的状态，不在重启后自动重做。
- 持久化记录包含执行 ID、请求指纹、动作类型、准备/提交结果与必要的效果定位符。不要持久化 READ_MEMORY/READ_MATERIALS 的内容缓存，也不要记录完整 prompt、Key、Cookie 或私人聊天。
- 开始前先落盘准备记录。写入成功后记录可核对的结果；进程中断后可查出已提交或未确认，不能一律当成没做过重新执行。
- 当前 ThoughtNotes 是 Markdown 行格式，没有稳定 action ID。若增加索引或 ID，必须兼容旧格式；不能靠“正文看起来一样”猜是否已写，也不能破坏现有列表、删除和摘要逻辑。
- 明确幂等保存窗口、容量上限、过期 ID 的行为以及安全清理方式。容量满时不应悄悄丢去重记录后再次执行旧请求。
- 取消不等于回滚原子写入。若无法判断中断时是否已提交，展示“需要核对”，保留证据，不虚构未执行/成功结论，也不自动用新 ID 重试。
- 只读操作始终按当前删除和来源版本重新读取；不要为统一账本而恢复旧的私密内容缓存。

验收必须包含：重建执行器/进程恢复、准备后中断、落盘后回执前中断、同 ID 并发、同 ID 换参数、容量边界、只读遗忘后重试。使用确定性的故障注入与栅栏，不靠长 sleep 碰运气。

## 本轮不要顺带做

不接模型工具循环，不安装那六个参考插件，不做 B站/QQ 登录、公开评论、私信、任意 Shell 或无障碍写操作。自动取材与模型驱动放后续阶段；将来每次 HTTP 尝试都要先预留同一本 AtomicBudget，并限制最多 3 次模型尝试。

## 交付

使用 JDK 17 / Android SDK 35：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python tools/test_verify_upgrade.py
```

Windows 使用 `gradlew.bat`。保留所有原断言；不提交 local.properties、缓存、Key 或 Cookie。更新版本号、STATE、APK SHA-256。推送新分支，C1/C2 分提交，不合并或覆盖 Codex 分支。

没有设备时，升级验证交付代码、脚本和待测清单即可，明确写“未执行”。本地同签名 APK 检查不能替代用户手机的覆盖升级。原版签名来自另一台机器时，应在原签名环境构建更新，不卸载用户应用或清数据来让测试通过。
