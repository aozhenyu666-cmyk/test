# C2 真机端到端验收清单

基线：`ccr-2899d69f-xlzjh6`（已合并 C0–C2 与 0.5.1–0.5.3 修复），APK **0.5.4 / versionCode 22**。
APK 来源：https://github.com/aozhenyu666-cmyk/test/releases/download/tongzhuo-latest/tongzhuo.apk ，SHA-256 以 Release 说明为准。
签名：仓库固定钥匙 `companion-pet/keystore/tongzhuo-debug.jks`。设备上已有的 0.5.2（SHA-256 0659B7…，来自同一个 Release）同钥匙，可直接 `install -r` 覆盖；
用本机默认调试钥匙构建的包（如旧 C1 的 C01D7C…）签名不同，**装不上，也不要卸载**，请从本分支构建或直接用 Release 包。
以下未执行项均为 NOT_RUN。

每项记录：设备型号、Android 版本、APK 版本/SHA-256、前置条件、步骤、预期、实际、证据路径、状态。不要把 JVM/CI 当设备 PASS。

## 1 截图
前置：无障碍已启用，设置允许截图。
步骤：在普通图文页**点桌宠 → 看一眼**（不是设置页：设置页里前台是桌宠自己，按设计拒绝）；再打开受保护/密码页；再断开无障碍。
预期：截图成功后按配置给出结果或明确原因；在设置页点按钮显示“当前是桌宠自己的界面”；受保护页“没截到”；不落盘、未开上传时不发送。
状态：NOT_RUN

## 2 真实视觉请求
前置：用户明确打开“允许把截图发给视觉模型”；设置 → 看一眼 → 点“千问看图”（或 GPT）并填该服务商的视觉 Key、保存；预算足够。
注意：0.5.2 及以前视觉模型名写死为 deepseek-flash 且无法修改，DeepSeek 接口多半不接收图片，所以真机上的“看了，但视觉模型没给出可用的结果”是配置问题，不是截图失败；0.5.4 会直接写出失败原因。
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
预期：模型提议时**悬浮气泡**出现“允许这次/不用”；允许只执行一次；拒绝/过期/切换不执行；批准后杀进程，重启后该条显示“批准后程序中断，结果未知”（INTERRUPTED），只能点“重新确认”再执行，不静默重放。
状态：NOT_RUN

## 6 目标恢复
步骤：创建“复习行测错题”目标 + 只读任务 → 执行一次 → 杀进程/重启 → 查看 GoalStore/trace。
预期：目标/任务/episode 一致；只读步骤回执 SUCCESS 后该步 DONE、不再重复执行；失败为 BLOCKED，需点“重试”；目标 COMPLETED 只能由用户点“确认目标完成”。
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
adb -s <serial> install -r tongzhuo.apk   # 不加 -g；vivo 手机上要在弹窗里点“继续安装”，开发者选项里打开“USB 安装”
adb -s <serial> shell am start -W -n cn.companion.pet/.MainActivity
adb -s <serial> shell dumpsys package cn.companion.pet | findstr versionName versionCode
adb -s <serial> shell run-as cn.companion.pet cat files/memory/agent/traces.jsonl
adb -s <serial> shell run-as cn.companion.pet cat files/memory/goals/state.json
adb -s <serial> shell run-as cn.companion.pet cat files/memory/actions/pending.json
~~~

证据只记步骤/状态/截图路径；不导出 Key、Cookie、截图原文、Base64、私人聊天。 
自动化辅助：python companion-pet/tools/c2_device_smoke.py --adb <adb.exe> --apk tongzhuo.apk --evidence-dir <dir>（离线自测：python companion-pet/tools/test_c2_device_smoke.py）
脚本会拉回设备上的 base.apk 比对 SHA-256，install 结果写成 PASS / PASS_SAME_APK_ALREADY_INSTALLED / FAIL_DIFFERENT_APK_ON_DEVICE。
无设备时生成 NOT_RUN JSON；不读取 Key/截图原文。
