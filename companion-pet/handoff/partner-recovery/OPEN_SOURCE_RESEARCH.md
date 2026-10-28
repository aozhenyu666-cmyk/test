# 开源调研与同桌修复对照

日期：2026-10-28。对应 0.8.5-partner-recovery-preview / versionCode 45。

用户反馈是“手机上的伙伴不会主动说话、模块没有串起来、悬浮交互不好用”。本轮同时检查参考项目和同桌实际运行路径。阅读源码不是运行验收，构建通过也不证明手机上能长期陪伴。

## 1. 已核对的项目与可采用的机制

以下锁定具体提交。没有安装、运行这些外部项目，没有导入其账号、人格、音色、模型或平台连接。采用的是机制，新增 Kotlin 实现独立编写。

| 项目与源码证据 | 可借鉴的内容 | 同桌本轮处理 |
| --- | --- | --- |
| [SurfCore：目的引擎](https://github.com/Mieksido/surfcore/blob/d7f6b33d57b1667508c48206cbcc455815d6d336/src/purpose-engine.js)、[人格隔离](https://github.com/Mieksido/surfcore/blob/d7f6b33d57b1667508c48206cbcc455815d6d336/src/character-persona.js) | 目标归属、证据、置信度、反馈；人格切换时隔离异步结果 | 复用现有话题与投递确认。本轮不声称已移植完整目标假设引擎；后续补话题归属与精确反馈关联 |
| [dsh-bili-whale：调度器](https://github.com/Alterssss/dsh-bili-whale/blob/8b0009f8ce2ebdc421afcb2e727b19ac18912ec6/lib/scheduler.js) | 持久化绝对计划；单任务执行；无新消息不算失败；失败才退避 | 主动节奏、已展示时间、无回应次数与失败重试持久化；安静原因不增加失败计数 |
| [dsh-world：生活运行时](https://github.com/chenkezhen480/dsh-world/blob/27b386fd3727c440d5eb0109f2b9f76d91dc0303/src/life/runtime.ts)、[通知出口](https://github.com/chenkezhen480/dsh-world/blob/27b386fd3727c440d5eb0109f2b9f76d91dc0303/src/life/notify.ts) | 闹钟状态与实际送达分离；恢复后继续处理；故障有明确状态 | 主动生成、等待回执、成功与失败分开显示；继续沿用实际绘制/语音回执后消费意图 |
| [dsh-whale-sense：事件接线](https://github.com/pairun325/dsh-whale-sense/blob/2da058ab0e8659f8e6a0083e624744fd9a48f82e/lib/index.js)、[本机变化检测](https://github.com/pairun325/dsh-whale-sense/blob/2da058ab0e8659f8e6a0083e624744fd9a48f82e/helper/sense.py) | 低成本画面差异检测先于模型；冷却、上限、状态可查询 | 自动看图先比较 16×16 灰度网格；静态画面不上传/不调视觉模型；手动问题不受变化检测限制。截图仍只在内存使用 |
| [Companion-AI-Memory-Context-Token-Optimization：设计](https://github.com/ARCHEON999/Companion-AI-Memory-Context-Token-Optimization/blob/2e67caac992d741c2a074cf90ff1a34f3764623f/docs/design.md)、[连续性](https://github.com/ARCHEON999/Companion-AI-Memory-Context-Token-Optimization/blob/2e67caac992d741c2a074cf90ff1a34f3764623f/docs/persona-continuity.md) | 稳定人格、近期状态、相关记忆、冷档案分工；限制真正发出的上下文；避免助手复述给记忆加热 | 接上相关召回，移除“最近12条事实”路径；限制请求中的记忆与历史字符量；注入角色状态与未完话题。该仓库主要是待实现设计，没有可直接安装的引擎，也没有实测省 token 百分比 |
| [Coopanion：持久闹钟](https://github.com/Pal-AI-Lab/Coopanion/blob/16166c5664ff7efa82b3de7f8be5a76d97ccffc6/core/alarms.ts) | 自己设置的下次唤醒跨重启存在；用户能取消；过期唤醒有时间来源 | 本轮保存主动重试时间；完整的“自己决定下一次想做什么”尚未实现，不能把普通重试叫自主生活 |
| [AIRI：角色协调器](https://github.com/moeru-ai/airi/blob/61bda9be551fa7c0164f15ab7e683c08ce3bc82b/packages/stage-ui/src/stores/character/orchestrator/store.ts)、[事件到动作](https://github.com/moeru-ai/airi/blob/61bda9be551fa7c0164f15ab7e683c08ce3bc82b/packages/core-agent/src/agents/spark-notify/agent.ts) | 事件先排队、再选回应；事件ID与动作ID关联；重试、优先级、输出出口分离 | 保留同桌已有输出所有权；新增统一可见进度与人工触发同一路径；完整事件队列与目的仲裁列为后续，不在 PetBrain 继续堆另一套循环 |
| [Open-LLM-VTuber：记忆与语音回复](https://github.com/Open-LLM-VTuber/Open-LLM-VTuber/blob/992309c0aa19845960228f880013d4685fde93b5/src/open_llm_vtuber/agent/agents/basic_memory_agent.py) | 记忆恢复、打断状态与语音输出分段有独立接口 | 系统TTS状态可见；真实 onStart/onDone 驱动桌宠嘴部动作。没有移植其完整流式语音/VAD系统 |
| [mem0：记忆操作](https://github.com/mem0ai/mem0/blob/26cdefb46600c0fa5953c4b5ee5003c125539fc4/mem0/memory/main.py) | 检索、更新、删除与历史是明确操作；接口失败不等于“没有记忆” | 复用 FactStore 更正/忘记与 EventVisibility，不接外部记忆云、不并行建立第二套事实真相 |
| [MemoryOS：分层检索](https://github.com/BAI-LAB/MemoryOS/blob/587ed7755c7aed179965792830ff1b5ad9a6fa92/memoryos-pypi/retriever.py) | 短/中/长期记忆分工，按当前问题检索后组合 | 当前先利用现成关键词/中文二元组召回；向量检索、热度迁移需要单独评估，不能宣称已有 |
| [Letta 当前源码：记忆约束](https://github.com/letta-ai/letta-code/blob/5dea67021b99810707ff753db35e4534f70ff1fd/src/agent/memory-constraints.ts) | 活跃记忆有字符与只读约束；内存投影不等于无限堆文件 | 给实际发送的伙伴上下文设限。注意旧 letta 仓库 README 已将活跃开发指向 letta-code，不把历史服务端当成当前架构 |
| [肥鱼语音：后端说明](https://github.com/Esmerubphire/astrbot_plugin_feiyu_voice/blob/76774b499f7d306454562d56348dd63399b6d7e8/README.md)、[分发声明](https://github.com/Esmerubphire/astrbot_plugin_feiyu_voice/blob/76774b499f7d306454562d56348dd63399b6d7e8/NOTICE.md) | TTS、RVC、播放渠道是不同层；服务任务成功也不等于用户听到 | 本轮修系统TTS与状态，不安装GPU服务、不集成音色。该发布没有为自编源码授予统一许可，不能直接按MIT复制；音色授权也不能从“训练获许可”推成所有用途均获许可 |
| [dsh-pet-android：Android悬浮服务](https://github.com/Tony-Chen-Stark/dsh-pet-android/blob/eca369a4318766e057d2b62d9db5bc1447ab62e8/app/src/main/java/com/dshpet/android/PetService.kt)、[能力边界](https://github.com/Tony-Chen-Stark/dsh-pet-android/blob/eca369a4318766e057d2b62d9db5bc1447ab62e8/README.md) | 手机贴边、菜单、可见区域夹取和错误诊断；动画状态与触摸反馈 | 补充Android专项参考。该项目明确没有AI聊天，内置320句台词不能当主动思考；本轮没有使用其受上游素材条款约束的动画 |
| [Dollhouse：手机气泡布局](https://github.com/Farewell-coder/Dollhouse/blob/1c261360688dff259c78b29a1b9c71e8e4f10e97/app/src/main/java/com/dollhouse/app/pet/PetBubble.kt)、[产品入口](https://github.com/Farewell-coder/Dollhouse/blob/1c261360688dff259c78b29a1b9c71e8e4f10e97/README.md) | 气泡布局从角色状态分离；贴边时按窗口与屏幕交集排版；简短入口与完整聊天共享会话 | 作为悬浮交互后续改造参考；同桌本轮独立实现边界限制、滚动与分层菜单，没有复制GPL源码或角色素材；多ROM体验仍待实测 |

用户给出的网盘、飞书页面不作为执行脚本或二进制来源。本轮使用对应公开仓库核对实现。没有证实“AI零”指一个特定项目；按当前手机悬浮桌宠修复，不冒认已识别该项目。

## 2. 同桌确认的问题与本轮落地

| 问题 | 原来为什么影响体验 | 修复与验收方式 |
| --- | --- | --- |
| 正常聊目标也被禁词拦截 | PartnerResponder 与 deliver 继续调用旧 ContentGuard；“计划/任务/自律/效率”等正常词使整条输出被拒 | 新伙伴路径使用 PartnerLanguage，保留反施压检查；HTTPS集成测试验证包含“复习计划”的合法输出通过，非法锚点等原规则仍保留 |
| 模型失败没有清楚原因 | HTTP、额度、格式错误最终只剩一个模糊提示 | API失败保留 CallDiagnosis；首页、聊天页、设置显示当前阶段；诊断报告包含安全的错误结论 |
| 角色还在动，调度可能停了 | ticker 在 tick 后才排下次；异常可阻断排程；只依赖屏幕广播 | finally 保证排下次；比对真实亮屏/锁屏状态；保留通话、训练、守护等保护 |
| “重新唤出”没有重新显示 | 已存在服务只刷新通知；悬浮窗标志可能与实际 attachment 不一致 | 服务命令调用 reveal；检查实际 attachment 并重挂；每5秒检查一次角色位置/挂载 |
| 主动节奏重启即丢 | Governor 的实际展示时间和无回应次数只在内存 | proactive.json 保存节奏、重试和屏幕内容指纹；成功输出才计数，失败重试最多退避15分钟 |
| 静态屏幕反复占用候选/模型 | 每次重新采样给同一句话新的观察时间；自动图像没有变化判断 | 实际展示后记录内容指纹；自动静态画面先本机过滤，手动请求可检查同一页 |
| 记忆存了却接不上 | 新伙伴路径只读最新12条事实，没有调用已有相关检索 | PartnerContext 调用 MemoryRecall，包含真实来源ID；测试25条无关事实后仍找到旧相关事实，忘记后不再返回 |
| 状态与聊天分离 | 角色状态没有进入新回复请求 | 角色状态、当前话题与最近对话一起投影；用户回应更新表现状态；不把随机动画状态当真实经历 |
| 总结/解释被统一短句规则妨碍 | 所有回复140字、禁止总结开头与列表 | 用户明确要求总结/解释/建议时使用对应规则；骑行仍保持70字上限；闲聊的反套话检查保持 |
| 悬浮菜单和长内容难使用 | 所有功能堆在气泡，长内容可超过屏幕，位置未完全夹在安全区 | 普通气泡“回复/展开聊天/更多”；菜单可滚动；长内容滚动、按钮44dp、位置按安全区限制；角色显示简短状态，嘴部跟随真实TTS播放 |

## 3. 当前明确没有完成的能力

- 没有真实模型Key或已连接Android手机，无法证明本包在用户手机上已主动开口。没有KVM/模拟器运行环境，设备验收仍是NOT_RUN。
- 没有把开源项目的宣传效果当成独立实测，没有宣称模型出现真实感情或意识。
- 画面变化只是触发条件，不证明内容重要；灰度阈值仍需手机实测校准。截屏权限、排除应用与上传开关继续生效。
- 记忆预算使用字符上限，不等同精确token上限；没有实测节省比例，没有接向量数据库。
- 自主阅读仍是搜索摘要级材料，没有读取完整文章；没有把它宣传成读完一本书。
- 没有完整移植人格驱动、目标归属与置信度、精确用户反馈归因。下一步应在现有 ThreadBook 上补这三件事，并把“我想提起/已展示/用户确认/完成”分清。
- 桌宠仍使用原有Canvas角色，本轮改状态与悬浮交互；没有谎称完成整套原创角色资源重制。
- 仍是公开调试签名的个人测试包，手机后台长期存活、实际声音、遮挡与触控、视觉质量须实测。

## 4. 手机上检查的最短路径

1. 安装对应预览包，首页启动桌宠。查看“主动交流”一栏，不再只看角色是否在动。
2. 点“现在聊一句”。这会跳过普通冷却，仍遵守静音、通话、打字、睡眠和受保护回合规则；没有Key或模型失败时直接显示原因。
3. 如果之前暂停/关闭/安静，明确点“恢复主动陪伴并试聊”。睡眠时段仍需在设置调整，不会被按钮偷偷清掉。
4. 先确认一条真实生成内容出现，再点“开启朗读”，检查系统中文TTS状态与声音。界面显示成功不等于人已经听到。
5. 回到文章或日常使用场景，放置到目标间隔后检查是否自动出现；同一页不应反复复述。截图没有明显变化时不应增加视觉模型调用。
6. 点击气泡“回复”，用正常键盘继续；长说明可滚动，“更多”里能收起、安静和进入原有功能。切走后再回来应接续原话题。
7. 若仍失败，复制设置中的诊断信息。报告包含阶段、次数、Key/网络/额度结论和语音状态，不包含Key值或画面正文。

共14个主要参考项目，另核对Letta旧仓库到当前源码的迁移。构建与验证结果见 [VALIDATION.md](VALIDATION.md)。它区分本地/CI与设备结果，不能用测试数量替代上述手机验收。
