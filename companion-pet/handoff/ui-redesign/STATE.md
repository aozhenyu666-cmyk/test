# 前端 UI 重做（独立分支，未改功能）

基线：ccr-2899d69f-xlzjh6 / a590239，版本保持 0.5.4 / 22。
分支：deepseek/moling-ui-redesign。

## 视觉方向

- 暖纸色（#F6F1E7）+ 墨色（#1E1C18）+ 珊瑚强调（#E4572E）+ 柠檬绿信号。
- 圆角卡片/胶囊分段导航，不再用直角切角。
- 字体：标题用 Serif、正文 Sans，形成“手记/同伴”感。
- 参考图的“细线天体、轨道、流波、粒子”做成 Compose Canvas，不引入位图。

## 已改文件

- ui/theme/Tokens.kt：新色板、圆角、排版。
- ui/theme/CompanionTheme.kt：M3 色彩映射（accent 主色）。
- ui/components/CompanionUi.kt：Panel 圆角+细边框、HeroPanel、QuietAction。
- ui/components/CompanionArt.kt：OrbitArt（轨道自转）、WaveArt（流波呼吸）。
- MainActivity.kt：顶部品牌区 + 胶囊四段导航。
- ui/StartTab.kt：今天页 Hero（OrbitArt）+ 状态/上次模型/诊断/控制 + WaveArt + 三张快捷卡 + 权限/最近交流。
- ui/MemoryTab.kt：记忆概览四项指标卡。
- ui/StatsTab.kt：最近七天双柱图（开口/回应）+ 原明细表。

## 验证

:app:testDebugUnitTest          PASS   365 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 15 warnings
:app:assembleDebug              PASS   29,826,437 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译

APK SHA-256：FE755C97F34D1D4617CF9D44D870CA71EEB25BFF963AB8872373F80FA77E89BA

## 边界

- 只改 UI，不动业务逻辑、权限、测试断言。
- 未在真机/模拟器截屏验证（当前 adb 设备未连接）；构建通过不等于视觉通过。
- SettingsTab 仅继承新主题/组件，长滚动分区留给后续；BringIntoView 实验 API 已回退。
- 没有新增权限、没有上传截图、没有改版本号。