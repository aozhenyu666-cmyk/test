# 界面

界面全部用代码画（不引入 androidx），样式集中在 `app/src/main/kotlin/com/dshdriver/app/Ui.kt`。
配色参照 Claude 的暖色调：米白底、暖灰字、陶土橙只留给主行动；分层靠 1px 边框和留白，不用阴影。

## 两套颜色

| 用途 | 浅色（首页） | 深色（断点卡 / 拦截页 / 悬浮卡） |
|---|---|---|
| 背景 | `#FAF9F5` | `#262624` |
| 卡片 | `#FFFFFF` | `#30302E` |
| 次要块 / 输入框 | `#F0EEE6` | `#3A3A37` |
| 边框 | `#E6E2D9` | `#45453F` |
| 正文 | `#1F1E1D` | `#F5F4EF` |
| 次要文字 | `#605D57` | `#B5B2AA` |
| 弱文字 | `#9A958C` | `#8A867E` |
| 主行动（橙） | `#D97757` | `#E08A6B` |
| 就绪 | `#2F6F54` | `#7BC0A0` |
| 需要注意 | `#94651F` | `#D8A55F` |
| 出错 | `#B4453A` | `#E79A90` |

深色只用在"盖在别的应用上"的三处（`CardActivity`、`CheckActivity`、`Overlay`）：夜里学习时不刺眼，也让"被打断"这件事在视觉上和首页分开。

## 字号与形状

| 层级 | 用法 |
|---|---|
| 28sp 衬线 | 页面主标题（`headline`）；断点卡 / 拦截页用 24sp |
| 22sp 衬线 | 统计数字；倒计时 40sp |
| 16sp 中黑 | 卡片标题 |
| 15sp | 正文 |
| 13sp | 说明（`caption`） |
| 11sp + 字距 0.13 | 小标签（`kicker`）、状态标签 |

圆角：卡片 14dp、按钮 10dp、输入框 10dp、状态标签 99dp（药丸）。按钮高 46dp，选项按钮 54dp，点按范围都够单手。

## 三处界面

- **首页**（`MainActivity`）：小标签 + 衬线标题 → 三块状态瓦片（拦截 / 大脑 / 无障碍）→ 开始陪练 → 拦截娱乐 App → 要的权限 → 大肥鱼 → 主线和老毛病 → 上一次 → 诊断 → 页脚。
- **断点卡**（`CardActivity`）：全屏深色，只有一句问题；有 A/B 时给两个大选项按钮，没有就给输入框；底部三个小按钮仍是"回到 Live / 休息 / 紧急结束"。
- **拦截答题页**（`CheckActivity`）：深色，标题 + 一句说明 + 问题卡 + 输入框 + 反馈 + 提交。
- **悬浮卡**（`Overlay`）：深色半透明圆角卡，离屏幕边缘 12dp、顶部避开状态栏；只放 A/B 和"展开 / 收起"，不抢焦点。

## 刷新方式

首页不再每秒重建整页：状态切换（待机 ↔ 陪练中）时才重建，陪练中每秒只改倒计时、进度条、状态标签和两个统计数字。
这样倒计时不会让整页闪一下，滚动位置也不会被弹回顶部。

## 调试入口（只在 debug 构建里）

暗色页平时要真的切屏或被 Curbox 拦下才会出现。debug 构建多一个预览入口，可以单独看排版：

```bash
adb shell am start -n com.dshdriver.app/.UiPreviewActivity --es screen card
adb shell am start -n com.dshdriver.app/.UiPreviewActivity --es screen gate
```

release 构建不含这个 Activity（见 `app/src/debug/AndroidManifest.xml`）。

## 验证状态

| 项 | 状态 |
|---|---|
| `:app:assembleDebug` 编译 | **PASS** |
| 首页：待机、陪练中（倒计时、进度条、当前这一问、统计） | **PASS（模拟器）**：Pixel AVD API 34，1080×2400 |
| 断点卡、拦截答题页的暗色排版 | **PASS（模拟器，走 debug 预览入口）** |
| 悬浮卡在 Gemini Live 上方浮起的实际位置与触摸 | **NOT_TESTED**：需要真机 + 悬浮窗权限 |
| 顶栏颜色、深色页状态栏在国产 ROM 上的表现 | **NOT_TESTED** |
| 应用图标在桌面上的观感 | **NOT_TESTED** |
