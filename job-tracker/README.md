# 求职投递看板（Android）

只在手机本地运行的求职投递助手：粘贴岗位描述后，AI 判断值不值得投；每条投递到期时，AI 备好跟进话术，你复制后自己发送。

- 规格的复述和待确认问题：[`docs/01-理解与待确认.md`](docs/01-理解与待确认.md)
- 技术栈：Kotlin + Jetpack Compose、Room、WorkManager、EncryptedSharedPreferences、OkHttp + kotlinx.serialization
- 最低系统版本：Android 8.0（API 26）

## 进度

| 模块 | 状态 |
|---|---|
| 1. 项目骨架与数据层 | ✅ 已完成，等你确认 |
| 2. 设置页与简历 | 未开始 |
| 3. 模型调用层与匹配分析 | 未开始 |
| 4. 列表、详情与状态联动 | 未开始 |
| 5. 跟进话术 | 未开始 |
| 6. 提醒与通知 | 未开始 |
| 7. 验收 | 未开始 |

## 模块 1：项目骨架与数据层

```
app/src/main/java/app/jobtracker/
├── config/AppConfig.kt          所有默认值（模型名、3 天、80 字、50 字、30 秒……）
├── data/model/Enums.kt          状态、结束结果、匹配度
├── data/db/                     三张表、类型转换、DAO、数据库
│   ├── ApplicationEntity.kt     投递记录
│   ├── ProfileEntity.kt         简历与方向（单行）
│   ├── SettingsEntity.kt        设置（单行，不含 API 密钥）
│   ├── Converters.kt            日期、时间戳、时刻、渠道映射的存储格式
│   ├── *Dao.kt
│   └── AppDatabase.kt
├── data/repo/                   读写封装：自动维护时间字段、校验"已结束必须有结果"
├── AppContainer.kt              手动依赖注入
└── MainActivity.kt              占位页面，界面从模块 2 开始做
```

### 怎么运行和验证

1. 用较新的 Android Studio（2025 年及以后的版本）打开 `job-tracker/` 目录，等 Gradle 同步完成。
2. 运行单元测试（Robolectric 加内存数据库，不需要真机）：
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
   3 个测试类，覆盖这些内容：
   - 类型转换往返（日期、时间戳、时刻、带中文键的渠道映射）
   - 新建记录状态为"待投递"，JD 原文和分析 JSON 原样保存
   - 列表排序：逾期在前，今天其次，未来再次，没有日期的在最后
   - 待跟进只包含今天和已逾期的记录，不含未来日期和已结束的记录
   - 按状态筛选、删除
   - "已结束"必须带结果
   - 简历只保存一行，重复保存会覆盖；空白简历算作没有简历
   - 设置没保存过时读到默认值；修改一项不影响其他项
3. 装到手机上：`./gradlew :app:installDebug`，打开后应看到"骨架已就绪"的占位页。

> 注意：这一版代码是在无法访问 Google Maven 和 Android SDK 的环境里写的，**还没有实际编译和运行过测试**。第一次同步或测试如果报错，把错误贴给我，我来修。
