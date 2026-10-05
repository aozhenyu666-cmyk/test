# A｜A-026｜强约束真机验证：Operit 被杀后能否按时解冻

**用户要做的**（约 3 分钟）：第 2 步完成后，按你的提示在系统设置里"强制停止" Operit，等 3 分钟再重新打开 Operit。
**目的**：严格模式要真锁，前提是"到期自动解冻"不依赖 Operit 进程。这一轮只验证这一件事。
**交付目录**：`/sdcard/Download/Operit/acceptance/round1/A-026/`
**不做**：不升级任何插件，不改 S4/P4/Strict、名单和规则，不碰重度 App。

## 1. 选测试对象并做预检（只读）

- 选一个与娱乐、通讯、工作都无关、并且用户同意临时冻结 3 分钟的 App，例如系统计算器。记下包名。它不能在 UP-08 保护名单里，也不能是 Operit。
- 用 `super_admin:shell` 执行 `id`、`which pm`、`dumpsys package <测试包名> | grep -m1 suspended=`。记录结果，以及当前 Shizuku 是否在运行。
- 预检失败（shell 不可用、该 App 已被冻结）就停止，回报原因。

## 2. 先挂解除，再冻结

用 `super_admin:shell` 依次执行下面两条，`<P>` 换成测试包名。顺序不能颠倒：先挂解除，再冻结。

```sh
nohup sh -c 'sleep 120; pm unsuspend --user 0 <P>; echo "released_at=$(date +%s)" > /sdcard/Download/Operit/acceptance/round1/A-026/probe_release.txt; dumpsys package <P> | grep -m1 suspended= >> /sdcard/Download/Operit/acceptance/round1/A-026/probe_release.txt' >/dev/null 2>&1 &
echo "armed_at=$(date +%s)"; pm suspend --user 0 <P>; dumpsys package <P> | grep -m1 suspended=
```

- 确认输出 `suspended=true`，记下 `armed_at`。
- 然后请用户：打开 设置 → 应用 → Operit → 强制停止；等 3 分钟；再打开 Operit。

## 3. 重新打开后读结果

- 读取 `probe_release.txt`，并执行 `dumpsys package <P> | grep -m1 suspended=`。
- **通过**：文件存在；`released_at - armed_at` 在 120～180 秒之间；当前 `suspended=false`。
- **不通过**：立即执行 `pm unsuspend --user 0 <P>` 把测试 App 恢复，读回确认 `suspended=false`，再写明是哪一环没做到（文件不存在 / 时间不对 / 仍是冻结状态）。

## 4. 第二种情况（可选，用户愿意时再做）

重复第 2、3 步，但这次把"强制停止 Operit"换成"关闭无线调试，或在 Shizuku 里停止服务"，3 分钟后再恢复。这一项验证的是 Shizuku 停掉时，解除是否还能执行。

## 回执

开头 `A｜A-026｜阶段`。第一行只写 **通过** 或 **不通过**，然后给出 `armed_at`、`released_at`、最终 `suspended=` 的原文，以及每条命令的原始输出。最后确认测试 App 已恢复正常。
