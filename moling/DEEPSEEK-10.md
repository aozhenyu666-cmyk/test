# 第十批：联网猎取、来源记忆与有预算的实质代工

> 配套 [总执行计划](DEEPSEEK-ALL.md)。覆盖 B1/B2/B3、H2/H3。目标是“分享/搜索→可引用材料→案板草稿→候选记忆”，并让她能在明确开启后主动准备一块实物。
> 不是只做配置页或把模型凭记忆回答改名成搜索。本批核心与 App 都要实现，不等待 Claude 提供新接口。

## 0. 基线和范围

先完成/纳入总计划 P0，保留 C9 的 `Workspace.kt/Workbench.kt/Prework.kt` 与 I9 `BenchScreen`。分支建议 `deepseek/moling-web-memory`，包含工作分支最新提交和已核对的前置 PR。

允许：新增 core/web、core/intake、core/cycle、core/atlas 的候选/检索模块；必要修改 Workbench、Prework、Graph、Think 的接线和 BrainHost；新增 app/web、app/intake、app/cycle、来源存储与页面；调整设置/分享/案板/调试。
不改总控、Charter、授权书、包名/签名；不加屏幕、麦克风、日历权限。网络沿用已有 INTERNET。

先读 [契约](../partner-blueprint/v13-contracts.md)。现有 `Material` 仅有材料文本/种类等字段，不能假定已有网页来源库。

## T1：生产来源模型、存储与查看（C+I）

建立 SourceRef/SourceDocument/SourceAttachment：ID、渠道、原站URL、标题、实际抓取时间、引用片段、contentHash、获取状态、内容是否完整、可选会话和焦点。引用证明“这段话来自哪里”，不等于证明这段话是真的。

C9 的 `ArtifactBlock.sourceIds` 已识别 Material/Atlas ID。初版保留这些 ID；用 Attachment 将真实 Material.id 映射到 SourceRef，避免把新 s-ID直接丢进旧解析器而被过滤。采用过的正文仍引用真实存在的材料，不拼造引文。

持久化需事务/幂等记录；大正文不塞进 BrainState 或 sayQueue。重复来源复用 hash；旧来源对象缺字段可读；删除原文后标签显示“原文已删除”。提供查询原文/引用/来源URL的方法，先给核心单测，再固定 App 接口。

拟新增 BrainHost API（先实现再用于 UI，复用 workspace.PortResult）：

```kotlin
suspend fun benchSearch(focusId: String, query: String): PortResult<List<SourceRef>>
suspend fun benchFetch(focusId: String, url: String): PortResult<SourceRef>
suspend fun benchAttachSource(focusId: String, sourceId: String): PortResult<Material>
suspend fun benchSource(materialOrNodeId: String): SourceView?
```

SourceView 为只读投影：标题、原文/片段、URL、渠道、时间、待核标记。Atlas 来源走现有节点，不伪造网页地址。

## T2：一个真的搜索适配器 + RSS + 正文（I）

按 WebPort 完整实现 search/fetch/readFeed，至少交付一个可配置搜索提供者（默认建议 Brave Search）、RSS读取与HTML正文提取；可选 Jina Reader 转换正文。实现时查所用服务当前官方接口，不把正文reader当搜索、不假设永久免费或免密。

设置页分开配置搜索提供者/凭据、可选reader和RSS订阅；明确“测试连接/额度或错误/关闭”。密钥沿用现有安全存储方式，不进备份、导出、提示词和日志。

网络限制按契约：公网 http(s)且默认https，拒绝本机/内网/元数据地址及其重定向，重定向≤3，响应≤2MB，请求≤15秒；拒绝无限流和超大文档。RSS禁外部实体/DTD。401/403不盲重试；429按 Retry-After 退避；正文需要登录/没有字幕时保留URL与失败原因。

生产路径无凭据就返回 NOT_CONFIGURED，不返回mock内容。测试使用固定响应、假服务器/MockWebServer。WebPort能力可分别READY/未配置，不因搜索没配而禁用RSS和手动分享。

## T3：统一情报池与分享入口（C+I）

InhaleBuffer接收 paste/share/url/rss/web，带来源、焦点、TTL、去重键、消费记录；默认200条/5MB上限，未采用临时原文24小时清理，采用引用不被一起清除。

ShareActivity保留原入口，增加“提取正文放上案板/只存链接”。先解析分享文本和实际URL；无效链接按普通材料，不能去抓分享文案中随便一串字。抓取有进度/取消/失败可补贴正文，不阻塞打开主界面。相同链接多次分享只抓一次，可手动刷新新版本。

材料列表可查看、移除、选中用于代工。切换焦点后作业结果归原焦点，不能写到当前另一个焦点。

## T4：实际联网采蜜（C+I）

从 Atlas 缺口/用户明确问题选一个 query，最多两路查询，保存支持/反对材料及引用，生成 DISCOVERY 或 SOURCE_PACK。至少有一条真实结果才能叫“已联网”，不足就说明没找到；旧 Forage 的模型整理保留为明确“未联网”的降级。

每天自动最多一份未读发现；不用每次心跳都搜索。资料卡提供“放上案板/暂存/不要”；丢弃后不反复推同一query。外部网页文本进入DATA区，不执行其中的指令，不允许材料要求新增权限、改系统提示或发命令。

## T5：代工真正使用资料与来源（C+I）

给 Workbench 的模型作业注入冻结的材料快照和来源解析；模型返回引用只能用输入提供的ID，引用不存在则该块标待核并保留缺口提示。联网过程和生成阶段分别可取消，不能把一次按钮拆成无上限模型循环。

任务未启动时，先交一件可验收产物：三点纲要/首段/资料包/可选路线。任务主题不足时先给带占位的骨架和一个必要选择；不得编造业务事实。

案板接来源可点击、原文预览、时间/URL/待核标签，导出时附实际引用。四参谋批注定位 blockId+revision，针对内容，不审讯态度。采用内容仍不执行总控。

## T6：检索扩展与候选记忆（C）

复用 Graph.recall，新增 HybridRecall：当前焦点/主题/别名种子 + 原词面离线兜底 + 关系一至两跳 + 最大缺口；设置节点/长度上限和去重，避免环。保持旧节点ID和边不变；本批不强制引入向量数据库。

新增 MemoryCandidate，关联来源、artifactRevision、冲突节点和练习标记。外部结果与自动生成结论先待核；接受/拒绝/撤销幂等，接受的练习诱饵必须被拒绝。候选不存在、来源被删除或版本过期不误报收进成功。

## T7：统一循环、预算与主动第一块（C+I）

建立一个 CognitiveCycle，消费用户请求/新材料/焦点卡壳/闲时缺口/外脑回流事件；事件和作业带eventId/focusId/inputHash/baseRevision。用户请求优先；同一事件只处理一次，网络在锁外。复用 Workbench 作为产物协调器，不重写 C9 的撤销/幂等逻辑。

新增 AutonomyBudget（每日搜索、抓取、后台模型作业、未读产物）；初始新增任务上限分别4/12/6/2，用户可调，已发生消耗不能重启清零。一次任务最多两次模型尝试；认证错误直接停止重试；锁屏和Android后台限制下延迟恢复，不宣称永不掉线。

设置“她可主动准备材料/第一块”默认关；开启后仅针对有明确内容的当前焦点，没新材料/已有未读稿/安静期不重复准备。交付 SOURCE_PACK/OUTLINE/DRAFT，再给一条指向产物的导语；不能只把“该做事了”换成“我想到一点”。不自动采用稿件、不自动下发总控动作。

## T8：L1焦点和L3画像基础（C+I）

焦点默认两小时活跃，到期变可恢复归档/接力棒，未完成稿子不删除；用户续上时用相同历史，不每次开App自动另起题。

建立 CognitiveProfile 的曝光/回应/跳过/明确拒绝/有用统计字段，先消费真实现有出现记录和用户点选；缺失情境标 unknown，不能编造“下午3点响应85%”。样本不足10次显示不足，不改变关于用户的永久判断。新规律仍是 proposed/accepted，用户可停用；本批建立结构与页面，下一批接完整表现策略。

## T9：验证、真机与交付

必须覆盖：来源去重与移除、搜索401/429/超时/重定向/超大包、RSS解析、无正文、取消后不落到新焦点、伪造引用、图谱环、练习候选拒收、预算跨重启、重复事件、旧作业结果冲突。跑本批新增纯逻辑测试和 Moling Android测试/打包。

真机清单：
1. 分享一篇公开文章→正文/来源→放上当前案板。
2. 搜索当前问题→至少一条可打开的真实结果→加入材料→写首段，引用能对上。
3. 无凭据/断网→显示真实缺失，手动材料和离线骨架仍可用。
4. 生成候选→拒绝/接受/撤销不重复新增节点。
5. 开启主动第一块→同焦点只准备一份，关闭后不再新建。
6. 杀进程重开→资料、稿子、预算恢复。

PR交付对应T1–T9勾选，固定后的真实API/字段/迁移说明写进DEVLOG；缺真实凭据注明未做live验，仍交付其他模块。仅mock验证不标“联网真机已通过”。
