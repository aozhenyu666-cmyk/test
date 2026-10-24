# GitHub 参考：让“大脑”和“手”形成闭环

调研日期：2026-10-24。六个名字均找到了公开仓库，集中在 `Yuuyuko-uu` 名下。这些是 DSH 插件，需要 Node/浏览器或桌面后端；不是可直接安装到原生 Kotlin APK 的组件。本轮未安装、运行或引入它们的代码，也未使用任何账号凭证。

## 能借鉴什么

| 项目 | 查看依据 | 可借鉴的设计 | 安卓落地判断 |
|---|---|---|---|
| [dsh-fish-memory](https://github.com/Yuuyuko-uu/dsh-fish-memory) | README；`lib/index.js` 的默认策略与会话隔离接口 | 先看相关证据，再用新鲜度/重要度排序；事实与事件区分；每条带时间；明确问题的信息量；上下文条数/字数上限 | 借鉴策略，用 Kotlin 和本地存储实现；不移植整套词典和宿主注入代码 |
| [dsh-speak-up](https://github.com/Yuuyuko-uu/dsh-speak-up) | README；`lib/host.js` 的本地触发与 source.kind 设计 | 定时器本地决定是否触发，不在每个 tick 问模型；主动触发有独立来源；dry-run、冷却与安静时段；触发后继续使用同一个角色和历史 | 可对应现有 SpeakScheduler、TopicGate、PetBrain；不能把系统任务伪记为用户说的话 |
| [dsh-bili-subagent](https://github.com/Yuuyuko-uu/dsh-bili-subagent) | README；`lib/state.mjs` | “看材料”和“用角色口吻表达”分离；字幕/评论/标题有来源；材料报告返回角色再决定怎么说；配置和报告在运行数据目录 | 先实现分享材料入口；完整站点抓取放独立适配器。其 Python、MCP、会话宿主不能直接搬入 APK |
| [dsh-feiyu-mood](https://github.com/Yuuyuko-uu/dsh-feiyu-mood) | README；`lib/index.js` 的事件来源与提取接口 | 区分用户来聊、主动搭话、定时取材；情绪随时间回稳；低置信判断不直接变成角色口吻；变化不反复注入 | 本轮先保留本地确定性 PetState；每轮额外问模型判情绪会增加请求，不照搬 |
| [dsh-qq-bridge](https://github.com/Yuuyuko-uu/dsh-qq-bridge) | README | 会话绑定、接收/发送分离、消息去重和投递结果；先试运行；依赖 OneBot HTTP 后端 | 属于后续桥接服务。手机 `127.0.0.1` 不是电脑地址；先设计经过认证的适配器再接入 |
| [dsh-tts-bridge](https://github.com/Yuuyuko-uu/dsh-tts-bridge) | README | 最终回复进入朗读队列；停止、取消、只播放完成的一轮 | 该插件需要浏览器扩展和登录中的网页。安卓本轮继续系统 TTS，后续再评估原生语音引擎 |

查看源码时固定的参考提交：

- fish-memory：`b49938f77b36b8610d061d3a24f2d6ca95214044`
- speak-up：`7a9dee46d4f89946738a598990f3ec1c26d1635f`
- bili-subagent：`bbe41848137cdfa334bf04ebe7d5d29830390923`
- feiyu-mood：`2f278fcdeee00a78d43f3e7acbd6976428a3e6b1`

代码查看集中在这些模块的接口、策略和默认值，并非完整审计。QQ/TTS 的判断主要来自 README；不把作者描述的兼容性当成本项目实测。

## B站方案里真正有用的部分

`dsh-bili-subagent` 的公开流程是：到点触发 → 读取视频材料 → 内部报告 → 伙伴决定表达 → 投递。它沿用 [c1overzz/dsh-bili-scroll](https://github.com/c1overzz/dsh-bili-scroll) 的基础，相关站点能力来自内置的 [adoresever/bilibili-mcp](https://github.com/adoresever/bilibili-mcp)。

这解释了为什么同一个 DeepSeek 看起来更有活力：它拿到了新材料、可持续的历史、合适的主动触发时机，以及能真正执行的工具。仅增加一句“你要自主、要活泼”，无法补齐这些条件。

本项目适合采用这条链路：

```mermaid
flowchart LR
    A[用户分享或真实事件] --> B[素材收件箱与来源ID]
    B --> C[相关记忆与角色状态]
    C --> D[本地判断时机与预算]
    D --> E[角色生成一句有依据的话]
    E --> F[受限工具执行]
    F --> G[成功或失败结果]
    G --> C
```

“大脑”负责判断证据、记忆更新、时机和下一步；“手”负责有限且可核对的效果：保存、打开、播报、交付。工具失败必须回到决策层，不能在文案里继续写“我已经替你完成了”。

## 本轮已经吸收的原则

- 用户更正优先于旧摘要，来源失效要贯穿派生内容。
- 用户当前问题用于相关召回；信息不足时不硬凑。
- 静态人设保持稳定；动态材料处于数据段，限条数、限字数、保留完整标记。
- 在途生成基于的记忆已经变化，就丢弃旧结果。
- 调用预算按本应用实际 HTTP 尝试计算。外部项目“手动不计自动额度”的口径不适用于本应用的每日总预算。

## 接下来按什么顺序做

1. **素材收件箱**：先支持用户分享的链接/摘录，做到有来源、可删、可回看、无字幕不假装看过。对应 DeepSeek 任务 A。
2. **受限工具**：用明确动作集、幂等 ID、取消和结构化结果，把现有安卓能力接成可检查的执行层。对应任务 B。
3. **更好的相关召回**：引入信息量门槛、可解释命中、普通事件衰减、长期事实保留；先在合成语料上比较误召回率，不能直接靠“结果条数更多”判断更智能。
4. **有来源的自主取材**：上述闭环稳定后，再做只读的 B站材料适配器；抓取状态、字幕可用性和限额需要单独验证。
5. **外部表达**：QQ 或公开评论属于另一种工具，需独立配置对象和启用方式；默认先产生本地草稿与投递预览。不能拿测试通过替代实际账号交付证据。

另找到了 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)，适合继续研究宿主的会话、工具和回合接口。当前 APK 仍保持原生安卓结构，不把整个 Node 宿主嵌进去。

本项目已有的 Endfield 风格界面继续保留。本轮只完善状态反馈与按钮换行；GitHub 的界面参考不能证明手机上字体 1.3、键盘、悬浮窗已适配，这些仍需设备证据。
