# 自我察觉参考与来源

本次没有整包移植参考 App；沿用本项目 GPL-3.0 和现有 Android/Ui/Engine，无新增 AndroidX、数据库、记忆框架或 MediaProjection 依赖。

| 来源与核对提交 | 核对文件 | 使用情况 |
|---|---|---|
| [Pause](https://github.com/dtsykunov/pause-app/tree/d7411ef04b7c2a4c129c71815655e651988a6e76) · MIT | AppMonitorService.kt 的截止时间/重复回访 | 学习“期限是真相、计时器只触发复查”，用现有 Engine ticker 实现；未复制拦截模块 |
| [Why am I here](https://github.com/hlophlopgaming/whyiamhereAndroid/tree/cfc6c5c601801f6cc5370bf623cafb34346cd3aa) · MIT | WhyService.kt 的会话/锁屏/浮层生命周期 | 参考持久会话和 screenAvailable 检查；不引入 Room/协程/UI体系 |
| [Appreciate](https://github.com/srid/Appreciate/tree/2dce44824c42963728f7f9422736b77f42778cdc) · GPL-3.0 | TtsManager.kt | 适配临时 duck 音频焦点及完成/错误时释放；使用中文 TTS 和已有 selfSpeaking，不移植随机英语 voice 表 |
| [HAT](https://github.com/andromedvn/HAT/tree/b0342d85e0b7f44e63e86f2fd9e47b7cac10c109) · AGPL-3.0 | UsageStatsEngine.kt、工程元数据 | 仅用于比较时间线/长期统计方向；不复制其实现，不把 APP 改成一套新统计系统 |
| [FocusTrace](https://github.com/sneri12pic/FocusTrace/tree/914291300fda26241a27095d6079850db8637947) · 未见明确许可证 | UsageSnapshotStore/Recovery/Stats 文件结构 | 仅参考快照/恢复和导出方向；未复制代码 |
| [ScreenshotTile](https://github.com/cvzi/ScreenshotTile/tree/979ab4a663b433a762850aef961dc0129602f9fc) · GPL-3.0 | 工程截图/权限入口结构 | 作为兼容方案比较；本轮用更小的无障碍单帧路径，不移植编辑/保存到相册流程 |
| [android-scroll-capture](https://github.com/garregusev/android-scroll-capture/tree/91c172b06db36ff35e3c9d9043e15c41b5a978b6) · MIT | ScrollCaptureService.kt 的 takeShot / takeShotWithRetry | ObservationShots.kt 改编单帧 callback、硬件 bitmap 转软件 copy、关闭 buffer；新增 JPEG720/60、一次瞬时重试和本地保留；不引入滚动/拼接 |
| [ScreenCapture](https://github.com/YeeeeRQ/ScreenCapture/tree/7fbd65da3db444dfbd8da36b5bf9c63698ffc2f1) · 未见明确许可证 | ProjectionHelper/前台录屏服务结构 | 仅备用技术比较；本轮无 MediaProjection、无录屏、未复制代码 |

MIT 截图代码版权：Copyright (c) 2026 Igor (@garregusev)。完整许可保存在 [licenses/android-scroll-capture-MIT.txt](licenses/android-scroll-capture-MIT.txt)。

宿主工具钩子还核对了 npm `@deepseek-ai/dsh-tools@0.0.1-rc.1` 的 `lib/types/index.d.ts`：`ToolExecutionInput` 使用 `name` 字段；本项目没有打包此审计副本或新增依赖。fish-memory 的 remember 可用性与 PTC 边界依据原项目交接审计 `dsh-go-companion-handoff/research/40-runtime-truth.md`，实际用户会话仍需现场验证。
