# 手机健康与“现在”流 · 模拟器验证报告

- 日期：2026-11-04
- 设备：`dsh_pixel_api34`（Android 14，x86_64，Google APIs）
- 构建：`:app:assembleX64Debug`，带 `runtime/out/x86_64`（借用 `h-dev` 的运行时）
- 分支：`feat/relationship-core-p0`（基线 `fix/r4-dev-regression-build-2026-11-01`）

## 一、验证结果

| 项目 | 结果 | 证据 |
|---|---|---|
| App 启动 | 通过 | 首启进入引导第 2 步；无 FATAL |
| 无障碍绑定 | 通过 | `dumpsys accessibility` 显示 `Bound services` 与 `Enabled services` 都包含本服务 |
| 健康探针 | 通过 | `health`：`connected=true alive=true healthy=true verdict=HEALTHY lastEventAgeMs≈2s` |
| 前台应用识别 | 通过 | 打开设置后 `now`：`app=com.android.settings` |
| 停留分钟 | 通过 | 同一 App 65 秒后 `appMinutes=1` |
| 亮屏/灭屏 | 通过 | 灭屏：`screenOn=false app=null`；亮屏：`screenOn=true` |
| 充电 | 通过 | `dumpsys battery set ac 1` 后 `charging=true` |
| 运行中删除无障碍 | **通过** | `settings delete` → 0.4 秒内 `DshA11yKeeper: accessibility service re-added`，服务重新 bound |
| 重启后恢复 | 通过 | 删除设置后重启 App，keeper 重新加入服务并 bound |
| 主进程冻结 + 心跳过期 | **通过** | `:watchdog` 杀掉僵死的主进程 pid，新进程约 2 秒重新连上无障碍 |
| 重启手机 | **通过** | 主进程和 `:watchdog` 都起来，无障碍保持 bound |
| 系统 UI / 输入法过滤 | **修复后通过** | 之前 `now` 会被 `com.android.systemui` / 输入法污染；加过滤后保持真实前台 App |

## 二、本轮修复

1. **假死检测**：新增 `A11yHealthPlan` / `A11yHealth`，根据事件新鲜度 + `rootInActiveWindow`/`windows` 探针判定 `HEALTHY / STALE / DEAD`。
2. **健康心跳**：独立 `HandlerThread` 每 30 秒探活，避免主线程卡死时探针也停。
3. **Keeper 接入健康**：`A11yKeeper` 从“连上就算好”改为“DEAD 才重绑”，STALE 不误重绑。
4. **设置页可见**：状态里增加 `healthy / verdict / lastEventAgeMs / lastProbeAgeMs / failures`；“已连接但不响应”显示为需要重新连接。
5. **现在流**：`NowFeed` 记录前台 App / 停留分钟 / 亮屏 / 充电；过滤 systemui、IME、权限对话框；每 30 秒和读取时用焦点窗口校准，解决窗口事件滞后。
6. **phone_now 工具**：MCP 工具目录新增 `now`，模型可直接读取手机此刻状态。
7. **诊断**：`A11Y_PROBE` 新增 `health` / `now`；`tooling/a11y-doctor.ps1` 可对真机做只读诊断。
8. **独立看门狗**：`:watchdog` 进程 + 低优先级前台通知 + 60 秒（debug）/15 分钟（release）闹钟 + 开机/解锁接收器；心跳过期时先安全杀掉僵死的主进程，再重绑无障碍。

## 三、模拟器暴露并修掉的问题

- **前端应用被 systemui 污染**：窗口切换时事件包名常是 `com.android.systemui`，`NowFeed` 原先会误记。已过滤系统 UI、IME、权限对话框。
- **窗口事件滞后**：`am start` 打开设置后没有立即收到 Settings 的包事件，`now` 仍显示旧 App。已在读取时用 `driver.foregroundPackage()` 校准。
- **中文 Pub 缓存路径**：`jni` 原生编译的 `ninja` 无法处理 `C:\Users\敖震宇\...`，导致带 runtime 的 APK 构建失败。用 `D:\code\pub-cache` 映射后解决（本机环境问题，不入库）。

## 四、仍未验证 / 不足

| # | 不足 | 影响 | 下一步 |
|---|---|---|---|
| 1 | 没有 iQOO 12 真机 | 模拟器是 AOSP，vivo 的睡眠模式/后台限制不同 | 连 USB，跑 `a11y-doctor.ps1` |
| 2 | 看门狗本身也可能被杀 | `:watchdog` 能扛住主进程被杀；如果 OEM 连 `:watchdog` 一起清掉，只能靠第二个极小 App 或 Shizuku 外部看门狗 | iQOO 真机观察；必要时做第二 App |
| 2b | 用户/系统强行停止 App | Android 禁止任何自动重启，任何 App 都救不回来 | 通知/快捷方式一键恢复；不要承诺全自动 |
| 3 | 自动重绑需要 `WRITE_SECURE_SETTINGS` | 没有 Shizuku/ADB 授权时只能提示手动开关 | 做一键授权引导，或接 Shizuku |
| 4 | 假死判定最长 5 分钟 | 事件停了但树还可读时不会立刻重绑 | 增加“动作失败即降级”的信号 |
| 5 | 没有读屏/使用统计 | 无障碍关闭时 `now` 只能返回未知 | UsageStats 兜底（需用户授权） |
| 6 | 关系插件未安装到 App | 7 个工具还没在真实会话里出现 | `dsh plugin add` 后跑一次真实对话 |
| 7 | 没有关系面板/高频仪式 | 只有引擎和工具，没有“今天/抽背/复盘”页面 | P1 做线程卡、抽背页、复盘页 |
| 8 | 中文解析仍是规则 | 复杂从句/时态会漏 | P1 换模型结构化提取 |
| 9 | 发行包未就绪 | 当前是 x64 debug + 借用 runtime；无 release 签名/update.json | 重建 arm64 runtime，签正式版 |
| 10 | 无障碍合规风险 | `isAccessibilityTool=false`，商店审核敏感 | 准备披露文案与隐私说明 |

## 五、结论

**“发现假死 + 自动重连 + 主进程被杀后由 `:watchdog` 恢复 + 现在流”在模拟器上成立**，可以作为正式版的地基。  
**但还不是正式版**：缺 iQOO 12 真机验证、arm64 正式包、关系插件的真实会话验证，以及“今天/抽背/复盘”的产品页面。  
另外，如果 iQOO 连 `:watchdog` 一起清掉，是否接受“第二个极小 App 做外部看门狗”需要产品决定。
