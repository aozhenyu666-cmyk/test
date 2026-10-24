# 升级验证：已做什么，设备上还要做什么

## 当前证据

| 层次 | 本轮结果 | 能证明什么 |
|---|---|---|
| 原有与新增 JVM 测试 | 198/198，通过 | 纯逻辑、旧文件读取、迁移重试和 journal 恢复 |
| SDK 35 构建与 lint | 通过，0 errors / 19 warnings | 代码与两个 APK 可构建 |
| APK 对检查 | 通过；包名相同、签名相同、versionCode 3 → 4 | 本次两份测试包具备覆盖安装的静态条件 |
| 升级脚本保护测试 | 3/3，通过 | 拒绝物理设备与已有安装，覆盖失败不退回卸载/清数据 |
| 模拟器 `adb install -r` | **未执行** | 没有连接设备，也没有 `/dev/kvm` |
| 用户手机 / iQOO 升级 | **未执行** | 不作已验证结论 |

**签名范围很重要：** 本轮两份 APK 都由同一个构建环境产生。用户手机上的旧包若由另一台机器的 debug keystore 签名，新包可能无法覆盖安装。遇到签名不符，应在原构建环境用原签名构建新代码；不要为了安装测试包卸载用户原应用或清数据，也不要把 keystore、API Key 或聊天交给审查者。

## 1. 先检查两个 APK，不操作设备

要求：Python 3.10+、JDK、Android SDK build-tools。设置好 JDK 17 和 SDK 35 后，在 `companion-pet/` 执行：

```sh
python tools/verify_upgrade.py --sdk /path/to/android-sdk --old-apk /path/to/old.apk --new-apk /path/to/new.apk --check-apks --report apk-pair.json
```

Windows 也使用相同参数，把路径换成本机实际路径并按需要加双引号。脚本直接调用 Java 的 apksigner.jar，不依赖 bash 或插件。

检查内容：包名必须是 `cn.companion.pet`；新 versionCode 更大；签名校验成功且签名证书一致；记录两份 APK 的 SHA-256。`device_upgrade: not_executed` 是正常且准确的输出，不能改成 passed。

## 2. 在干净模拟器上运行覆盖安装

启动一个 API 35 模拟器。它必须没有安装 `cn.companion.pet`；脚本若发现已有安装会停止。不要把测试目标换成有用户数据的物理手机。

```sh
python tools/verify_upgrade.py --sdk /path/to/android-sdk --old-apk /path/to/old.apk --new-apk /path/to/new.apk --serial emulator-5554 --report emulator-upgrade.json
```

脚本会：

1. 检查目标确为模拟器，并检查没有已有安装。
2. 安装旧包；写入固定的**合成**人设、profile、旧 facts.json、today.md 和不含 Key 的设置。
3. 对新包执行 `adb install -r -t`；启动主界面。
4. 只核对本次创建的五份合成文件的校验和，不读取任何凭证或私人聊天。
5. 核对旧 today.md 的迁移备份；强停后再次启动，检查事件导入幂等。

脚本不会自动卸载或清理现有应用。它也不覆盖全部升级场景：权限保留、OEM 后台服务、悬浮窗、键盘和用户原有签名还需要设备验证。运行失败时保留 JSON 与具体失败步骤，不手动把结果文件改成成功。

## 3. 本地旧数据回归

`LegacyUpgradeTest` 包含不带新 origin 字段的旧事实、旧状态/话题格式、迁移中断后重试，以及没有删除版本的旧 journal。旧格式 journal 无法证明删除版本一致时安全作废，保留事件待重新整理，不覆盖用户档案。

```sh
./gradlew :app:testDebugUnitTest --tests cn.companion.pet.core.memory.LegacyUpgradeTest
```

这个测试是**文件兼容回归**，不是设备 APK 升级证据。完整构建命令仍以 `STATE.md` 为准。

升级脚本的保护逻辑可单独运行：`python tools/test_verify_upgrade.py`。它使用假的 ADB 响应检查拒绝条件，不冒充模拟器安装结果。

## 4. 真机需要回传的最小结果

- 安装前后应用版本、两个 APK 的 SHA-256 与签名是否一致；不回传 Key 或私有存储全量包。
- 旧包中用合成资料建立一条事实、一条念头、一个已读信状态；覆盖升级后分别核对。
- 人设、自定义模型名称、非敏感设置保留；只报告 Key 是否仍可由用户正常使用，不展示其值。
- 启停、后台恢复、锁屏返回、气泡纯文字、大字体和输入法；每项有“通过/失败/未执行”及截图或步骤。
- 用户更正/遗忘后，整理、重启、再提同一问题；确认没有旧内容复活。

如果只有构建日志和同签名检查，交付结论应为“具备下一轮设备验证条件，设备升级待执行”。
