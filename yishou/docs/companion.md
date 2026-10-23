# 连续音画陪练：基线与探针（T0 / T1）

核对日期 2026-10-23。这一版只做两件事：把基线核对清楚（T0），交一个能装到手机上验能力的探针（T1）。
没有改界面流程、没有接模型、没有动数据库。

## 1. 基线

| 对象 | 值 |
|---|---|
| 工作分支 | `ccr-a5172662-j1jcik` |
| 本次起点提交 | `a72a2f4b7a4326247ffe6c65b7503fc692c36ba3`（“0.22：反向能耗——想透一句就过，糊弄要被追问”） |
| `yishou-latest` 标签 | 同一个提交，说明它就是当前发布树 |
| 数据库版本 | 14（本次没有改表，因此没有迁移） |
| 未合 PR | 只有 #1（`codex/flexible-training-preview` → `ccr-a5172662-j1jcik`），它的 base 停在 `f240811d`，比当前分支头旧，本次没有基于它开发 |
| `link/` 目录 | 一手发布树里确实没有（与任务书一致），本轮也不需要 |

默认分支不是一手基线，别从默认分支切。

## 2. 本机能跑的命令（实测）

本机可以完整跑通测试和打包，不必等 CI。四条环境前提踩过一次坑，记在这儿：

```bash
# 1) JDK：默认 JAVA_HOME 指向一个不存在的目录，要显式指
export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot"
# 2) Android SDK：系统变量指向不存在的 E:\ANDROID_SDK，实际在 C:/Android/sdk（android-34 + build-tools 34.0.0）
export ANDROID_HOME="C:/Android/sdk"
export ANDROID_SDK_ROOT="C:/Android/sdk"
# 3) 工程目录和 GRADLE_USER_HOME 都不能带中文：
#    路径含中文时 AGP 直接拒绝（可用 -Pandroid.overridePathCheck=true 绕过），
#    但中文 GRADLE_USER_HOME 会让测试进程起不来（ClassNotFoundException: GradleWorkerMain）。
export GRADLE_USER_HOME="C:/wb/gh"

# 4) 依赖要联网，走自己的代理
./gradlew testMainlineDebugUnitTest --console=plain \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<端口> \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<端口>
```

| 命令 | 本次结果 |
|---|---|
| `./gradlew testMainlineDebugUnitTest` | 通过。基线 305 个用例；加完 T1 的纯逻辑后 331 个，0 失败 |
| `./gradlew assembleMainlineDebug assembleChaluDebug` | 通过，两个 APK 都出了（各约 13.5 MB） |
| `./gradlew kspMainlineDebugKotlin` | 偶发「拒绝访问」写不进生成目录，删掉 `app/build/generated/ksp` 后重跑即可 |

## 3. 文件地图

路径都相对 `yishou/app/src/main/java/com/yishou/app/`。

| 文件 | 行数 | 实际在干什么 | 本轮怎么用 |
|---|---|---|---|
| `look/ScreenLookService.kt` | 352 | 前台服务持有投屏授权，720 宽镜像，用户点「看一眼」才取一帧，图不落盘 | 复用它的授权流程与权限写法；新增投屏账本，避免和探针抢 |
| `scroll/ScrollService.kt` | 636 | 同上的镜像再加「连拍三帧拼一张」出题；60 分钟自动停 | 同上；三帧 burst 是 T3 关键帧筛选的现成参考 |
| `scroll/ScrollQuiz.kt` | 220 | 出题角度、判定、入池 | T4 复用出题角度与入池，输入换成带来源的音画窗口 |
| `partner/Controller.kt` | 398 | 纯逻辑会话控制器：阶段、轮次号、取消、没接阶梯、收线 | 演进现有控制器，不另建第二个竞争控制器 |
| `partner/PartnerVoice.kt` | 180 | 把控制器接到手机上：麦克风听、Speaker 说、结果写库 | 音频宿主起点；增加内录通道与资源仲裁 |
| `partner/PartnerVoiceService.kt` | 137 | microphone 类型前台服务，带「停」「结束并收线」 | 语音侧资源所有者，保留 |
| `partner/Director.kt` | 87 | 导演卡：六面、强度、本机兜底 | 兜底里现在含二选一，本轮视频陪练改成「缩小问题」 |
| `voice/PartnerApis.kt` | 64 | STT / 文字 / 流式文字 / 识图 / TTS 的接口与 HTTP 实现 | 扩能力协商；`RequestTag(sessionId, turnId)` 是过期的凭据 |
| `voice/Turns.kt` | 22 | `TurnGuard`：轮次号加一，晚到的旧结果一律丢 | 直接复用，视频场景再加 sourceEpoch |
| `voice/Sentences.kt` | 66 | 流式文字切句 | 直接复用 |
| `speech/VoiceInput.kt` | 220 | AudioRecord 录音 + VAD + 上传听写；也支持系统识别 | 麦克风侧照旧；内录是另一条路（见下） |
| `window/Speaker.kt` | 178 | TTS 接口 / 系统朗读，带代号防旧回调 | 直接复用 |
| `pool/`（Intake/Next/Pool） | — | 素材池：收进来、挑下一个 | T6 接来源片段，不另起记忆库 |
| `summary/LedgerFrom.kt` | 98 | 各表一行 → 账上一手；没回、跳过不记 | 新陪练记录按同样口径接入 |
| `data/Endgames.kt` | 81 | 残局与复习时间、唯一 sourceKey | 只有满足残局语义的才进，不硬凑 |
| `data/AppDatabase.kt` | 236 | Room，版本 14，迁移到 13→14 | T6 才动；动就升版本写迁移 |

## 4. 现有能力的实测缺口

在 `app/src/main` 全量搜过：**AudioPlaybackCapture、MediaSession、MediaController、NotificationListener 一处都没有**。
也就是说这三项不是「已有待改」，而是全新的，T1 必须先探针验证再谈接入。

投屏相关（MediaProjection）已经有两处各自独立实现：`ScreenLookService` 与 `ScrollService`，两边都自己
`getMediaProjection` + `createVirtualDisplay`。这就是任务书说的「两份资源争抢」的实情。

## 5. 旧规格与本轮要求的差异

`AGENTS.md` 与 `docs/voice-partner.md` 里有几条是上一轮的口径，本轮按新任务书执行，差异写在这里，
不用再回头问：

| 旧口径 | 本轮口径 |
|---|---|
| 截图只在用户明确要求时取一帧，图片不落盘 | 用户明确开启本次连续音画会话后，允许在会话内连续取关键帧；默认仍只在内存，`保留训练卡图片` 打开后仅保存被选入卡片的压缩图到私有目录，可删 |
| 语音伙伴没有「连续听内容」这回事，只听用户 | 新增「系统内录」通道，听的是目标 App 放出来的声音；麦克风通道照旧，两条通道分开记来源 |
| 没接的阶梯：缩短重问 → 二选一 → 记一笔 | 视频陪练改成：缩短重问 → 缩小问题 → 记一笔；二选一留给「诱」强度，不自动降级成选项题 |
| 看屏是独立功能，和语音伙伴互不干涉 | 投屏只有一份，看屏 / 刷着练 / 陪练三者走同一个账本，谁在用时别人不硬开 |

透明原则不变：权限都由用户手动给、图标和常驻通知可见、不隐藏、不模拟点击、不读无障碍节点、数据只在本机。

## 6. T1 探针交付了什么

新增包 `companion/`，两层分开：`core/` 是纯逻辑（有 JVM 测试），`android/` 才碰系统 API。

**纯逻辑（`companion/core/`）**

| 文件 | 干什么 |
|---|---|
| `Probe.kt` | 四项能力 × 六种结论；**没证据的「可用」自动降级成「未验证」** |
| `CaptureLease.kt` | 投屏账本：同一个时刻只有一个主人，别人占着要说清是谁 |
| `FrameCadence.kt` | 关键帧三道闸：和上一张一样 / 间隔太短 / 这一分钟够了 |
| `AudioSignal.kt` | 内录到底有没有声音；全零不能算「不支持」 |
| `PauseProof.kt` | 暂停判据：发指令 → 再看状态，看到暂停才叫暂停 |

**Android（`companion/android/`）**

| 文件 | 干什么 |
|---|---|
| `CaptureArbiter.kt` | 账本的进程内入口，`ScreenLookService`、`ScrollService`、探针共用 |
| `PlaybackCaptureProbe.kt` | AudioPlaybackCapture + AudioRecord，44.1k 单声道，实时报峰值与有效时长 |
| `ScreenFrameProbe.kt` | 一路 720 宽镜像，画面变了才留帧；旋转时宽高对调重建；授权被收回立刻释放 |
| `MediaControlProbe.kt` | 读活动播放会话、发暂停、700ms 后再读状态核实 |
| `MediaNotificationListener` | 只为拿到「读活动会话」的资格，不读通知内容 |
| `SelfTestPlayer.kt` | 自控播放器：放一段 220Hz 低音量提示音并挂自己的播放会话，用来先验暂停路径 |
| `CompanionProbe.kt` | 调度：一次只开一路投屏，观察 20 秒出结论 |

**入口**：设置 → 「陪练探针（调试）」→ 探针页。三个区块：音画观察、暂停与恢复、结论。

## 7. 必须知道的三个限制

1. **内录录不到一手自己的声音**。系统不把采集方自己的音频算进内录，所以自控播放器只能验「暂停」那条路径，
   内录一定要用你真正在看的那个 App 来验。界面上写明了，不装成验过。
2. **目标 App 可以禁止被采集**。这时读到的是全静音，探针只报「没有有效声音」，不报「不支持」。
3. **暂停别的 App 通常要通知使用权**。普通 APK 拿不到「活动媒体会话」；没开通知使用权时，探针直接说
   「没有可控制的播放会话」，给的是手动暂停这条路，不把「发过指令」说成「已经暂停」。

## 8. 真机验收步骤（本次未做，需要你在手机上走一遍）

1. 设置 → 陪练探针 → 开始观察 → 系统弹窗同意投屏。
2. 切到平时看视频的 App，放一段有声音的视频，让探针在后台观察 20 秒（探针页会显示读到的峰值）。
3. 回探针页看结论：「内录声音」应当是「可用」并写出读到几秒；如果显示「没有有效声音」，先确认那个 App 真的在出声、
   没被静音，再试一次。
4. 「关键帧」应当写出看到几帧、留下几帧；画面一直不动时写的是「看到 N 帧，画面一直没变」，这也算通路正常。
5. 暂停：先点「开」（自控播放器），再点「测暂停」，结论应当是「已暂停（核实过状态）」；
   然后点「去开通知使用权」，回来点「直接测你正在放的那个 App」，看是不是同样能核实。
6. 在探针观察期间去开「看屏」或「刷着练」，应当被拒绝并说明是谁占着投屏；反过来也一样。
7. 观察中途在下拉通知里点「停止投屏」，探针应当立刻收摊，结论里记「系统收回了投屏授权」，不留残余。

## 9. 明确的未验证项

- 目标手机型号、系统版本、具体视频 App：未指定，因此一条都没在真机上跑过。
- 目标 App 是否允许被采集（`allowAudioPlaybackCapture`）：未知，只能真机试。
- 内录在蓝牙耳机、来电、切歌、投屏旋转下的表现：未测。
- 20 秒观察期的耗电与发热：未测。
- 通知使用权在 vivo 上的入口是否好找：未测。

这些都不写进「已通过」，等真机结果再回填。
