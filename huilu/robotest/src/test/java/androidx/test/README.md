# androidx.test 最小替身（仅测试用）

Robolectric 运行时会调用 `androidx.test:monitor` 和 `espresso-idling-resource` 里的少量类，
而这两个库只发布在 Google Maven 上。在只能访问 Maven Central 的环境里，用这里的最小实现代替它们。
只实现了 Robolectric 4.14 实际调用到的方法；有完整 Android SDK / Google Maven 时可以删掉这个目录，
改为依赖真正的 `androidx.test:monitor`。
