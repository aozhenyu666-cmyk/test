# 伙伴 v1 接线交付

日期：2026-10-28。分支：`claude/r21-strict-takeover`。版本：0.8.3-partner-v1-preview / versionCode 43。

本轮复用 Claude 的五个核心模块；未改动原有 partner 测试、离线判分、acceptAdvice 规则或发布工作流。没有连接手机，因此所有真机项为 **NOT_RUN**。

## 任务提交与调用链

| 任务 | 首次接线提交 | 文件与调用链 | 接线 | 真机 |
|---|---|---|---|---|
| G1 | `0b3e7e4` | `PetBrain.onUserInput → PetApp.partner → PartnerConversation.send → DialogueRules`；`DialogueStore` 单线程原子保存；收起走 away、重新打开走 resume；不再使用旧两轮 Conversation | WIRED | NOT_RUN |
| G5 | `a3a98ec` | `PartnerConversation.generate → PartnerResponder → ReplyContract.parse → ReplyCheck`；失败只重写一次；主动失败不输出，用户回应失败显示保守短句 | WIRED | NOT_RUN |
| G4 | `ebeb0b1` | `PetBrain.presenceTick → GovernorSignals/TopicCandidate → ProactiveGovernor → PartnerConversation.proactive → PartnerDelivery.confirm`；只有悬浮窗口已挂上、聊天页完成渲染回执或 TTS onStart 后才消费意图并记实际措辞 | WIRED | NOT_RUN |
| G7 | `a5dfba8` | `PartnerConversation.acceptMetadata → ThreadBook/ThreadBookStore`；确认卡 / 语音明确同意后才 `FactStore.recordSaid`；`MemoryEdits.forget → forgetFact` 关闭相关线并清理会话上下文 | WIRED | NOT_RUN |
| G2 | `2ad734b` | `ChatActivity → PetApp.partner`；首页“和同桌聊”和气泡“展开聊天”共用会话、消息来源、输入、语音与记忆确认卡 | WIRED | NOT_RUN |
| G3 | `f052b66` | `PetBrain.syncRiding → RidingVoiceLoop → Speech.onDone → VoiceInput → PartnerConversation.send → Speech`；通知栏停止 / 安静；受约束回合不能开启骑行；通话时让出语音 | WIRED | NOT_RUN |
| G6 | `07291bd` | `PartnerVision.capture → VisionTalk.prompt → VisionAnalyzer → VisionTalk.toCandidate/displayText`；手动问题从聊天页传入，原图仅在内存；完整观察不序列化；低/未知置信度不作为主动候选 | WIRED | NOT_RUN |
| G9 | `165eaf7` | 实际发出后 `retainQuotedMaterial → MaterialInbox + MATERIAL 话题线`；只留被引用的一句和来源应用，后续跟进可引用它 | WIRED | NOT_RUN |
| G8 | `296908a` | `MemoryTab` 展示事实的来源原话，以及 `partner.threads` 的重要度、下次跟进时间和进展；暂停一天 / 不再跟进 / 删除 | WIRED | NOT_RUN |
| G10 | `bcf40d6` | `Settings.partnerPerHour/partnerQuietUntil/partnerRiding`；陪伴设置与通知入口；诊断显示 Governor 小时次数、忽略计数、最近五条来源/锚点和拦截原因 | WIRED | NOT_RUN |

以上是各任务的首次提交。最终构建源码：**0ffdf01b6569d1593ff877c716ca93ab2929438b**；主要修复提交 `e5a6339`，导航修复 `0ffdf01`。后续修复对上述调用链统一生效。

## 额外修复

- `OwnLifePlanner` 保留不足 30 分钟的余量，秒级 tick 不再清零进度或每秒写盘；时钟回拨仍重设时间基准。
- 篇幅未知的搜索结果不再永久阻止下一次检索；保存真实搜索摘要，伙伴开口会明确说明未读取文章正文。
- 聊天页使用独立 Android 任务，从外部悬浮入口进入后可返回原应用，避免带问题看图回到设置页。真机导航仍待验证。
- 完整聊天页也有真正的渲染回执；页面关闭、输出失败、过期或被新会话取代的结果不计为已说。
- 用户输入可以取消正在准备的主动开口；收起聊天后，迟到回复不会重新弹出气泡。
- 语音识别加实例代数，旧识别的迟到回调不能消费新录音；完整聊天页不会清掉骑行循环持有的录音状态。
- 事实删除/更正后废弃在途回答并清理会话摘要，阻止从旧摘要再次提起；素材删除/失效也会使关联跟进线失效。
- 待回应的话题线从持久化的最近提问时间恢复，重启不会立刻重复同一个问题。自动留存的屏幕摘录不触发材料陪练。外部 AI 会话自动启动的默认值改为关闭，已保存的设置保留。
- 新伙伴对话所在的夜间批次保留已有长期档案，不从这些聊天自动新增档案；明确确认的事实仍走 FactStore。这是为了防止夜间整理绕过“要我记住吗”。混合批次中的其他新档案提取也会保守跳过。

## 自动化证据

- 原有 781 条测试保持不变；新增 `PartnerIntegrationTest` 14 条：回答契约与一次重写、失败显示不消费意图、重复回执幂等、零时刻回执、频率调整不丢历史、十轮与二十分钟恢复、确认事实遗忘后关闭持久化跟进、夜间记忆确认、秒级阅读累计、未知篇幅释放、完整画面观察不落盘。
- 单元测试：**795 条，0 失败、0 错误、0 跳过（PASS-L）**。
- 本地：JDK 17 / Android API 35；`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`。
- 最终源码 GitHub Actions [#129](https://github.com/aozhenyu666-cmyk/test/actions/runs/40641286807) 四项完整检查与预览发布均成功。独立下载包为 30,783,059 字节，SHA-256 `158ddc395d72f853b410e7fd5e3279c608ceccacb796ed957c032ceb9cae0c41`，版本与固定签名已复核。
- CI 与发布包信息见 [唯一验收表](../claude-r21-strict/ACCEPTANCE.md) 的伙伴 v1 小节。
- Android 测试 APK 仅完成构建，未运行到手机上；纯 Kotlin 测试通过不能证明 Android 的真实执行效果。

## 手机上这样试

1. 覆盖安装主线预览包，确认版本 0.8.3 / vc43。保留原 Key 和权限；在设置确认 DeepSeek 模型连接。首页进入“和同桌聊”，同一话题聊十轮；收起后二十分钟返回，检查是否接得上。
2. 设置 → 陪伴：默认每小时 20 次，睡眠时段默认 00:00–07:00。测试时确认不在静默时段且每日调用额度足够。正常刷一小时，记录次数、所聊具体点、打字/通话是否让出；不要把目标次数当成已实测次数。
3. 开骑行语音，戴耳机先在静止状态试十分钟，再测试锁屏、通话、耳机断开、系统回收和恢复。识别服务不支持后台/中文、TTS 数据缺失时查看诊断；连续技术失败会停止模式。
4. 在模型与看图设置单独配置视觉模型并开启上传。聊天页点“带问题看一眼”，回到上一页内容；检查完整细节与后续追问。视频/游戏可以按系统应用类别识别，也可在陪伴设置补充名单。对照真实画面判断是否说错。
5. 说一个明确目标或烦心事，核对“要我记住吗”内容；拒绝时不应新增事实。确认后在“我的 → 记忆”核对来源、删除；再次聊天和等待跟进都不应重提已删除的事。重要线默认六小时后可跟进，一般有意义的线默认一天后。
6. 原有严格守护、训练恢复和休息许可仍需按旧验收表单独真机验收。

## 限制

- 没有真实 Key 调用视觉 / 搜索服务，没有真机、没有执行耳机与关屏长测，也没有观察 UI 实际渲染效果。
- TTS onStart 是系统开始播放的回调，不能证明用户听到了；音量、输出设备和系统音频路由仍需真机验证。
- 每小时 20 次是调度目标。正在输入、通话、录音、睡眠、显式安静、训练或守护、预算用尽、无可用内容、未回应退避、模型失败都可能降低次数。
- Governor 的小时统计与说过的话账本是进程内状态；进程重启后重新累计。事实、话题线与文字会话有持久化。
- 仍为公开调试签名、debuggable 预览包。严格守护的动作效果回执 D02 仍未完成，不能声称长期监管已经可靠。
