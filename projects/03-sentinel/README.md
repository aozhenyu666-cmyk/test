# 哨兵

[返回项目总览](../README.md) · [返回仓库首页](../../README.md)

独立监测总控、一手、同桌的健康状态，处理失联与锁屏兜底。

## 去哪里找

| 内容 | 入口 |
|---|---|
| 源码 | [zongkong/sentinel/](https://github.com/aozhenyu666-cmyk/test/tree/deepseek/sentinel-v2/zongkong/sentinel) |
| 所在分支 | [deepseek/sentinel-v2](https://github.com/aozhenyu666-cmyk/test/tree/deepseek/sentinel-v2) |
| 本次核对快照 | [59117af](https://github.com/aozhenyu666-cmyk/test/tree/59117afe1f3fcfefcd05f33b4adfc7a0a9fd9a75/zongkong/sentinel) |
| Android 包名 | `com.zongkong.sentinel` |
| 项目原说明 | [打开说明](https://github.com/aozhenyu666-cmyk/test/blob/deepseek/sentinel-v2/zongkong/sentinel/SENTINEL-V2-NOTES.md) |
| 构建记录 | [zongkong-android.yml](https://github.com/aozhenyu666-cmyk/test/actions/workflows/zongkong-android.yml?query=branch%3Adeepseek%2Fsentinel-v2) |
| 发布页 | [zongkong-latest](https://github.com/aozhenyu666-cmyk/test/releases/tag/zongkong-latest) |
| 已有安装文件 | [zongkong-sentinel.apk](https://github.com/aozhenyu666-cmyk/test/releases/download/zongkong-latest/zongkong-sentinel.apk) |

## 查找提示

哨兵是独立 APP，构建模块为 `:sentinel`，但不是独立 Gradle 根工程。本地从 `zongkong/` 构建，不能直接把 `zongkong/sentinel/` 当完整工程搬走；它使用上级工程和 `../app/debug.keystore` 签名路径。现有 `zongkong-latest` 附件对应较早的发布提交，不能据此声称它已包含 sentinel-v2 的改动。v2 请以源码、实施说明和对应运行记录为准。

## 构建位置

检出 `deepseek/sentinel-v2` 后，从仓库根目录执行：

```bash
cd zongkong
./gradlew :sentinel:testDebugUnitTest :sentinel:assembleDebug
```

Windows 可使用 `gradlew.bat`。本次只整理入口，没有重新运行 Android 构建或真机锁屏测试。

> 本页整理的是实际目录、分支和已有发布入口。导航分类不代表停用、删除、验收通过或所有开发分支已经合并。完整分支清单见 [分支索引](../BRANCHES.md)。
