# 0.5.6：从 5 个参考项目借来的能力

日期：2026-10-25。分支：`ccr-2899d69f-xlzjh6`（唯一主线）。版本 **0.5.6 / versionCode 24**。
参考：JARVIS（ansaribilal14/jarvis，Skills v3 设计文档）、OpenDroid、mobilerun、mobile-use、Past-da-king/agent。
**只借思路，没有复制代码**；全部用 Kotlin 在现有 DeviceController / DevicePolicy 之上重写。

## 做了什么

| 能力 | 借自 | 实现 |
|---|---|---|
| 技能（固定步骤一键重放） | JARVIS Skills v3、OpenDroid Skills、MacroDroid | `core/skill/`：步骤 = 打开应用 / 点 / 写 / 滚动 / 返回 / 等 / 等到出现。目标按资源 ID + 文字找（列表项 ID 重复时必须文字也对上），带应用守卫；**不存坐标**，布局变了宁可失败。回放：轮询等元素（8 秒）→ 按元素执行（控制器先点节点、不行按节点中心发手势）→ 重新观察核对 → 每步一行日志 ✓/?/✗，失败即停，锁屏/点桌宠即停，最长 3 分钟、30 步。存储原子写，损坏另存。 |
| 在桌宠上挑元素录技能 | JARVIS “Pick on screen” | 不录触摸（JARVIS 四次失败的教训）。点桌宠 → 技能 → 录新技能；每次点桌宠列出这一屏能点/能写的元素（①②③，输入框 ✎），选一个就记下并立刻帮你做一次。不在允许名单的 App 只记不做。 |
| 设置页管理技能 | JARVIS skill builder | 设备与权限 → 技能：新建、上下移动、删除步骤，补“打开应用/返回/滚动/等 2 秒/按文字点”，显示上次运行日志；“▶ 运行”交给桌宠跑。 |
| 截图标号（set-of-marks） | mobilerun、OpenDroid、mobile-use | 看一眼时先读无障碍节点，给最多 20 个可点/可写元素编号，在截图上画红框数字，同一张编号表放进提示词；模型提到元素时带编号。按窗口截图/整屏截图分别换算坐标。证据里记“标号 N 个”。可在设置关掉。 |
| 获取模型列表 | JARVIS “Fetch live model list” | 搭话和看图两处都有：请求 OpenAI 兼容 `/models`，点一下填进模型名；看图区把名字像视觉模型的排前、加粗（只是按名字猜）。和聊天请求同样只走 https、不跟随重定向。 |
| 电池“无限制”引导 | Past-da-king/agent | 0.5.5 已做：开始页写明“此服务出现故障”多半是被系统清理；诊断报告带电池状态。 |

## 有意没做

- **让模型自己提议“运行某技能”**：要加新动作类型并走待确认，技能一跑可能几分钟，放进确认链路需要单独设计（运行中取消、迟到结果）。下一步做。
- **按坐标点（canvas/游戏）**：JARVIS 用分数坐标兜底；我们为了不点错先不做。
- **通知触发**（Past-da-king、JARVIS）：需要通知读取权限和逐 App 授权，等你决定要不要。
- **内置浏览器 / 联网、更大的聊天面板**：你之前说先只当想法。
- **mobile-use 的多智能体反思、mobilerun 的电脑端驱动**：更适合当电脑上的自动验收工具，不放进 App。

## 验证

- JVM：388 个用例全过（新增 SkillTest 8、ModelListerTest 4、ScreenMarksTest 3）。
- 设备脚本离线自测 7 个全过。
- Android 编译/打包：看 GitHub Actions。
- 真机：**全部 NOT_RUN**。验收步骤见 `handoff/c2-device-acceptance/DEVICE_CHECKLIST.md` 第 10、11 项。
