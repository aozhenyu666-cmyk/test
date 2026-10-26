# 同桌 R21 — Claude 接管、严格跨 APP 守护与 APK 打包（唯一交接说明）

## 0. 接管规则（先读）
- 仓库：aozhenyu666-cmyk/test；项目目录仅 companion-pet/；应用包名 cn.companion.pet。
- **唯一工作分支：claude/r21-strict-takeover**；以固定提交 8fb3cc1e2bd30100a3ac0cc05c7d996634bb8bad 起步。不要继续提交到 deepseek/r21-proactive，也不要覆盖总控、一手、哨兵等其他工程。
- Claude 旧补丁参考：08f4dbe22a4b5819dada9e92e1f55d5c68abf2be；只读对比，按语义有选择移植；**禁止整文件覆盖**。
- DeepSeek 声称 D01–D07 全修好，以及 603 tests/0 failures 不能作为功能验收依据。未经重验的 PASS 调整为 PARTIAL / NOT_RUN。
- **本次目标是收敛现有代码、实现可观察的“跨 APP 严格守护 → 返回目标 → 稳定恢复”，然后交付一个 APK。** 不引入 R21-S1 计划触发、全新 AI 代理架构、哨兵统一控制协议、桌宠大改版；这些另案讨论。
- 不强制禁止卸载、不绕过系统安全界面，不阻断通话/紧急功能；保留清晰的暂停、结束守护和到期退出。

## 1. 源码审计已确认的现有能力（不是从零做）
- core/focus/FocusGuard.kt：FocusEnforcement.STRICT，离开目标 APP 后宽限、BLOCKING、有限自动返回、失败退避、到期/暂停/结束，纯状态机。
- core/focus/FocusController.kt：持久化契约、后台尝试启动目标 APP、1.2 秒后读取真实前台确认，过期不复活。
- overlay/FocusOverlay.kt：TYPE_ACCESSIBILITY_OVERLAY 或 TYPE_APPLICATION_OVERLAY 全屏拦截，包含“返回目标”“暂停 5 分钟”“结束守护”。
- a11y/PerceptionService.kt + PerceptionHub.kt：无障碍监听窗口切换、防抖读真实前台、传给 FocusController。
- service/PetBrain.kt：startFocusGuard() 以当前外部 AI 助手为 targetPackage 建立 5–180 分钟的显式契约；设置页已有 STRICT 开关；tick 每秒驱动 FocusController。
- a11y/AndroidDeviceController.kt + core/device/DeviceController.kt：授权包名范围内已具备其他 APP 的节点观察、点击、滚动、输入、返回等设备操作接口；**不等于跨 APP 中央指挥协议可靠**。
- **真实缺口**：PetBrain.onTrainingForeground() 在训练偏离时只 showBubble；没有接通同一全屏严格拦截/返回训练的用户流程。FocusGuard 当前保护的是已选择的外部 AI 目标，不能声称已经把同桌、一手、总控组成互控网络。
- 真机对 STRICT / 无障碍 / vivo/iQOO 后台拉起的效果仍然 NOT_RUN；在平台不支持、系统限制或权限缺失时必须显示失败状态，不能声称“已锁住”。

## 2. 实锤的残留缺陷：优先修，不能在新 APK 里冒充已通过
- D05 TrainingTimer.extendUntil()：当阶段阈值 60s、请求延长 90s 时，armedAt=now，仍在 60s 后催促。改为真正的有效时间截止；恢复/暂停不吞时间；补 89s/90s/91s 与恢复单测。
- D04 PetBrain.currentAnswerRevealed 只是内存布尔；ExtensionState 仅内存；startNewQuestionCycle() 未被调用。把泄题事实、已用宽限、到期信息、题组周期状态存到与实际使用一致的持久化快照，补跨重启和迁移题测试。
- D03 advanceToNextQuestion() 仍调用 outputArbiter.release(..., force=true)；endTraining 类似。只能 owner-matched release；会话仲裁必须覆盖冷启动，旧会话不能释放新所有者。
- D06 OfflineTraining.supported() 只比较内置题 ID；篡改版本/原句/量表也能套旧规则。恢复材料与版本一致性，逐题逐槽 rubric；“任何人”作为 LOCATE 答案可能正确，不可统一以字数/复述原句作为门槛。
- D06 PetBrain.confirmOfflineSemantics() 对本地 PASS 必须联网；无 Key 返回 RETRY。名为 CompletesOffline 的单测用 copy(uncertainty="", evaluationSource="SEMANTIC_CONFIRMED") 模拟确认，**不能作为断网完成的证据**。给可确定评分的题建立可信本地 PASS 方式；开放回答证据不足保留 UNCERTAIN/重试；不能为了离线打通而直接把任何关键词 PASS 都清空 uncertainty。第六道内置题 EXAMINE 也要明确支持边界。
- D02 recordActionReceipt() 在 PetBrain 中只有函数定义，没有实际调用链；lastActionAt 虽然记录但可靠性判断未检查动作新鲜度。将真实动作、独立效果观察、新鲜度接入能力状态，API 返回成功不冒充观察确认。
- U07 五题发券只调用 RestGrantStore.grant()，没有让 FocusController 实际暂停并到期恢复；且 grantId、cycleId 的单次语义必须跨崩溃有效。修复前不能宣称“完成五题得到可使用的休息”。
- D07 有些输出路径仍直接调用 overlay.showBubble；结束、切题之后的旧异步回调必须丢弃。请核对并给具体保护调用链。

## 3. Claude 本轮工作顺序：拒绝功能膨胀
### A. 冻结并梳理（单独提交）
基于上述 SHA 列出一次真实调用链和验收状态，不得以旧报告替代源码。只整理一份必要的差异清单；保持已有可工作的 FocusGuard，不做总项目重构。

### B. 修复影响可安装候选版的 P0（分批小提交）
先修定时/版本/仲裁/恢复，再修离线正确性、健康回执、五题休息，且附正反测试。每批说明修改文件、调用入口、测试结果。禁止为了提高通过率而删除失败测试/放宽版本保护。

### C. 收敛跨 APP STRICT 用户流程（独立提交）
- 用户明确开启 STRICT + 建立有期限的目标 APP 守护，离开允许范围后真正显示全屏拦截，再有界启动目标并验证 foreground package。
- “训练未完成却切到其他 APP”只在已有有效、用户授权的守护契约内联动严格模式；不能把一条气泡误称为全屏强制。确定返回的是训练的原题/原槽，不重复造新 session。
- 确保系统窗口、输入法、来电、锁屏、权限界面不被误判；覆盖悬浮窗显示失败、无障碍丢失、进程恢复、背景启动被拒与 3 次重试上限。
- 同桌设备操作能力保留独立授权白名单。**本轮不实现同桌远程管理总控/一手/哨兵**；如需跨应用命令，另起协议议题（明确包名、身份认证、授权、通信、审计）。
- 禁止持久不可退出的屏蔽、绕过安全设置或无授权操作其他应用。

### D. 构建与交付（独立提交与实际 APK）
- 执行测试、lint、assembleDebug、assembleDebugAndroidTest；每项记录真实命令与结果，不把 JVM 通过混同真机通过。
- 产出可下载的 debug APK（非正式发布），记录最终代码 SHA、版本、文件大小、完整 SHA-256、构建工作流/Artifact 链接。BUILD.json 的 sourceSha 必须真实且对应构建 APK 的源码提交，不能 PENDING_PUSH。
- 没有真实 Android 设备时，所有手动步骤标 NOT_RUN/DEVICE；有设备时提供型号、Android 版本、权限截图/Logcat 和逐步骤结果。
- 本次交付只更新一个 HANDOFF/ACCEPTANCE 实情表，不再生成多个版本互相矛盾的“全部通过”报告。

## 4. 六项最小用户验收（必须按顺序，逐项注明 LOGIC / DEVICE / NOT_RUN）
1. 启动同桌，显式开启 STRICT，选择外部 AI 目标，设 5 分钟守护，进入目标后记录前台已确认。
2. 切去娱乐 APP，过预设宽限出现 **实际全屏层**；返回目标后层消失、后台自动拉回至多规定次数。
3. 开始训练，输入有效短答；回答核验看本题 rubric，不靠复述长度；训练中离开可在授权条件下返回原题原槽。
4. 第三题杀进程后恢复：题组、已泄题、宽限次数、当前槽位不丢；重复/迟到回调不可加分或覆盖画面。
5. 真正断网完成 5 道可确定性判分的题（如果无法完成，诚实标 FAIL，不得模拟云确认）；第五题只发一次休息许可且使 FocusController 实际暂停。
6. 休息到期恢复守护；重启不重复发券、超时、无限延期；正常暂停/结束与紧急退出始终可用。

## 5. Claude 最终回复格式
- 分支与最终提交 SHA（以及每个阶段提交）
- 本轮改变了什么：具体文件/入口/调用链
- 上述六项：PASS-L / PASS-I / DEVICE-PASS / FAIL / NOT_RUN，附证据
- APK：具体 GitHub Actions artifact 或下载链接、文件哈希、大小、来源 SHA
- 仍存在的限制与下一轮候选事项（不得默默承诺“全完成”）

**请 Claude 在这个分支自主执行既定范围；不要每个小修复都重复问“要不要继续”。到遇到权限、系统 API 或真机条件无法满足时，记录阻塞并继续完成可以验证的部分。**
