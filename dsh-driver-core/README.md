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
2. **防发呆：** "Gemini 在说话"可以代替"你在说话"——它只在你说完之后才说。
   - **Gemini 刚说完一段、8–15 秒（随机）你还没接话**：这是最该推你的时刻，给一句"轮到你了，念给 Live：先别讲，就第3题问我一个二选一"。同一原因 90 秒内只推一次。
   - Live 那边 60 秒完全没声音，就判定你停了。第一次只给你一句**念给 Gemini 的话**："我停在第3题了，你问我一个一两个字就能答的问题。"念出来几乎不费力。Gemini 一接话，驾驶员就记为你已经开口。
   - 还是不说：降维成 A/B 二选一（六骰子：精简 / 预判 / 回想 / 联想 / 提问 / 体系），点一下就算。
   - 一直不回：同样按上面的两级升级。
3. **防假专注：** 你回给驾驶员的话会被判水位。秒回"选D"、没有依据，就踩刹车："滑过去了——你又在抓到一半就下结论。依据是哪个词？"这里的"老毛病"来自你写的 `profile.json` 和 fish-memory 里的记忆。
4. **不重复提问：** 新的一问和最近问过的 5 句字面太像，就换模板或换骰子；模型一直回同一句也会被换掉。
5. **分清"醒了 / 回了 / 想了"：** 点 A/B 只算"回了"，说出依据才算"有依据的思考"。会话结束报这个数，不拿聊天轮数充数。
6. **记得上次没做完的：** 被打断没接上的断点、没补上的依据、带"可能/好像"的结论，存进议程。下次开同一主题，第一问就是"上次停在【第7题】没接上，A 接着做 / B 先做新的"。连续 3 次选 B 就不再提。

再配合 [docs/03](docs/03-驾驶员提示词.md) 末尾那段 Gemini / ChatGPT 自定义指令，Live 那边每次回答也会用一个"一两个字就能答"的问题结尾。这样两边都往"高频、低成本互动"推。

## 一次 15 分钟锁定长什么样

`node plugin/bin/simulate.mjs` 的真实输出（没接模型，全部是模板题）：

```text
── 第一天 ──
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
01:30  Live　　（Gemini 说话 8 秒）
01:50  驾驶员　　　（Gemini 说完你没接）轮到你了。念给 Live：「先别讲，就第3题图形推理问我一个二选一。」
02:05  我　　　　　黑点数量每格加一
02:05  驾驶员　　　这个依据够下结论了吗？不够的话还缺哪个词？
02:10  Live　　（Gemini 说话 10 秒）
02:30  Live　　（Gemini 说话 8 秒）
03:38  驾驶员　　　念给 Live：「我停在第3题图形推理了，你问我一个一两个字就能答的问题。」 [level 1]
04:09  驾驶员　　　念给 Live：「我停在第3题图形推理了，你问我一个一两个字就能答的问题。」 [level 1]
04:30  我　　　　　（点「休息 3 分钟」）
04:30  放行　　　　休息到 07:30
07:30  拉回　　　　刚才停在【第3题图形推理】，不用重头想，只回我 A 还是 B 就行。  A:肯定 / 支持 / B:否定 / 排除 [level 1]
07:35  我　　　　　B
07:35  驾驶员　　　回到【第3题图形推理】：刚才卡住的是哪一步？说一个词。
18:00  驾驶员　　　时间到。一句话：这一段你确定了什么？（例：第3题排除C，因为题干说了X） [level 1]
18:20  我　　　　　第3题选D，因为左右对称且黑点数量递增
18:20  结束　　　　completed｜结论：第3题选D，因为左右对称且黑点数量递增

推进：醒 9 次｜回 5 次｜有依据的思考 3 次｜切屏 2 次｜接回 2 次

── 第二天：做到一半被电话打断 ──
00:00  驾驶员　　　这一段只钉一件事：第7题数量关系。你现在卡在哪一步？
00:20  我　　　　　（切去微信）
00:20  拉回　　　　刚才停在【第7题数量关系】，不用重头想，只回我 A 还是 B 就行。  A:肯定 / 支持 / B:否定 / 排除 [level 1]
00:25  我　　　　　（来电，点「紧急结束」）
00:25  结束　　　　stopped:call｜结论：（无）
议程里挂着：「第7题数量关系」

── 第三天：同一题回来 ──
00:00  驾驶员　　　上次停在【第7题数量关系】没接上。  A:从这接着做 / B:先做新的
00:03  我　　　　　A
00:03  驾驶员　　　接上了。回到【第7题数量关系】：它的依据是哪个词？
00:15  我　　　　　因为总数是 3 的倍数，所以排除 B
00:15  驾驶员　　　下一步要确认的是什么？说一个词。
议程状态：「第7题数量关系」已解决
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
| 插件状态机、水位、骰子、推进日志、提示词 | **PASS** | `cd plugin && npm test`：23 项全过，包括完整 15 分钟闭环、两级升级、休息拉回、沉默阶梯、模型超时兜底 |
| 第二轮：说完触发、唤醒冷却、随机插问、防重复、三级记录、跨会话议程 | **PASS（逻辑）** | `test/round2.test.mjs` 11 项；依赖的"Gemini 说完"信号**没有真机证据** |
| 插件入口（路由、鉴权、followup 出题、禁用工具、读 fish-memory 库） | **PASS（假宿主）** | `test/host.test.mjs`；**没在真实 DSH 引擎上跑过** |
| `DriverReflex.kt`（反射判定 + Live 推断 + 说完边沿 + 非 Activity 窗口过滤） | **PASS** | `android/jvm-check`：6 项 JUnit 全过 |
| 其余 Kotlin / Dart | **NOT_TESTED** | 没有 Android SDK，没编译进 fork |
| 真机：读包名、录音/播放推断、后台拉起、Live 后台继续 | **NOT_TESTED** | 见 [docs/02 §6](docs/02-状态机与协议.md#6-待真机核实全部-not_tested) 的 7 项 |

## 怎么装（在你的 fork 上）

1. 插件：把 `plugin/` 放到引擎能读到的位置，用 `cordis.patch.yml` 插入（方式同 `dsh-go-proactive`）。
2. Android：把 `android/kotlin/*.kt` 放进 `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/loop/driver/`，合并 manifest 片段，然后改三处：
   - `LoopRuntime.verifyNow()` 里调 `InterceptRuntime.onForeground(pkg)` 的地方，加 `DriverRuntime.onForeground(app, pkg, className)`（`className` 取最近一次 `TYPE_WINDOW_STATE_CHANGED` 事件的类名，用来排除弹窗和输入法；拿不到就传 null）；锁定时让 `InterceptRuntime.onForeground` 直接 return。
   - `MainActivity.configureFlutterEngine` 里加 `DriverChannel.bind(this, flutterEngine)`。
   - `SurfaceSlot.kt` 的 `VisibleSurface` 加 `const val OWNER_DRIVER = "driver"`（`DriverRuntime` 里已用同名常量）。
3. Dart：放入 `driver_channel.dart`，在 `app.dart` 里 `DriverRelay(targetOf: …).start()`，再加一个"开始锁定"按钮调 `startSession()`。
4. 配置：`POST /dsh-driver-core/v1/config {"brainSessionId":"<专用出题会话>","memorySessionIds":["<大肥鱼会话>"]}`；`POST /profile {"goals":[…],"habits":["抓到一半就下结论", …]}`。
5. 手机设置：无障碍、悬浮窗、忽略电池优化；小米还要开「后台弹出界面」「自启动」。

## 运行测试

```bash
cd plugin && npm test                 # 插件 23 项
node plugin/bin/simulate.mjs          # 打印一次锁定的时间线
cd android/jvm-check && gradle test -Pkotlin.compiler.execution.strategy=in-process   # Kotlin 反射 6 项
```
