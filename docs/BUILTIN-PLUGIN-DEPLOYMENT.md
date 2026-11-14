# 插件与工作流怎么部署到 DSH Go

手机端模型编程能力差，所以**不靠它写代码**。一个能力要么在 PC 上做成预置插件，随
APK 发出去；要么是一段预置工作流（技能）；只有在明确接受风险时，才让模型自己写。

按可靠性从高到低：

| 方式 | 谁写代码 | 需要联网 / pnpm | 适合 |
|---|---|---|---|
| ① 预置插件（本文） | 开发机 | 否 | 产品核心能力，必须稳定 |
| ② 插件市场安装 | 插件作者 | 是 | 第三方扩展 |
| ③ 模型现场写插件 | 手机端模型 | 否 | 一次性小界面，实验性 |

## ① 预置插件：随 APK 发布，启动即挂载

关系内核走这条路。它不经过插件市场、不装 npm 包、不需要 root，也不写用户 profile。

### 运行时发生了什么

1. 插件源码放在 `app/assets/engine/plugins/<id>/`，由 Flutter 打进 APK。
2. 每次引擎启动前，`runtime/BuiltinPluginGuest.kt` 从 APK 资源把文件复制到 rootfs 的
   可写区：`/opt/dsh-app/plugins/<id>/`。内容相同的文件不重写。
3. 同一个类写一份 overlay：`/opt/dsh-app/plugins/plugins.patch.yml`，按绝对路径插入每个
   插件：

   ```yaml
   - insert:
       - id: dsh-go-relationship
         name: /opt/dsh-app/plugins/dsh-go-relationship/lib/index.js
   ```

4. `runtime/DshProcess.kt` 在正常的 `dsh web` 启动命令里追加
   `--patch /opt/dsh-app/plugins/plugins.patch.yml`。DSH 会把多个 `--patch` 叠起来。
5. 安全模式、文件没写完、或任何一步失败时，**整份 overlay 都不传**，DSH 照常启动，只是
   没有这些工具；不会出现“半个插件”把引擎拖死。

插件从绝对路径加载，所以包里必须有 `package.json`（`"type": "module"`），否则 `.js`
会被当成 CommonJS。宿主依赖（`@deepseek-ai/dsh-tools`）不打包进 APK：
`lib/hostdeps.mjs` 按顺序找宿主自己的模块树，包括运行时里的
`/opt/dsh/node_modules/@deepseek-ai`、`DSH_GO_RUNTIME_ROOT/opt/dsh/node_modules`、从
`process.argv[1]` 向上回溯的 `node_modules`、profile 和 shims。同步 `require` 拿不到
ESM-only 的包时，再走动态 `import`。

### 加一个新预置插件

1. 在 `examples/plugins/<id>/` 开发和测试（单测用 `node --test` 跑，不需要手机）。
2. 机械复制到 `app/assets/engine/plugins/<id>/`；只带运行需要的文件，别带 test/sim。
   源目录是唯一事实来源，复制后用 `sha256sum` 核对，防止两边漂移。
3. `app/pubspec.yaml` 的 `assets:` 里补目录。**Flutter 不会自动递归 assets 子目录**，
   每一层都要单独列，例如：

   ```yaml
   - assets/engine/plugins/
   - assets/engine/plugins/<id>/
   - assets/engine/plugins/<id>/lib/
   - assets/engine/plugins/<id>/lib/core/
   ```

4. `BuiltinPluginGuest.PLUGINS` 里加一条：`id`、入口相对路径、要复制的每个文件。
5. 跑 `BuiltinPluginGuestTest`（overlay 命令行与 YAML），再构建 APK。
6. 真机/模拟器启动后看日志：插件自己打印 `[relationship] registered 7 tools`；失败时
   `RuntimeController` 会记 `bundled plugins not written`。

不要把这个插件再 `dsh plugin add` 进 profile：同一个包挂两次会让客户端组合失败
（和 `dsh-go-bridge` 的规则一样）。

## 工作流：预置技能，不是现场生成的脚本

工作流是**一段写给模型看的流程**，不是代码。它放在
`app/assets/skills/<name>/SKILL.md`，由 `ManagedSkillInstaller` 在每次引擎启动时写到
`$DSH_HOME/skills/<name>/`，DSH 会自动发现；用户删掉 front matter 里的
`# dsh-go:managed` 行，这个目录就归用户，App 不再覆盖。

关系内核的技能 `dsh-go-relationship` 规定：每轮怎么 `relationship_observe`、什么时候
`relationship_commit`、主动开口前先 `mcp__phone__now` + `relationship_check`、晚上
`relationship_nightly`、解决后 7 天静默。模型只照做并调用预置工具，不写代码。

加一个工作流：

1. 写 `app/assets/skills/<name>/SKILL.md`，front matter 带 `name`、`description`、
   `whenToUse` 和 `# dsh-go:managed`。
2. 写 `manifest.json`：`{"name": "...", "files": {"SKILL.md": "SKILL.md"}}`。
3. `pubspec.yaml` 加 `assets/skills/<name>/`。
4. `ui_skill.dart` 的 `extraSkillLoadersProvider` 加一个 loader（或在
   `ManagedSkillRequest.names` 允许列表里加名字，走原生安装的路径）。
5. 需要工具就同时做一个预置插件；工作流本身没有执行能力。

## ② 插件市场安装

现有路径：设置 → 插件 → 市场，App 通过宿主 RPC `pluginManager/installBundle` 让 pnpm
安装。需要网络和 registry，插件作者负责兼容性。适合非核心的第三方能力；不要把产品
关键路径押在它上面。

## ③ 让手机端模型现场写插件

App 自带 `dsh-go-plugin` 技能，能教模型生成一个小的原生界面插件。它适合“用户临时想要
一个按钮/卡片”的场景，不适合生产核心逻辑：没有编译期检查、没有回归测试、模型一改就
可能把配置写坏。生产能力的顺序永远是：先预置，再市场，最后才让模型写。

## 已验证（Android 14 x86_64 模拟器）

- `flutter build apk --debug --target-platform android-x64` 出的 APK 里，
  `flutter_assets/assets/engine/plugins/dsh-go-relationship/` 12 个文件齐全。
- 首次启动后 rootfs 出现 `/opt/dsh-app/plugins/dsh-go-relationship/` 与
  `plugins.patch.yml`；启动命令含
  `--patch /opt/dsh-app/plugins/plugins.patch.yml`。
- 引擎日志：`[relationship] registered 7 tools`；DSH 正常监听 3080。
- `$DSH_HOME/skills/dsh-go-relationship/SKILL.md` 由托管安装写入。
- 强制停止后再次启动，插件仍只挂载一次、仍注册 7 个工具（文件内容未变，不重复复制）。
- 单测：`tooling/builtin-plugin-test.ps1` 4/4；插件自身 `node --test` 4/4；
  Flutter `relationship_skill_test.dart` 2/2。

## 失败模式与排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 日志 `bundled plugins not written` | APK 资源缺文件、pubspec 少列目录、rootfs 权限 | 看 Flutter assets 里是否有 `flutter_assets/assets/engine/plugins/<id>/lib/index.js` |
| 插件加载了但没有工具 | `dsh-tools` 没找到，或返回的不是函数 | 看插件日志；确认 `/opt/dsh/node_modules/@deepseek-ai/dsh-tools` 存在 |
| `Cannot use import statement outside a module` | 没复制 `package.json` | 确认它在 `PLUGINS.files` 里 |
| 同一个插件行为出现两次 | 既预置又 `dsh plugin add` 进 profile | 从 profile 移除 |
| 安全模式没有工具 | 设计如此：安全模式不加载任何 App overlay | 正常启动再试 |
