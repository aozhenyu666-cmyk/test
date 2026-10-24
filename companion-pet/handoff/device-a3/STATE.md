# A3：受权限约束的设备操作（源码完成，设备未验证）

基线：`deepseek/moling-vision-a2` / `b97627ae8995aa2802be03a99cb0f12de0c4a46d`。
分支：`deepseek/moling-device-a3`。版本 0.3.9 / versionCode 12。仅修改 `companion-pet/`。

## 已完成

- 官方 Shizuku API `13.1.5` + `ShizukuProvider`；`ShizukuStatusResolver` 映射 NOT_INSTALLED/NOT_RUNNING/NO_PERMISSION/READY/UNSUPPORTED；设置页可刷新状态/请求权限。
- **不执行任意 Shell**，不申请 root/Sui；Shizuku 在本轮只做状态/授权，不作为万能执行器。
- `DeviceController`：状态、观察节点树、打开已安装应用、点击/滚动/输入/返回、读取最近观察；节点带 observation ID/过期时间/包名/bounds。
- `DevicePolicy`：锁屏、无障碍断开、应用不在授权名单、敏感系统/商店、观察过期、节点不存在、输入超长均拒绝。
- `DeviceActionAdapter` 接入现有 `ActionRunner`/`ActionSession`/账本：新增 8 个 ActionKind；`UNVERIFIED` 映射为 `UNAVAILABLE` 并带 `unverified=true`，不把 API 返回 true 当成功。
- 能力面板增加设备动作；设置页增加“允许 Agent 操作的应用”名单（空名单拒绝全部）。
- 查找/最近观察前先调用一次真实观察；点击/输入/滚动后只返回“需重新观察”，不声称页面已改变。

## 验证

```text
:app:testDebugUnitTest          PASS   301 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 13 warnings
:app:assembleDebug              PASS   28,747,683 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译
```

APK SHA-256：`1ECCAF16C858D823BFD9F89332D638B8BBEAB40F59F4F4CBC4E7A56F626E2E63`

新增 5 个 JVM 用例：Shizuku 状态映射、policy 锁屏/白名单/敏感/过期/节点/长度、观察过期、UNVERIFIED 不冒充成功、成功回执证据。基线 296 个测试保留。

## A3 验收状态

| 项 | 状态 |
|---|---|
| A3-T1 真实点击并观察变化 | **NOT_RUN**（无设备） |
| A3-T2 输入测试文字并核对 | **NOT_RUN** |
| A3-T3 Shizuku 安装/运行/授权/断线/重启 | **NOT_RUN**；仅状态映射与设置入口完成 |
| A3-T4 过期坐标/切换 App/重放/锁屏/权限撤销/停止 | **PARTIAL**：policy/JVM 覆盖；真实 Binder/无障碍未测 |
| A3-T5 单测/lint/构建 | **PASS** |

## 边界

- 未安装/未运行 Shizuku 时，Intent 与无障碍路径仍可用；本轮没有用 Shizuku 执行点击/输入/系统 API。
- 没有真机，不能声称“通用手部操作与 Shizuku 已设备验证”。
- 动作结果 UNVERIFIED 需要重新观察；已发生副作用不回滚。
- 没有发送/支付/账号/删除类高风险动作；没有新增任意 Shell。
