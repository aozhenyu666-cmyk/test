# A2：真实视觉感知（源码完成，设备未验证）

基线：`deepseek/moling-agent-fix-v1.5` / `a34adf975b7c1df4c48f56ad84ca8c458253e800`。
分支：`deepseek/moling-vision-a2`。版本：0.3.8 / versionCode 11。仅修改 `companion-pet/`。

## 已完成

### A2-0 前置边界

- 新增 `AgentEventLedger`：处理事件账本与 UI 轨迹分离，按 7 天生命周期/2000 条上限去重；501 条连续事件测试通过。
- `PetBrain` 空闲时每 5 秒补扫积压事件（不是每 tick 模型请求）；事件监听 + 1.2 秒防抖仍在。
- 关闭自主模式、锁屏、服务停止取消扫描/模型/在途动作；迟到结果不显示气泡。
- 使用稳定 turn ID `agent-event-<eventId>`，重复触发复用同一写动作 ID。

### A2-1 截图与诊断

- `perception_service.xml` 增加 `android:canTakeScreenshot="true"`；用户需要重新开关一次无障碍服务让系统重新读取能力。
- `ScreenCaptureGateway`：API 30+ `takeScreenshot`；API 34+ 优先 `takeScreenshotOfWindow`；5 秒超时；API 错误码映射为无权限/冷却/受保护窗口/无效窗口；关闭 HardwareBuffer、缩放/JPEG、超限拒绝；全部在 IO 调用。
- `ScreenObservation`/`ScreenObservationStore`：截图、节点、视觉理解三个独立布尔状态；观察有过期时间；不落盘。
- 设置 → “看一眼（诊断）”：原生截图、真实尺寸/字节/错误、本机预览；默认不上传。

### A2-2 视觉模型

- `ChatMessage` 增加可选 `imageDataUrls`；`DeepSeekClient` 在有图时发送 content blocks 数组（text + image_url），无图仍发送原字符串，保留旧调用。
- `VisionAnalyzer`：JPEG data URL、独立 `visionModel`（默认 deepseek-flash）、20 秒超时、JSON 结构化摘要；画面文字明确标记为不可信材料，不执行其中指令。
- 设置增加“允许把截图发给视觉模型”（默认关）；只有点击诊断且开关打开时按一次全局预算请求；排除/敏感窗口不上传。
- 最近有效观察摘要进入 `AgentContext` → `AgentPrompt` 的 user 数据段，带过期时间；不是 SYSTEM 指令。

## 验证

```text
:app:testDebugUnitTest          PASS   296 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 10 warnings
:app:assembleDebug              PASS   28,616,451 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`74F21A1524195F7E6C574639144C368E44B986CE40F98952BBC9102E9E768A94`

新增 7 个 JVM 用例：处理账本 501 条/清理坏行、截图策略（排除/受保护/冷却/无窗口）、观察过期与三状态、视觉请求图片块、视觉摘要解析、无配置不请求、图片 content blocks 请求体。基线 289 个测试保留。

## A2 验收状态

| 项 | 状态 |
|---|---|
| A2-T1 真机原生截图 | **NOT_RUN**（无设备） |
| A2-T2 真机视觉模型识别 | **NOT_RUN**（无设备/未用真实 Key） |
| A2-T3 Agent 使用观察 | **源码接线完成，设备/真实模型 NOT_RUN** |
| A2-T4 断线/锁屏/权限/超时/隐私等 JVM | **PARTIAL**：策略/观察/账本/内容块已测；Android 截图回调未测 |
| A2-T5 单测/lint/构建 | **PASS** |

## 边界

- 没有真机截图、没有真实视觉模型请求；不能声称 A2 设备闭环通过。
- 没有自动高频观察；只有用户点击诊断。
- 截图预览/本地缓存不落盘；上传需显式开关；受保护窗口会失败而不是绕过。
- 图片请求测试只验证 JSON 结构，不证明当前模型一定支持视觉；需要在设备上按模型能力实测。
- 没有 A3 Shizuku/通用点击，也没有 A4 长期目标。
