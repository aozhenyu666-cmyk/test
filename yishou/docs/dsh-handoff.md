# DSH Go 做题交接

一手负责出题和判卷；DSH Go 核对本次拦截的一次性凭证并打开目标应用。

## 协议 v1

| 名字 | 值 / 类型 |
|---|---|
| VERSION | `1` |
| 一手包名 | `com.yishou.app` |
| ACTION_TRAIN | `com.yishou.app.action.TRAIN_FOR_DSH` |
| ACTION_PASS | `io.github.ccch1mneyyy.dsh_app.action.YISHOU_PASS` |
| EXTRA_VERSION | `dsh_protocol`，Int |
| EXTRA_EVENT_ID | `dsh_event_id`，String，非空且 ≤64 字 |
| EXTRA_NONCE | `dsh_nonce`，String，16–64 字，原样带回，不记日志 |
| EXTRA_TARGET_PACKAGE | `dsh_target_package`，String，目标包名 |
| EXTRA_TARGET_LABEL | `dsh_target_label`，String，目标名称，最多 64 字 |
| EXTRA_REPLY_PACKAGE | `dsh_reply_package`，String，DSH Go 正式或带点后缀的包名 |
| EXTRA_PASS_MINUTES | `dsh_pass_minutes`，Int，1–120 |
| EXTRA_REF | `dsh_ref`，String，≤120 字，例如 `pass:17` |

请求带版本、事件、密钥、目标包名、名称、回复包名。
成功回复带版本、原事件、原密钥、放行分钟数和记录编号。
组名优先用目标应用已有的组，没有时用 `DSH Go`，放行时长走组设置。

## 手机上怎么用

1. 先装一手主线版（`com.yishou.app`），再装 DSH Go R4。
2. 在一手的关注名单里，去掉交给 DSH Go 拦截的应用（比如 B站），避免两个页面同时拦截。
3. 打开 B站，在拦截页点「去一手答一道题」→ 一手出题 → 判题通过 → 自动回到 B站。
4. 放弃做题时，点「回到拦截页」或返回键；不会发凭证。

## 放行和限制

- 只有判题通过、Pass 真正存库后的 `finishGrant` 能发回凭证；测试页、早上模式不会发送。
- DSH 模式不接受试满后选对的低能量放行，也不接受仅默写放行；需继续做题。
- 同一交接不会因页面重建而重复发送；发送失败会提示回拦截页重新开始，不自行打开目标应用。
- 答题时一手进程被系统杀掉，原凭证可能无法发出；DSH Go 的 15 分钟交接超时后记为「已拦截」，不会放行。
- 此分支需由 CI 验证测试与打包，完整跨应用流程仍待手机实测。
