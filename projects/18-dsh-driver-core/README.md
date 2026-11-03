# 大肥鱼主驾驶员

[返回项目总览](../README.md) · [返回仓库首页](../../README.md)

DSH Go 插件：开着 Gemini Live / GPT Live 学习时，切屏就拉回原断点，沉默就给最低成本的一步，假专注就踩刹车。

## 去哪里找

| 内容 | 入口 |
|---|---|
| 源码 | [dsh-driver-core/](https://github.com/aozhenyu666-cmyk/test/tree/ccr-8329c7b5-gr96ko/dsh-driver-core) |
| 所在分支 | [ccr-8329c7b5-gr96ko](https://github.com/aozhenyu666-cmyk/test/tree/ccr-8329c7b5-gr96ko) |
| 项目说明 | [打开说明](https://github.com/aozhenyu666-cmyk/test/blob/ccr-8329c7b5-gr96ko/dsh-driver-core/README.md) |
| 独立 APP 源码 | [dsh-driver-app/](https://github.com/aozhenyu666-cmyk/test/tree/ccr-8329c7b5-gr96ko/dsh-driver-app)（Android 包名 `com.dshdriver.app`，APP 名「思维驾驶员」） |
| 构建记录 | [dsh-driver-app.yml](https://github.com/aozhenyu666-cmyk/test/actions/workflows/dsh-driver-app.yml?query=branch%3Accr-8329c7b5-gr96ko)（APK 在运行页面底部 Artifacts） |

## 查找提示

按 2026-11-03 的决定做成独立 APP（`dsh-driver-app/`），不改 DSH Go 本体；`dsh-driver-core/plugin/` 是规则的 JS 参照实现，以后精简成配对后给 APP 出题的插件。`dsh-driver-core/android/` 是早先给 fork 写的接线草稿，已被独立 APP 取代。均未真机验证。

> 本页整理的是实际目录、分支和已有发布入口。导航分类不代表停用、删除、验收通过或所有开发分支已经合并。完整分支清单见 [分支索引](../BRANCHES.md)。
