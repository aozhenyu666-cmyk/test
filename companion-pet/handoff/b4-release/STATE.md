# B4：事件触发、证据链与设备验收

基线：deepseek/moling-b3-shizuku-real / c430a1f。分支 deepseek/moling-b4-e2e-release。
版本 0.5.0 / versionCode 18。

## B4.1 触发器与预算

- 事件监听：新 NOTE/MATERIAL、目标到期、解锁补扫；1.2 秒防抖，空闲 5 秒补扫积压；不每秒请求模型。
- 视觉默认手动；上传默认关；截图/视觉各自受预算、白名单、排除、锁屏、受保护窗口限制。
- WorkManager 只做可行调度；不声称秒级或强制停止后必恢复。
- 已有自主开关、停止/锁屏取消；同一目标连续无进展不在本轮新增指数退避（B4 边界）。

## B4.2 证据链

- AgentTrace：触发/工具/状态/短原因/eventId/actionId。
- PendingAction：预览、TTL、一次性批准、前台校验、执行回执。
- GoalStore：Goal/Task/FollowUp/Episode，任务 WAITING/BLOCKED 不冒充 DONE。
- 设置页可看：自主轨迹、待确认动作、目标下一步；不保存屏幕原文/Key。

## B4.3 六个设备用例（全部 NOT_RUN）

1. 原生截图并本地预览；受保护画面/失败有原因。
2. 打开上传后调用真实视觉模型；画面恶意指令只作内容；确认解释与截图一致。
3. 授权测试 App：找节点 → 确认 → 点击/输入 → 二次观察核对。
4. 行测复习目标：读材料 → 提问 → 用户答复 → 保存进度 → 杀进程/重启恢复。
5. 真实 Shizuku READY → 读取固定 Binder 版本 → 停止/断线诊断；不可用降级。
6. 关闭自主/撤权/锁屏/换 App/删目标 → 停止动作；迟到回调不复活不重复写。

建议 adb：
```text
adb -s 10AE5U23LT004DN install -r -g app-debug.apk
adb -s 10AE5U23LT004DN shell am start -W -n cn.companion.pet/.MainActivity
adb -s 10AE5U23LT004DN shell run-as cn.companion.pet cat files/memory/agent/traces.jsonl
adb -s 10AE5U23LT004DN shell run-as cn.companion.pet cat files/memory/goals/state.json
adb -s 10AE5U23LT004DN shell run-as cn.companion.pet cat files/memory/actions/pending.json
```


每项记录：设备型号/Android、APK SHA、步骤、PASS/FAIL/NOT_RUN、截图/日志；不导出 Key/屏幕原文/私人聊天。

## B4.4 产物

- APK：28,927,907 bytes
- SHA-256：76726046D96ACCD6F9945D3A59A696617A63F91CB2B7E74DB55342BA0D93F3DB
- 版本：0.5.0 / versionCode 18
- 覆盖升级：保留应用数据，不卸载/不清数据；包名 cn.companion.pet，需原签名环境生成更新包。
- 旧记忆/素材/目标兼容：沿用原文件路径；B0 损坏文件不覆盖。

## 验证

:app:testDebugUnitTest          PASS   323 tests / 0 failures
:app:lintDebug                  PASS   0 errors / 13 warnings
:app:assembleDebug              PASS   28,927,907 bytes
:app:assembleDebugAndroidTest   PASS   仪器 APK 仅编译

## 状态

代码/JVM 通过；模拟器未运行；真机/真实 API/真实 Shizuku 全部 NOT_RUN。