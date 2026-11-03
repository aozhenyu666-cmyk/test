# dsh-driver-core · 大肥鱼主驾驶员

DSH Go（大肥鱼）的插件：你开着 Gemini Live / GPT Live 学习时，它负责两件 Live 自己做不到的事——**你切走了，把你拉回原断点；你不说话了，用最低成本的一步让你重新开口**。

## 先说清楚它能做什么、不能做什么

**做不到（技术上没有办法）：**

- **让 Gemini Live 主动开口。** Live 是谷歌 / OpenAI 的应用，外部插不进它的对话。
- **听到你和 Gemini 说了什么。** 驾驶员不录音、不读屏幕文字，只看系统"有没有在录音 / 有没有在播声音"。

**所以换了个做法：**

1. **防切屏：** 锁定的 15 分钟里，前台只允许 Live 类应用（加上 DSH Go、输入法、来电）。你一切到 B 站、回桌面或进设置，前台稳定 0.35 秒后立刻盖上一张断点卡（真机延迟待测）："刚才停在【第3题·黑点数量】，不用重头想，只回我 A 还是 B"。点了才回到 Live。
   - 第 2 次再切走或不回：全屏 + 震动 + 语音念题，每 20 秒重复一次，直到你给出一条有效回答，或者选"休息 N 分钟"。到点自动拉回同一个断点。
   - "紧急结束"永远可用（来电、急事），但记为主动结束，不算完成。
2. **防发呆：** "Gemini 在说话"可以代替"你在说话"——它只在你说完之后才说。所以 Live 那边 60 秒没声音，就判定你停了：
   - 第一次只给你一句**念给 Gemini 的话**："我停在第3题了，你问我一个一两个字就能答的问题。"念出来几乎不费力。Gemini 一接话，驾驶员就记为你已经开口。
   - 还是不说：降维成 A/B 二选一（六骰子：精简 / 预判 / 回想 / 联想 / 提问 / 体系），点一下就算。
   - 一直不回：同样按上面的两级升级。
3. **防假专注：** 你回给驾驶员的话会被判水位。秒回"选D"、没有依据，就踩刹车："滑过去了——你又在抓到一半就下结论。依据是哪个词？"这里的"老毛病"来自你写的 `profile.json` 和 fish-memory 里的记忆。

再配合 [docs/03](docs/03-驾驶员提示词.md) 末尾那段 Gemini / ChatGPT 自定义指令，Live 那边每次回答也会用一个"一两个字就能答"的问题结尾。这样两边都往"高频、低成本互动"推。

## 一次 15 分钟锁定长什么样

`node plugin/bin/simulate.mjs` 的真实输出（没接模型，全部是模板题）：

```text
00:00  驾驶员　　　这一段只钉一件事：第3题图形推理。你现在卡在哪一步？
00:02  我　　　　　选D
00:02  驾驶员　　　刚才滑过去了——你又在「抓到一半就下结论」。依据是哪个词？只说一个。
00:14  我　　　　　因为图形是对称的
00:14  驾驶员　　　下一步要确认的是什么？说一个词。
00:40  我　　　　　（切去 B 站）
00:40  拉回　　　　刚才停在【第3题图形推理】，不用重头想，只回我 A 还是 B 就行。  A:肯定 / 支持 / B:否定 / 排除 [level 1]
01:10  强提醒　　　刚才停在【第3题图形推理】，不用重头想，只回我 A 还是 B 就行。  A:肯定 / 支持 / B:否定 / 排除 [level 2]
01:18  我　　　　　（回到 Gemini）
01:20  我　　　　　嗯
01:20  拉回　　　　刚才停在【第3题图形推理】，不用重头想，只回我 A 还是 B 就行。  A:肯定 / 支持 / B:否定 / 排除 [level 2]
01:23  我　　　　　A
01:23  驾驶员　　　接上了。回到【第3题图形推理】：它的依据是哪个词？
01:40  我　　　　　黑点数量每格加一
01:40  驾驶员　　　这个依据够下结论了吗？不够的话还缺哪个词？
02:10  驾驶员　　　这个依据够下结论了吗？不够的话还缺哪个词？ [level 1]
02:40  强提醒　　　这个依据够下结论了吗？不够的话还缺哪个词？ [level 2]
03:00  强提醒　　　这个依据够下结论了吗？不够的话还缺哪个词？ [level 2]
03:10  Live　　（Gemini 开口了）
04:10  驾驶员　　　念给 Live：「我停在第3题图形推理了，你问我一个一两个字就能答的问题。」 [level 1]
04:30  我　　　　　（点「休息 3 分钟」）
04:30  放行　　　　休息到 07:30
07:30  拉回　　　　刚才停在【第3题图形推理】，不用重头想，只回我 A 还是 B 就行。  A:肯定 / 支持 / B:否定 / 排除 [level 1]
07:35  我　　　　　B
07:35  驾驶员　　　接上了。回到【第3题图形推理】：它的依据是哪个词？
18:00  驾驶员　　　时间到。一句话：这一段你确定了什么？（例：第3题排除C，因为题干说了X） [level 1]
18:20  我　　　　　第3题选D，因为左右对称且黑点数量递增
18:20  结束　　　　completed｜结论：第3题选D，因为左右对称且黑点数量递增

推进统计： {
  generated: 11,
  displayed: 10,
  replied: 7,
  advanced: 5,
  resumed: 2,
  interruptions: 2,
  displayedUnproven: 0,
  advanceRate: 0.45
}
```

接上模型后，追问会结合你的死穴和记忆来写，模板只是兜底。模型不可用时整个闭环照样能跑。

## 目录

| 位置 | 内容 |
|---|---|
| [docs/01-复用接口清单.md](docs/01-复用接口清单.md) | 能直接复用的 Hook 点（DSH 引擎、speak-up、fish-memory、你 fork 里的 Android 拦截与主动内核）及冲突 |
| [docs/02-状态机与协议.md](docs/02-状态机与协议.md) | 会话状态机、带宽水位判据、升级规则、数据结构、HTTP 协议、手机端权限与事件、真机待核实清单 |
| [docs/03-驾驶员提示词.md](docs/03-驾驶员提示词.md) | 三层驾驶员提示词模板、一次真实渲染、兜底规则、贴给 Gemini/ChatGPT 的设置 |
| [docs/04-能力研究.md](docs/04-能力研究.md) | 下一轮能力研究：读 Mika、Open-LLM-VTuber、closepaw、Orbit、ScreenSeek、promptfoo 源码后的可复用模式、五项能力设计、性价比前三和最小方案（未实现） |
| `plugin/` | DSH 宿主插件（零依赖 ESM）：`lib/core/` 纯逻辑，`lib/index.js` 路由，`lib/brain.js` 借 agent 出题 |
| `android/kotlin/` | 放进 fork `app/android/.../dsh_app/loop/driver/` 的 Kotlin：反射、音频推断、断点卡、悬浮小卡、通道 |
| `android/dart/driver_channel.dart` | 放进 fork `app/lib/driver/` 的 Dart 中继 |
| `android/AndroidManifest.snippet.xml` | 要合并的权限、`<queries>`、Activity 声明 |
| `android/jvm-check/` | 只编译、测试 `DriverReflex.kt` 的小工程 |

## 状态

| 部分 | 状态 | 证据 |
|---|---|---|
| 插件状态机、水位、骰子、推进日志、提示词 | **PASS** | `cd plugin && npm test`：12 项全过，包括完整 15 分钟闭环、两级升级、休息拉回、沉默阶梯、模型超时兜底 |
| 插件入口（路由、鉴权、followup 出题、禁用工具、读 fish-memory 库） | **PASS（假宿主）** | `test/host.test.mjs`；**没在真实 DSH 引擎上跑过** |
| `DriverReflex.kt`（反射判定 + Live 推断） | **PASS** | `android/jvm-check`：4 项 JUnit 全过 |
| 其余 Kotlin / Dart | **NOT_TESTED** | 没有 Android SDK，没编译进 fork |
| 真机：读包名、录音/播放推断、后台拉起、Live 后台继续 | **NOT_TESTED** | 见 [docs/02 §6](docs/02-状态机与协议.md#6-待真机核实全部-not_tested) 的 7 项 |

## 怎么装（在你的 fork 上）

1. 插件：把 `plugin/` 放到引擎能读到的位置，用 `cordis.patch.yml` 插入（方式同 `dsh-go-proactive`）。
2. Android：把 `android/kotlin/*.kt` 放进 `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/loop/driver/`，合并 manifest 片段，然后改三处：
   - `LoopRuntime.verifyNow()` 里调 `InterceptRuntime.onForeground(pkg)` 的地方，加 `DriverRuntime.onForeground(app, pkg)`；锁定时让 `InterceptRuntime.onForeground` 直接 return。
   - `MainActivity.configureFlutterEngine` 里加 `DriverChannel.bind(this, flutterEngine)`。
   - `SurfaceSlot.kt` 的 `VisibleSurface` 加 `const val OWNER_DRIVER = "driver"`（`DriverRuntime` 里已用同名常量）。
3. Dart：放入 `driver_channel.dart`，在 `app.dart` 里 `DriverRelay(targetOf: …).start()`，再加一个"开始锁定"按钮调 `startSession()`。
4. 配置：`POST /dsh-driver-core/v1/config {"brainSessionId":"<专用出题会话>","memorySessionIds":["<大肥鱼会话>"]}`；`POST /profile {"goals":[…],"habits":["抓到一半就下结论", …]}`。
5. 手机设置：无障碍、悬浮窗、忽略电池优化；小米还要开「后台弹出界面」「自启动」。

## 运行测试

```bash
cd plugin && npm test                 # 插件 12 项
node plugin/bin/simulate.mjs          # 打印一次锁定的时间线
cd android/jvm-check && gradle test -Pkotlin.compiler.execution.strategy=in-process   # Kotlin 反射 4 项
```
