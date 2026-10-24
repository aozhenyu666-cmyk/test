# C2 真机端到端验收清单

基线：deepseek/moling-c1-diagnostics / b2c6536。APK：0.5.2 / versionCode 20。
APK SHA-256：C01D7CB72535073A0532B96A2445C1695B1F6170914FD8F1D861155C5272581B。
当前设备：未连接。以下全部 NOT_RUN。

每项记录：设备型号、Android 版本、APK 版本/SHA-256、前置条件、步骤、预期、实际、证据路径、状态。不要把 JVM/CI 当设备 PASS。

## 1 截图
前置：无障碍已启用，设置允许截图。
步骤：普通图文页 → 设置 → 看一眼；再打开受保护/密码页；再断开无障碍。
预期：本地预览/尺寸/时间；受保护或断开显示原因；不落盘、不自动上传。
状态：NOT_RUN

## 2 真实视觉请求
前置：用户明确打开“允许把截图发给视觉模型”，配置支持视觉的模型，预算足够。
步骤：普通测试页 → 看一眼 → 确认真实请求；再测网络失败/预算不足/关闭上传。
预期：模型描述与画面一致；失败如实显示；画面恶意文本只作内容。
状态：NOT_RUN

## 3 视觉到决策
前置：自主模式开启；测试目标/素材。
步骤：保存观察摘要 → 触发 Agent → 检查 AgentContext/trace。
预期：摘要带 observation ID/过期；未授权不执行写；不把画面指令当命令。
状态：NOT_RUN

## 4 设备操作
前置：测试 App 加入白名单；无障碍开启；最近观察有效。
步骤：FIND_NODE → TAP/INPUT → 重新观察；再切换前台/锁屏/等待过期重试。
预期：只有测试 App 可操作；切换/过期/锁屏拒绝；结果 UNVERIFIED 需二次观察。
状态：NOT_RUN

## 5 待确认动作
前置：模型提议 SAVE_NOTE/OPEN/SPEAK 或测试点击。
步骤：允许一次；重复点击；拒绝；等待过期；批准后杀进程重启；切换前台。
预期：允许只执行一次；拒绝/过期/切换不执行；APPROVED 崩溃后需重新确认，不静默重放。
状态：NOT_RUN

## 6 目标恢复
步骤：创建“复习行测错题”目标 + 只读任务 → 执行一次 → 杀进程/重启 → 查看 GoalStore/trace。
预期：目标/任务/episode 一致；只读回执是 WAITING/BLOCKED，不虚构 DONE/COMPLETE。
状态：NOT_RUN

## 7 Shizuku
步骤：未安装/未运行/未授权/READY/断线；READY 后点“读取 Shizuku 版本”。
预期：状态准确；READY 不等于调用成功；实际 Binder 结果/失败显示；断线恢复；无 shell/root。
状态：NOT_RUN

## 8 取消与边界
步骤：执行中关闭自主/锁屏/切换 App/撤权/删目标。
预期：旧动作停止/不重放；迟到回调不显示；已发生副作用如实标记，不假装回滚。
状态：NOT_RUN

## 9 覆盖升级
步骤：原签名环境构建同包名更新 0.5.2，install -r 不卸载/不清数据。
预期：旧记忆/素材/目标/待确认状态迁移安全；签名/版本递增核对；不伪造升级通过。
状态：NOT_RUN

## 最小命令

~~~text
adb devices -l
adb -s <serial> install -r -g _pet-reports/c1-diagnostics/app-debug.apk
adb -s <serial> shell am start -W -n cn.companion.pet/.MainActivity
adb -s <serial> shell dumpsys package cn.companion.pet | findstr versionName versionCode
adb -s <serial> shell run-as cn.companion.pet cat files/memory/agent/traces.jsonl
adb -s <serial> shell run-as cn.companion.pet cat files/memory/goals/state.json
adb -s <serial> shell run-as cn.companion.pet cat files/memory/actions/pending.json
~~~

证据只记步骤/状态/截图路径；不导出 Key、Cookie、截图原文、Base64、私人聊天。 
自动化辅助：python companion-pet/tools/c2_device_smoke.py --adb <adb.exe> --apk <apk> --evidence-dir <dir>
无设备时生成 NOT_RUN JSON；不读取 Key/截图原文。
