# 伴生桌宠 V1.5

一只停在屏幕边上的小生物，会按节奏主动找你说一两句话：看书看卡住了它会针对那段内容挑刺，来回刷手机时它只问你脑子里在转什么。想深聊时一键把你送进 Gemini，上下文已经帮你拼好。

需求依据：[V1.5 需求文档](https://claude.ai/artifact/GBaCjrc1shZXy4v391C4zu)，开发计划见 [PLAN.md](PLAN.md)。

## 安装

1. 在 GitHub Actions 的 “Companion Pet Android” 运行记录里下载 `companion-pet-debug-<commit>`，解压得到 `app-debug.apk`，传到手机安装。
2. 打开“桌宠”，在“开始”页按顺序开权限：
   - **悬浮窗**（必需）
   - **无障碍服务**（必需）。开关是灰的：应用详情 → 右上角 ⋮ → 允许受限制的设置，再回来开
   - 麦克风和通知、不限制电池（推荐）
   - vivo / iQOO：i 管家里允许自启动和后台高耗电；应用权限里允许“后台弹出界面”；最近任务里把桌宠锁住
3. 到“设置”填 DeepSeek 的 API Key（地址默认 `https://api.deepseek.com`，模型默认 `deepseek-chat`，都能改），保存。
4. 回“开始”点“启动桌宠”。

## 怎么用

| 操作 | 效果 |
|---|---|
| 它主动开口 | 气泡里一两句话。可以按住说话、打字、去 Gemini；学习场景多一个“一起看”（Gemini Live） |
| 点一下角色 | 主动叫它，弹出入口；有信时先给你看信 |
| 按住角色说话 | 学习场景：直接带着屏幕内容进 Gemini；其他场景：它接你一句 |
| 拖动角色 | 换位置，松手吸附到最近的边 |
| 通知栏 | “安静一会”暂停主动开口；“停止”关掉桌宠（之后不会自动拉起） |

不理它：60 秒后它做个动作 → 再 60 秒轻震一下、往中间挪一点 → 再 60 秒回到边上，下次早一点、换个说法再来。

## 代码结构

```
app/src/main/java/cn/companion/pet/
  core/      纯 Kotlin，全部有 JVM 单元测试
    scene/     场景判断、行为轨迹、卡壳探头
    rhythm/    开口节奏、被忽略升级
    probe/     人设、提示词拼装、回复解析、违禁词、调用上限
    memory/    Markdown 记忆、每晚整理、今日流水
    life/      生物钟、有分量的开口
    text/      屏幕文字清洗（WorkBuddy）
    bridge/    Gemini 桥接文本（WorkBuddy）
  net/       OpenAI 兼容客户端（不重定向、不重试、256 KiB 上限）
  data/      设置、统计、Keystore 存 API Key
  a11y/      无障碍感知、Gemini 自动发送实验
  overlay/   占位角色（Canvas 绘制）、漫画气泡、悬浮窗管理
  service/   常驻服务、大脑（PetBrain）、语音、震动、锁屏心跳、开机自启
  work/      每晚整理、看门狗
  ui/        设置页四个标签：开始、设置、记忆、统计
```

## 构建与测试

CI（`.github/workflows/companion-pet-android.yml`）每次推送都跑单元测试、lint、构建 debug APK 和设备测试 APK。

本地需要 JDK 17、Android SDK 35：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## 已知限制

- 角色是占位形象，正式版换原创 Lottie 动画，状态接口不变。
- 从开机或后台被拉起时，系统不允许前台服务带麦克风类型，这时按住说话可能失败，会提示改用打字；打开一次 App 再启动桌宠即可恢复。
- Gemini Live 没有公开的直达入口，只能打开 Gemini 后提示你点 Live 和共享屏幕。
- 真机行为（常驻、保活、语音识别、Gemini 拉起、自动发送）需要在 iQOO 12 上验证，见 VALIDATION.md。
