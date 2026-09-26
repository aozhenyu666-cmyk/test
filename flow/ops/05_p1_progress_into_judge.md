# 给 Operit AI 的指令：P1 让用户进展进入判断官

> 用法：用户批准后，把"指令正文"整段复制给 Operit AI。和 ops/04（修判断官会话）互不依赖，先后都可以；但要等 ops/04 做完，判断官真正开始工作，才能看到效果。
>
> **改了什么**（3 个脚本，改动见仓库 `flow/patches/p1/`，`orig/` 是设备上的原文件，`new/` 是新版）：
> 1. `judge/gen_pack.sh`：证据包加入"用户本人说的进展"，读 `progress/*.jsonl` 里 `origin=REAL_USER`、最近 24 小时的记录，最多 5 条。按时间戳判断新旧，不依赖文件名日期，所以不受时区问题影响。
>    - 进展 id 放进哈希区：你一报新进展，判断官就会重新判断。
>    - 可读版（"2 分钟前 提交：投了两家"）和判读准则放在哈希区外："已说完成或暂停的事不得再判跑偏""说卡住时优先判 STUCK"。
> 2. `judge/hash_gate.sh`：放行时只写"待提交"哈希，不再在判断之前就把证据标记成已处理。
> 3. `judge/judge_save2.sh`：判断官给出答复后才提交哈希。判断失败的证据下一轮会重试，判断过的证据不会重复花钱。
>
> 模拟测试（mawk 和 bwk awk 两种实现）：进展筛选、排序、引号转义、无进展时的输出、重试和跳过逻辑都通过；原有段落的输出逐行一致。这些是 VERIFIED（模拟宿主），还没在真机上跑过。

---

## 指令正文

本轮替换 `/sdcard/Download/Operit/judge/` 下的 3 个脚本：`gen_pack.sh`、`hash_gate.sh`、`judge_save2.sh`。用户已批准。严格按步骤执行，每一步都要有工具回读证据；遇到任何与预期不符的情况，立即停止并报告，不要自行变通。

### 禁止事项

- 不修改这 3 个脚本以外的任何文件，不自己编辑脚本内容，新版只能用 `download_file` 下载。
- 不触发、不修改任何工作流。
- 不调用 `app_suspender` 的任何工具，不向任何会话发消息。

### 步骤

**第 1 步：确认设备上的原文件没变过。** 用 `super_admin:shell` 执行：

```sh
cd /sdcard/Download/Operit/judge && sha256sum gen_pack.sh hash_gate.sh judge_save2.sh
```

必须完全等于：

| 文件 | 预期 sha256 |
|---|---|
| gen_pack.sh | `13015b651ed328f33d1cab33b4d9f5349e3ecc2b6dd39d1bb9a0c1c8f2513f3b` |
| hash_gate.sh | `83fd294aa1cb2a7a8cc3103af70c95a7cd8fbc692bbc5e14b314c2631dabc759` |
| judge_save2.sh | `a90e134939d66d063b633ca62e252d8434c2df02a7100781c6a89589a17f53e6` |

有任何一个不同就停止，说明文件在导出之后被改过，报告三个实际值。

**第 2 步：备份。** 用 `super_admin:shell` 执行：

```sh
B=/sdcard/Download/Operit/00_AGENT_HANDOVER/p1_backup_20260926; mkdir -p $B && cd /sdcard/Download/Operit/judge && cp gen_pack.sh hash_gate.sh judge_save2.sh $B/ && cd $B && sha256sum *.sh
```

三个值必须和第 1 步一致。

**第 3 步：下载新版到暂存目录。** 调用 `download_file` 三次：

| url | destination |
|---|---|
| `https://raw.githubusercontent.com/aozhenyu666-cmyk/test/a4807c6f239445b2abf758c694719d8ec14aac72/flow/patches/p1/new/gen_pack.sh` | `/sdcard/Download/Operit/00_AGENT_HANDOVER/p1_new/gen_pack.sh` |
| `https://raw.githubusercontent.com/aozhenyu666-cmyk/test/a4807c6f239445b2abf758c694719d8ec14aac72/flow/patches/p1/new/hash_gate.sh` | `/sdcard/Download/Operit/00_AGENT_HANDOVER/p1_new/hash_gate.sh` |
| `https://raw.githubusercontent.com/aozhenyu666-cmyk/test/a4807c6f239445b2abf758c694719d8ec14aac72/flow/patches/p1/new/judge_save2.sh` | `/sdcard/Download/Operit/00_AGENT_HANDOVER/p1_new/judge_save2.sh` |

**第 4 步：校验新版。** 用 `super_admin:shell` 执行：

```sh
cd /sdcard/Download/Operit/00_AGENT_HANDOVER/p1_new && sha256sum gen_pack.sh hash_gate.sh judge_save2.sh
```

必须完全等于：

| 文件 | 预期 sha256 |
|---|---|
| gen_pack.sh | `4cb9f253fde7932d6a71d697f370a08ae4bb1f51c3ec0d2f9b41e15ac6c3ec8c` |
| hash_gate.sh | `5c753e2448b33686b0fa01aaed6431f9e8650b96279042f8148b3a4fc6718cc0` |
| judge_save2.sh | `d21ff929627c711aaea841c3479b0e9f28d584379f645f73222f1e561cc9e4de` |

不一致就停止，**不要**进行第 5 步。

**第 5 步：替换并回读。** 用 `super_admin:shell` 执行：

```sh
cp /sdcard/Download/Operit/00_AGENT_HANDOVER/p1_new/*.sh /sdcard/Download/Operit/judge/ && cd /sdcard/Download/Operit/judge && sha256sum gen_pack.sh hash_gate.sh judge_save2.sh
```

三个值必须等于第 4 步的预期值。

**第 6 步：只读试跑证据包。** `gen_pack.sh` 只往屏幕输出，不写文件。用 `super_admin:shell` 执行：

```sh
sh /sdcard/Download/Operit/judge/gen_pack.sh 2>&1 | sed -n '/用户本人进展指纹/,/才可判 DRIFT_RISK/p'
```

原样贴出输出。预期能看到"用户本人进展指纹""用户本人说的进展""进展判读准则"三段；最近 24 小时没报过进展时，会显示"（最近 24 小时无）"。

### 回滚（只在用户要求时执行）

```sh
cp /sdcard/Download/Operit/00_AGENT_HANDOVER/p1_backup_20260926/*.sh /sdcard/Download/Operit/judge/
```

### 输出格式

只输出下面这张表、第 6 步的原文和一句结论：

| 步骤 | 结果 | 证据（实际值或错误原文） |
|---|---|---|

`结果` 只能是：`OK`、`STOPPED`、`FAILED`（附错误原文）。
