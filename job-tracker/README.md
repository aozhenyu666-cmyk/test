# 求职投递看板（Android）

只在手机本地运行的求职投递助手：粘贴岗位描述后，AI 判断值不值得投；每条投递到期时，AI 备好跟进话术，你复制后自己发送。

- 规格的复述和待确认问题：[`docs/01-理解与待确认.md`](docs/01-理解与待确认.md)
- 技术栈：Kotlin + Jetpack Compose、Room、WorkManager、EncryptedSharedPreferences、OkHttp + kotlinx.serialization
- 最低系统版本：Android 8.0（API 26）

## 进度

| 模块 | 状态 |
|---|---|
| 1. 项目骨架与数据层 | ✅ 已完成 |
| 2. 设置页与简历 | ✅ 已完成 |
| 3. 模型调用层与匹配分析 | ✅ 已完成 |
| 4. 列表、详情与状态联动 | ✅ 已完成，等你确认 |
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

## 模块 2：设置页与简历

```
app/src/main/java/app/jobtracker/
├── security/ApiKeyStore.kt        API 密钥：EncryptedSharedPreferences 加密保存，不进数据库
├── ui/settings/SettingsScreen.kt  简历、求职方向、API 密钥、接口地址、两个模型名
├── ui/settings/SettingsViewModel.kt
├── ui/settings/SettingsValidation.kt  接口地址必须 https://，模型名不能为空
├── ui/home/HomeScreen.kt          首页占位，模块 4 实现
└── MainActivity.kt                底部两个标签：投递 / 设置；没有简历时首次打开直接进设置
```

其他改动：
- 设置表新增 `apiBaseUrl`（接口地址，默认 `https://api.anthropic.com`）。应用还没发布过，所以没有升级数据库版本号。
- 关闭 Android 12 及以上的云备份和换机迁移（`res/xml/data_extraction_rules.xml`），数据和密钥只留在本机。
- 设置页里的提醒时间、渠道与应用、后台运行说明、导出，分别在模块 5、6 中加入。

### 怎么验证

1. 单元测试：`./gradlew :app:testDebugUnitTest`，新增两个测试类：
   - `SettingsValidationTest`：地址必须是 https、模型名不能为空
   - `SettingsViewModelTest`：
     - 首次打开是空简历和默认模型
     - 保存后重新加载仍在；空白简历不保存
     - 密钥只存在密钥存储里，数据库三张表都搜不到
     - 密钥输入框留空时保留旧密钥
     - 地址不合法时什么都不保存
     - 清除密钥后密钥为空
2. 真机检查（对应验收标准第 1 条）：
   1. 首次打开应直接进入"设置"，粘贴简历和求职方向后点"保存简历"
   2. 填 API 密钥后点"保存接口设置"，输入框清空，下方显示"密钥已加密保存在本机"
   3. 强制结束应用再打开，应进入"投递"标签；切到"设置"，简历、方向、模型名都还在，密钥显示为已保存
   4. 把接口地址改成 `http://` 开头，应提示错误且不保存

## 模块 3：模型调用层与匹配分析

```
app/src/main/assets/prompts/       提示词，改这里不用动代码
├── analysis_system.md             匹配分析的规则（只依据简历、如实写缺口、参考求职方向、只输出 JSON）
├── analysis_user.md               {{resume}} {{direction}} {{jd}} 模板
└── analysis_schema.json           输出格式，八个字段加 reason
app/src/main/java/app/jobtracker/
├── ai/ModelClient.kt              唯一入口 callModel(role, system, user)，以及错误类型
├── ai/AnthropicClient.kt          Anthropic Messages 接口（OkHttp），30 秒超时
├── ai/PromptStore.kt              读提示词文件、填模板
├── analysis/MatchAnalysis.kt      解析和校验分析结果
├── analysis/AnalysisService.kt    组装提示词、调用、输出不合格时自动重试一次
├── ui/analysis/                   分析页
└── ui/home/HomeScreen.kt          首页（模块 4 已换成正式版）
```

几处实现细节：
- **结构化输出**：请求里带上 `analysis_schema.json`，模型必须按这个格式返回 JSON。规格里"不是合法 JSON 就重试一次"的逻辑仍然保留。缺字段、匹配度或建议的取值不对，也算不合格，会触发重试。
- **服务端拒答兜底**：只有直连官方接口、并且模型是 Sonnet 5.5 这类支持的模型时才开启。开启后，模型拒答时服务端会自动换一个模型重试。走中转地址时不开启，以免中转服务不认这个参数。
- **思考深度**：分析用 `medium`，话术用 `low`，都在 `AppConfig` 里。分析如果超过 20 秒，可以把它改成 `low`。
- **哪些情况不重试**：无网络直接提示，不发请求；超时、密钥无效、限流也不重试，直接给出对应提示。
- **出错不丢内容**：分析失败时，JD 原文留在输入框里。

### 怎么验证

1. 单元测试：`./gradlew :app:testDebugUnitTest`，新增 5 个测试类：
   - `AnthropicClientTest`：用本地模拟服务器检查：
     - 请求头、模型名、system、结构化输出参数
     - 没有密钥或断网时不发请求
     - 404、拒答、截断、超时分别给出对应错误
   - `AnalysisServiceTest`：简历、方向、JD 都发给强模型；不合格时重试一次，第二次仍失败就报错；断网不重试
   - `MatchAnalysisTest`：解析、去掉代码块包裹、缺字段、非法取值、简历要点截到 3 条
   - `PromptStoreTest`：模板只替换一次；schema 要求全部字段
   - `AnalysisViewModelTest`：
     - 没有简历或 JD 不足 50 字时不能分析
     - 加入投递后生成一条"待投递"记录，保存的是手改后的公司名、JD 原文和分析 JSON
     - 重新分析不影响已加入的记录
     - 失败时 JD 还在
2. 真机检查（对应验收标准第 2～4 条和第 11 条）：
   1. 清空简历后，从首页点"+"进入分析页，"分析"按钮应不可点，并提示"去设置"
   2. 保存简历和密钥后粘贴一段真实 JD，点"分析"，20 秒内应出现匹配度、建议、硬性要求、优势、缺口、简历要点，公司名和岗位名可以改
   3. 点"加入投递"，回到首页，应出现这条记录，状态为"待投递"
   4. 打开飞行模式再点"分析"，应提示"没有网络连接"，JD 还在

## 模块 4：列表、详情与状态联动

```
app/src/main/java/app/jobtracker/
├── domain/FollowUpRules.kt     状态和日期变化时自动排下次跟进日期（纯逻辑）；DueState 判断今天、逾期、未来
├── ui/home/                    首页：待跟进 / 全部，按状态筛选，逾期标红，右下角 +
├── ui/detail/                  详情页：状态、日期、公司、岗位、渠道、备注，JD 折叠，删除前确认
└── ui/Format.kt                日期显示："10月13日（3 天后）"
```

自动排日期的规则（天数都在 `AppConfig` 里）：

| 操作 | 结果 |
|---|---|
| 改为"已投递" | 投递日期为空就填今天，下次跟进 = 投递日期 + 3 天 |
| 改为"面试中" | 跟进次数清零；有面试日期则下次跟进 = 面试日期 + 1 天，没有就先不动，页面上提示补填 |
| 第一次填投递日期 / 面试日期 | 按上面的规则重算 |
| 之后再改这两个日期 | 下次跟进日期还是自动算出的值就跟着重算；手改过的不动 |
| 改为"已结束" | 先弹框选结果（被拒 / 无回复放弃 / 自己放弃 / 已录用），然后清空下次跟进日期 |
| 从"已结束"改回 | 清空结果，再按新状态套用规则 |
| 手改下次跟进日期 | 随时可以，也可以清空 |

其他说明：
- **自动保存**：详情页没有"保存"按钮。状态和日期改了立刻保存；文字停止输入半秒后保存；离开页面时再补存一次。
- **渠道**：自由输入，下面会列出用过的渠道，点一下就能填入。
- **"待跟进"的范围**：今天到期和已逾期、并且未结束的记录。"全部"按下次跟进日期排序，没有日期的排最后。

### 怎么验证

1. 单元测试：`./gradlew :app:testDebugUnitTest`，新增 3 个测试类：
   - `FollowUpRulesTest`：上表每条规则都有对应用例，另外覆盖逾期、今天、未来的判断
   - `DetailViewModelTest`：
     - 改为"已投递"后存进数据库的日期是 3 天后
     - 改为"已结束"要带结果
     - 文字和手改的日期都能保存
     - 渠道提示来自其他记录
     - 删除后记录消失
   - `HomeViewModelTest`：待跟进只显示今天和逾期的记录；"全部"的排序和按状态筛选
2. 真机检查（对应验收标准第 4、5、12 条）：
   1. 加入投递后，首页"全部"里应有这条"待投递"记录；"待跟进"为空
   2. 点进详情，把状态改为"已投递"，下次跟进日期应变成 3 天后
   3. 把下次跟进日期改成昨天，回到首页，这条记录应出现在"待跟进"最上面并标红
   4. 改为"已结束"时应弹框选结果，选完后下次跟进日期显示"未安排"
   5. 删除时应先弹框确认
   6. 强制结束应用再打开，所有修改都在

> 注意：这一版代码是在无法访问 Google Maven 和 Android SDK 的环境里写的，**还没有实际编译和运行过测试**（模块 1～4 都是）。第一次同步或测试如果报错，把错误贴给我，我来修。
