# 0.5.5：按 DeepSeek 的 C2 真机问题集修复

日期：2026-10-25。分支：`ccr-2899d69f-xlzjh6`（唯一主线；下一轮从这里开分支）。版本 **0.5.5 / versionCode 23**。
输入：`_pet-reports/c2-agent-054/` 的 C2_DEVICE_TEST_RESULT_0.5.4.md、C2_ISSUE_SET_FOR_CLAUDE.md、截图和 c2-smoke.json。

## 先更正一条 0.5.4 的结论

C2-04（example.com 看一眼 = PARTIAL）应为 **NOT_RUN**。截图 c2-054-look.png 的气泡“这页用七种语言反复说……你在找什么？”带“打字 / 去Gemini / 一起看”按钮，
这是普通的主动搭话气泡（读的是无障碍文字），不是看一眼的结果；看一眼的结果固定以“我看了一眼：”开头且没有按钮。
原因很可能是：点开菜单 20 秒没操作就自动收起，或看一眼进行中主动搭话插了进来，把结果盖掉。两处都已修（见下）。

## 修了什么（对应问题集）

| 问题 | 修复 |
|---|---|
| P0 视觉证据不能证明看图 | 每次看一眼记一条证据（`shared_prefs/vision_evidence.xml`）：结论、交给网络层的图片块数、image/jpeg、大小、尺寸、SHA-256 前 12 位、模型、主机、耗时。不含图片/Base64/Key/回复原文。设置页和两份诊断报告都显示“上次看一眼”。网络层把图片块发成 `image_url`（DeepSeekClientTest 已覆盖）。 |
| P0 没配好不要发注定失败的请求 | 新 `VisionSetupResolver`：缺模型名 / 缺 Key / 看图和搭话不是同一家但没填视觉 Key → 未配置，不发请求，提示去哪填。**搭话 Key 只在同一主机时沿用**，清掉视觉 Key 后不会再把 DeepSeek 的 Key 发给千问。 |
| P1 失败提示要分清 | 截图失败 / 没允许上传 / 未配置 / 额度用完 / 请求失败（带原因，如 HTTP 401）/ 解析失败，各一句。 |
| P1 设置太长找不到 | 设置页拆成 5 个分区：模型与看图、设备与权限、自主与目标、陪伴、诊断；各分区单独滚动。看图区顶部显示“看图状态 / 上传截图 / 上次看一眼”。原来的“看一眼（诊断）”改名“截图权限检查”，写明只测本机、在设置页被拒是正常的。功能一个没删。 |
| P1 菜单难自动化 | 主动搭话在看一眼进行中和结果显示 6.5 秒内不会插进来；点开的菜单 20 → 45 秒才自动收起；桌宠加 content-desc“桌宠”；主界面开 `testTagsAsResourceId`，分区按钮 `settings_group_0..4`、底部 `tab_0..3`、`vision_status`、`goals_empty`、`pending_empty` 都能在 uiautomator dump 里按 resource-id 找到。 |
| P2 goals/actions 目录不存在 | 目标和待确认没有内容时显示明确的空状态；冒烟脚本把文件不存在记为 `EMPTY_NOT_CREATED_YET`，不再当失败。第一次添加目标/产生待确认时才建文件，这是设计。 |
| P2 允许名单为空 | 设计如此；说明里写明测试时放一个专用测试 App，不放系统设置/商店/银行/支付。 |
| 体验：Shizuku 按钮后滚动跳顶 | 设备分区变短、三个按钮改为可换行；未在真机复现确认，**UNVERIFIED**。 |
| 无障碍“此服务出现故障” | 开始页“不限制电池”一项写明这是最常见原因；两份诊断报告都带“电池不受限制：是/否”。 |

## C2 冒烟脚本新增 `--look`

`python tools/c2_device_smoke.py --apk tongzhuo.apk --look`：推一张纯图形合成图（红底白圆蓝方块，无文字）→ 相册打开 → 点桌宠 → 点看一眼 → 等“我看了一眼：”气泡 → 读证据。
PASS 需同时满足：结果气泡、证据 OK 且 imageBlocks≥1 且是本次的、描述含颜色词和形状词。相册打不开时手动打开后用 `--look-here`。
离线自测：`python tools/test_c2_device_smoke.py`（7 个用例）。

## 验证

- JVM：373 个用例全过（新增 VisionSetupTest 8 个）。
- Android 编译/lint/打包：看 GitHub Actions。
- 真机：**全部 NOT_RUN**（本轮没有设备）。真实视觉仍需要千问/DashScope Key；Shizuku 需启动并授权。

## 没做（需要产品确认）

- 分享预览标题重复、URL 被剥离、双空格：是展示规则问题，等确认要怎么显示再改。
