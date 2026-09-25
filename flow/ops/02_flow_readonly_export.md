# 给 Operit AI 的指令：整体流程线只读导出（v2）

> 用法：把"指令正文"整段复制给 Operit AI。本轮**只读**：只新建一个脚本文件和一个导出文件，不改其他任何东西。
>
> v2 改动：v1 让 Operit AI 把脚本逐字抄进文件，实测抄错了（sed 那行字符集变了，第 22 行多了一个 `a`）。v2 改成直接从 GitHub 下载脚本原文件，再用 sha256 校验，全程不需要抄写。
>
> 脚本源码：[`flow/ops/flow_export.sh`](flow_export.sh)，下载链接固定在提交 `9464af8`，文件内容不会变。
> 导出内容通过 shell 直接写进文件，不经过对话上下文，所以即使文件很大也不费 token。

---

## 指令正文

本轮只做一件事：在设备上**只读导出**整体流程相关的脚本、配置和日志，交给外部编程 Agent 分析。严格按步骤执行，每一步都要有工具回读证据；遇到任何与预期不符的情况，立即停止并报告，不要自行变通。

### 禁止事项

- 不修改、不删除、不移动 `/sdcard/Download/Operit/` 下任何已有文件。上一轮写坏的 `00_AGENT_HANDOVER/flow_export.sh` 保持原样，不运行、不删除。
- 唯一允许新建的是：
  - `/sdcard/Download/Operit/00_AGENT_HANDOVER/flow_export_v2.sh`（下载得到）
  - `/sdcard/Download/Operit/00_AGENT_HANDOVER/flow_export_<日期>.txt`（由脚本生成）
- **不要自己写或改脚本内容**，只能用下载工具获取。
- 不触发、不启用、不停用、不修改任何工作流。
- 不调用 `app_suspender` 的任何工具，不改变任何 App 的冻结状态。
- 不向任何会话发消息，不发通知。

### 步骤

**第 1 步：下载脚本。** 调用 `download_file`：

- url：`https://raw.githubusercontent.com/aozhenyu666-cmyk/test/9464af8312bb71df8ca3f637f34e6a9e4fb5aeb0/flow/ops/flow_export.sh`
- destination：`/sdcard/Download/Operit/00_AGENT_HANDOVER/flow_export_v2.sh`

下载失败就停止，报告错误原文。

**第 2 步：校验。** 用 `super_admin:shell` 执行：

```sh
sha256sum /sdcard/Download/Operit/00_AGENT_HANDOVER/flow_export_v2.sh; md5sum /sdcard/Download/Operit/00_AGENT_HANDOVER/flow_export_v2.sh; wc -l < /sdcard/Download/Operit/00_AGENT_HANDOVER/flow_export_v2.sh
```

必须同时满足：

- sha256 = `5b480f71b8cd499ab3f3dfd7ddf575a90bc273578e25fd9e1de72674922f7168`
- md5 = `1ba68463b9ff68731cc4fae2529a3d8b`（如果 `sha256sum` 命令不存在，就只看这一项）
- 行数 = `77`

任何一项不符就停止，报告三项的实际值，不要运行脚本。

**第 3 步：运行。** 用 `super_admin:shell` 执行：

```sh
sh /sdcard/Download/Operit/00_AGENT_HANDOVER/flow_export_v2.sh
```

它只输出四行：`OUT`、`SIZE`、`FILES`、`NOT_FOUND`。**不要读取导出文件的内容**（没必要，而且费 token）。

**第 4 步：实时工作流清单。** 调用一次 `workflow:get_all_workflows`，只整理成下面这张表（26 行左右），不做分析：

| 名称 | ID 前 8 位 | enabled | updatedAt | lastExecutionStatus | 总/成/败 |
|---|---|---|---|---|---|

**第 5 步：遗留工作流停用结果。** 如果之前已经执行过"停用 5 条遗留工作流"那份指令，把它的结果表原样贴出；没执行过就写"未执行"。

### 输出格式

只输出下面四部分，不要写其他内容：

1. 第 2 步三项校验的实际值，以及是否一致。
2. 第 3 步的四行原文。
3. 第 4 步的表。
4. 第 5 步的结果表或"未执行"。

最后一句提醒用户：把 `/sdcard/Download/Operit/00_AGENT_HANDOVER/flow_export_<日期>.txt` 这个文件发给外部 Agent。
