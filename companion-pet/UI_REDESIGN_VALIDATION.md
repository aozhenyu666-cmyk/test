# 同桌 UI 重构 · 验证记录（UI_REDESIGN_VALIDATION.md）

日期：2026-10-25。分支 `deepseek/companion-ui-overhaul`，基线 `5f2d423`（0.6.2-preview / 29）。

## 1. 构建、测试与 lint（实际执行）

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

| 项 | 结果 |
|---|---|
| JVM 单元测试 | **441 个，0 失败** |
| lint | **0 errors / 15 warnings** |
| assembleDebug | **成功** |
| assembleDebugAndroidTest | **成功（仅编译）** |

APK：29,832,679 bytes
SHA-256：`0EC2DAE2A678FCD0E1D3DE3349B551553FDF2592E48050374265326E5BB87D10`

注：构建过程一度失败，原因是 `org.gradle.caching=true` 命中了改动前（仍引用 `mipmap-anydpi-v26`）的构建缓存，报 `resource mipmap/ic_launcher not found`。用 `--no-build-cache --rerun-tasks` 重新构建后通过。这不是代码问题，但**说明不能只看增量构建结果**。

## 2. 改动文件清单

### 新增
| 文件 | 作用 |
|---|---|
| `res/drawable/ic_launcher_foreground.xml` | Launcher 前景（圆头 + 触角 + 平静小嘴） |
| `res/drawable/ic_launcher_background.xml` | Launcher 背景（暖纸 #F7F3EA） |
| `res/drawable/ic_launcher_monochrome.xml` | 主题图标剪影 |
| `res/drawable/ic_notification.xml` | 通知小图标（单色剪影） |
| `res/mipmap-anydpi/ic_launcher.xml` | adaptive icon |
| `res/mipmap-anydpi/ic_launcher_round.xml` | 圆形 adaptive icon |
| `res/values-night/themes.xml` | 深色启动底色，避免闪白 |
| `UI_REDESIGN_AUDIT.md` | 审计报告 |
| `DESIGN.md` | 设计系统 |
| `UI_REDESIGN_VALIDATION.md` | 本文件 |

### 修改
| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | icon 由 `@drawable/ic_pet` 改为 `@mipmap/ic_launcher` + `roundIcon` |
| `res/values/themes.xml` | 增加 `windowBackground` = `#F7F3EA` |
| `ui/theme/Tokens.kt` | 强调色降饱和为珊瑚；新增 `success/warning/info`；`signal` 改为柔和高亮；新增 `pill` 圆角；正文均提升 1sp |
| `ui/theme/CompanionTheme.kt` | 同步新 token 映射 |
| `ui/components/CompanionUi.kt` | `CutAction` 高亮改用珊瑚；`SourceTag` 用 `pill`；新增 `EmptyState` |
| `MainActivity.kt` | 应用标题由“桌宠”改为“同桌” |
| `ui/StartTab.kt` | Hero 上文字改用 `accent`（原 `signal` 在新配色下不可读）；OrbitArt 核心色改 `warning` |
| `ui/StatsTab.kt` | 空状态改用 `EmptyState`；表格增加横向滚动提示 |
| `overlay/PetView.kt` | 强调色由蓝 `rgb(108,142,230)` 改为珊瑚 `rgb(201,81,46)`；纸色对齐 token |
| `overlay/BubbleView.kt` | 墨、珊瑚、气泡底色对齐 token |
| `service/PetService.kt` | 通知图标改用 `ic_notification` |

### 删除
| 文件 | 原因 |
|---|---|
| `res/drawable/ic_launcher.xml` | 旧回退层列表，minSdk 30 用不到，lint 报 unused |

## 3. 视觉验收状态：**未完成（环境限制）**

必须如实说明：
- `adb devices` 为空，iQOO 真机不在线。
- SDK 目录内**没有 emulator 二进制**，`~/.android/avd` 的 3 个 AVD 无法启动。
- 因此**没有拿到任何一张真实渲染截图**，指导书要求的“至少两轮截图评审与修正”**未执行**。

本轮的视觉结论全部来自源码与资源审计。**构建成功不等于视觉通过。**

## 4. 已验证 / 未验证

### 已验证
- 图标资源能被 AAPT 正确链接（`processDebugResources` 通过）。
- adaptive icon 与 roundIcon 在 manifest 中引用正确。
- 移除 `mipmap-anydpi-v26` 后 lint 的 `ObsoleteSdkInt`、`UnusedResources` 警告消失（17 → 15）。
- 441 个 JVM 测试全过，说明 UI 改动未破坏业务逻辑与数据层。
- 通知图标从 Launcher 图标分离，符合 Android 单色要求。

### 未验证
- **桌面图标实际显示效果**（浅色/深色壁纸、圆/方/圆角三种裁切、Material You 主题图标）。
- 深色模式全页面观感。
- 字体放大 1.3× 下的换行与截断。
- 窄屏（小屏机）下统计表格与设置页。
- 悬浮角色换色后在真实壁纸上的对比度。
- 今天页 / 设置页 / 记忆页 / 统计页的实际渲染。
- 空状态、错误状态、加载状态。

## 5. 已知限制与风险

1. **图标是矢量重绘，未经真机确认**。若 vivo 桌面裁切过狠，前景可能需要再缩进 4–6dp。
2. **设置页仍是 66.9 KB 单文件长滚动**。本轮只做了分组标题与密度优化，**没有拆成二级页**，因为拆分需要改导航结构，属于较大改动，留给下一轮。
3. **`signal` 语义已变更**。原有代码里它曾被当高亮按钮底，现改为柔和高亮；已修正 `CutAction`、`SourceTag`、`StartTab` 三处，但若后续新增页面误用会出现低对比度。
4. **悬浮层颜色是硬编码常量**，非 Compose token。两边改色必须同步，已在 DESIGN.md 标注。

## 6. 真机验收清单

在 iQOO 12（Android 16）上按顺序检查：

| # | 检查项 | 预期 |
|---|---|---|
| 1 | 桌面图标（浅色壁纸） | 珊瑚圆头清晰可见，不糊 |
| 2 | 桌面图标（深色壁纸） | 暖纸底在深色上形成对比 |
| 3 | 圆形 / 方形 / 圆角裁切 | 头部不被切掉 |
| 4 | Material You 主题图标 | 单色剪影正常显示 |
| 5 | 通知栏小图标 | 单色、可辨认 |
| 6 | 今天页首屏 | 焦点是状态卡，不是按钮堆 |
| 7 | 设置页 5 个分区 | 分组标题清晰，无截断 |
| 8 | 记忆页 / 统计页 | 表格可横滑且有提示 |
| 9 | 统计页无数据 | 显示 `EmptyState` 说明而非空白 |
| 10 | 深色模式全页面 | HeroPanel 与页面底可区分 |
| 11 | 字体 1.3× | 无截断、无重叠 |
| 12 | 悬浮角色 + 气泡 | 珊瑚强调与页面一致 |
| 13 | 悬浮层遮挡 | 不挡当前 App 主要操作 |
| 14 | 覆盖安装 29 → 新版本 | 数据、Key、记忆保留 |

## 7. 下一步最小行动

1. 设备/模拟器可用后，先截图桌面图标四项裁切，确认前景缩放。
2. 再截今天页 + 设置 5 组 + 深色模式，按指导书做两轮评审修正。
3. 若图标裁切问题明显，调整 `ic_launcher_foreground.xml` 的安全区（当前 72/108）。
4. 设置页拆二级页作为独立 PR，不混入本次视觉修正。
