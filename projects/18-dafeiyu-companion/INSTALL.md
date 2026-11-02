# 安装、配置、回滚

适用：Windows 电脑上的 DSH 桌面版（审计时是 0.2.1-rc.2，profile 叫 `desktop`）。

## 先说清楚：会动什么、不会动什么

- **会换掉**：`dsh-speak-up`（0.2.7 → 0.3.0-dafeiyu.1）和 `dsh-fish-memory`（1.4.3 → 1.6.0-dafeiyu.1）两个插件的代码。
- **不会动**：记忆数据、心情数据、会话、人格和其他插件。新版读的还是同一套文件，格式没变。
- **会新增**：`~/.dsh/fish-memory/auto-extract.json`（自动提取记到哪了）。另外 `fish-memory-config.json` 里可能多一个 `自动提取` 字段，`speak-up-config.json` 里多 4 个新设置。旧版插件会忽略这些，回滚不受影响。
- 尽管如此，**装之前还是先备份**。`install.ps1` 会自动备份；手动装就按第 1 步做。

---

## 方法 A：交给你的 DSH 自己装（最省事）

把 `dist/` 里的两个 `.tgz` 和这份 `INSTALL.md` 放到电脑上，比如 `D:\dafeiyu\`。然后在 DSH 里对大肥鱼（或任何一个会话）说：

> 帮我装两个插件。文件在 `D:\dafeiyu\`：`dsh-speak-up-0.3.0-dafeiyu.1.tgz` 和 `dsh-fish-memory-1.6.0-dafeiyu.1.tgz`。
> 先按 `D:\dafeiyu\INSTALL.md` 的「1. 备份」把数据备份好，再用 `dsh plugin --profile desktop add <tgz 的完整路径>` 装这两个包。
> 装不上就告诉我报错原文，别自己去改 profile 的 package.json。装完提醒我重启 DSH。

## 方法 B：一键脚本

在放着这些文件的文件夹里，打开 PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

脚本会：① 把数据备份到 `~/.dsh-backup-<时间>`；② 用 `dsh plugin add` 装两个包；③ 打印下一步要做什么。
它**不会**改任何配置文件，配置要按第 3 步自己点。

## 方法 C：手动

### 1. 备份

PowerShell：

```powershell
$t = Get-Date -Format 'yyyyMMdd-HHmmss'
$b = "$env:USERPROFILE\.dsh-backup-$t"
New-Item -ItemType Directory -Path $b | Out-Null
foreach ($x in 'fish-memory','fish-memory-backup','feiyu-mood','fish-memory-config.json','speak-up-config.json','her-words-config.json') {
  $p = Join-Path "$env:USERPROFILE\.dsh" $x
  if (Test-Path $p) { Copy-Item $p -Destination $b -Recurse }
}
Copy-Item "$env:USERPROFILE\.dsh\profiles\desktop\package.json" -Destination $b -ErrorAction SilentlyContinue
"备份在：$b"
```

### 2. 安装

```powershell
dsh plugin --profile desktop add D:\dafeiyu\dsh-speak-up-0.3.0-dafeiyu.1.tgz
dsh plugin --profile desktop add D:\dafeiyu\dsh-fish-memory-1.6.0-dafeiyu.1.tgz
```

**装完重启一次 DSH。**

如果 `dsh plugin add` 不接受本地文件路径（这一点还没在你机器上试过），按顺序试：

1. 路径前面加 `file:`：`dsh plugin --profile desktop add file:D:/dafeiyu/dsh-speak-up-0.3.0-dafeiyu.1.tgz`
2. 在 DSH 的 **设置 → 插件 → 安装** 里填同样的路径；
3. 用 GitHub 链接装（需要仓库是公开的，或者你的 DSH 能访问它）：
   `https://github.com/aozhenyu666-cmyk/test/raw/ccr-92adf522-sstar5/projects/18-dafeiyu-companion/dist/dsh-speak-up-0.3.0-dafeiyu.1.tgz`
4. 实在不行就按上游 README 的「手动装」：把 `.tgz` 解压后的 `package` 文件夹改名，覆盖到
   `%USERPROFILE%\.dsh\profiles\desktop\node_modules\dsh-speak-up\`（fish-memory 同理）。这两个插件之前已经装过，profile 里的登记不用改。

### 3. 配置（最关键，不配等于没装）

装完重启后，在 DSH 设置里：

**主动搭话（dsh-speak-up）**
- 「目标会话」只勾**大肥鱼主会话**（审计时 id 以 `session-429facb2` 开头）。现在它显示「冷的，掷中会叫醒」是正常的。
- 「总开关」打开（审计时它是关着的，这是主动发言一直不工作的原因之一）。
- 「唤醒冷会话」保持打开；「叫醒后等几秒」先用 5。
- 「试运行」**先开着**，看一两个小时的记录，确认时机合适了再关。也可以直接按「真发一次」当场验证（见 VERIFY.md）。
- 「心情联动」默认开、强度 0.5，先不用动。

**记忆（dsh-fish-memory）**
- 确认大肥鱼主会话的记忆开关是**开**的（默认开，除非以前手动关过）。
- 自动提取默认开（每 3 句提一次，闲 10 分钟也提）。想改的话，编辑 `~/.dsh/fish-memory-config.json`，加上或修改：
  ```json
  "自动提取": { "开": true, "每几句": 3, "闲置分钟": 10, "每次最多": 4 }
  ```
  ⚠ 用记事本「另存为 UTF-8」，**不要带 BOM**；别用 PowerShell 的 `Set-Content -Encoding UTF8`，它会带 BOM。
- 自动提取和模型自己调 remember 共用「每天最多记几条」这个配额（默认 8）。如果觉得记得太少，到记忆设置页把它调大。

**心情（dsh-feiyu-mood，不需要换版本）**
- 确认大肥鱼主会话**绑在某一份心情上**（会话模式下要在设置里勾它；工作区模式下要把那个工作区设好）。没绑的会话不会判断心情。
- 「自动判断」保持打开。

**避免两个调度器打架**（交接文档里的建议）
- SurfCore 的 pet-purpose（另一个主动发言调度）先关掉，只让 speak-up 负责主动开口。dsh-pet 的「碎碎念」也建议先关，等这条链路稳定了再开。

---

## 回滚

两个插件的旧版本都在 npm 上，可以直接装回去：

```powershell
dsh plugin --profile desktop add dsh-speak-up@0.2.7
dsh plugin --profile desktop add dsh-fish-memory@1.4.3
```

重启 DSH。如果数据出了问题，把备份目录里的文件拷回 `~/.dsh/`。拷之前先关掉 DSH。

自动提取写进去的记忆和普通记忆格式一样，只是 `source` 字段写的是「自动提取」。回滚到旧版后，这些记忆照样能用。
