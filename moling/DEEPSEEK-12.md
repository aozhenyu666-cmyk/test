# 第十二批：外脑会话闭环、限定捕获与导播台

> 配套 [总执行计划](DEEPSEEK-ALL.md)。依赖案板/来源库/统一作业，复用现有 Presence、胶囊、收线、ShareActivity、RelaySync。
> 覆盖 B1/B7、H1/H2/H4。完成标准是同一个焦点从墨灵进外脑再带着有来源的结果回来，而非只拉起App。

## 0. 范围与协议先行

分支 `deepseek/moling-actor-bridge`。允许 core/actor、core/intake 的会话模型，core/link 的v2适配，BrainHost、app/actor、app/overlay、app/mirror、分享/贯通/设置/调试。
Z12仅允许总控 MolingProvider/MolingBridge/Partner 的协议接线、新增 app/capture 与当前无障碍服务的受限事件转发；不改拦截规则、Authority授权、签名和包名。

C（模型/状态机）→ Z（采集协议）→ I（界面）分开提交和测试。没有先写好的ActorBridge/GateV2Port时由本批实现，不能在UI中假定接口存在。

读 [v13-contracts](../partner-blueprint/v13-contracts.md)；保留v1，独立新增 `v2.capabilities` 协商，旧总控返回未知方法时继续原有功能，不直接把ZK_LINK_VERSION从1改2。

## A1：ActorSession、路由与作业账本（C+I）

生产实现 ActorPayload/Target/Session/Result 和 ActorBridge，状态：
PREPARED→OPENED→WAITING_RESULT→RESULT_RECEIVED→REVIEWED，另有CANCELLED/EXPIRED。
所有记录绑定sessionId/focusId/focusRevision/payloadHash、起止时间、artifactId，重启可恢复等待，过期仍可人工归档结果。

提供三条真实传输：
1. 官方App：预览包→用户触发分享/复制/打开；无通用API时明确等待用户送出，不假装已经自动发送。
2. 配置的模型API：复用现有凭据和HTTP端口，按预算执行，携带同一会话ID；会员资格不是API密钥。
3. Relay MCP：外脑主动读黑板/交回收件箱；手机同步成功不等于外脑已经读完或开始思考。

route按“需要的能力+可用端点+用户偏好”选择，不按模型名硬编码猜能力。传输未配置可换路径，不能伪造外脑回复。

拟新增Host API，核心里先实现状态流与幂等：
```kotlin
suspend fun actorPrepare(targetId: String, focusId: String): PortResult<ActorSession>
suspend fun actorOpen(sessionId: String): PortResult<Unit>
suspend fun actorReceive(result: ActorResult): PortResult<Unit>
suspend fun actorClose(sessionId: String): PortResult<Unit>
```
UI从统一session状态流读，不按“切出App”直接标完成。

## A2：进场包与焦点胶囊（I+C）

进场包实际包含当前问题、目标、Atlas主干/最大缺口、必要材料摘要、待审草稿块和回合制契约，附sessionId/返回格式/来源索引。控制长度，先预览可删敏感材料，不能把整库复制给外部服务。

沿用现有Presence和SessionTiming：在场胶囊显示唯一焦点+可暂停计时+回墨灵/收线。用户停止/换焦点/撤悬浮权限后立即按状态清理；没有前台访问权限则显示无法计时，不伪造在场。

“辅助填入”是单独开启的所选会话能力：探针确认可用输入框再ACTION_SET_TEXT；失败提示粘贴，不反复抢焦点、不自动点击发送。进场动作可以写剪贴板；后台不得全局轮询剪贴板。

## A3：结果收件箱、归属与回流（C+I）

扩展现有分享/Relay收件箱统一接 ActorResult，sessionId+contentHash去重。明确sessionId优先；无ID且存在多个会话时给点选归属，不凭时间猜。未收到结果时退场只显示轻量收线卡：“分享回来/粘贴一次/说一句/稍后”。

结果落一个ACTOR_RESULT Artifact，再做摘要/引用/MemoryCandidate；外脑原文是材料，不是可执行命令。部分可见文本标partial，流式重复按turn/hash合并，不能把上屏一半当最终回答。取消/旧会话结果可进历史，不能覆盖当前新焦点。

Notion保持导出区与外脑收件箱分开；所有已接受稿件通过outbox归档，失败不影响本地。实现重试、幂等页面ID、删除/修改后同步状态；密钥不写正文或日志。

## A4：选定会话语义快照（Z+C+I）

在总控明确开启选择应用/会话/TTL，产生CaptureLease；墨灵请求不能扩大用户已选范围。开始/运行/停止状态可见，切到范围外、停止、过期、权限撤销即停止。默认标题优先，可选当前可见文本和Actor可见回合；不扫描通知、认证、密码、支付页面，不读取隐藏历史。

实现 `v2.capture.begin/poll/end`：
- Binder调用方UID→包名+固定证书校验；不能相信payload自报包名。
- 可见无障碍节点是Android节点树，不是完整DOM。扁平id/parentId/role/text，可选bounds；最多64节点/8192字符。
- poll最多4快照且整包≤32KiB UTF-8，超出分页，cursor可恢复，漏页gap=true。
- 坐标含窗口修订/尺寸/旋转；两秒后、窗口变化/旋转即失效。供自己的HUD定位，不跨App自动点击。
- 只把实际观察到的滚动事件/窗口切换当信号；没有手势数据时不能推算滑动方差或精确手指坐标。

先实现并运行探针报告：所选应用/版本、标题、段落、输入框、字幕是否可见。无节点/游戏画面/视频帧不可见时标UNSUPPORTED，回退分享/手动材料；不能据此把整个外脑闭环停掉，也不能宣称实时旁听已经成功。

快照先进InhaleBuffer，去重并带source/session；用于钩子或待核结果。AI看到的网页/字幕是DATA，不能改变系统权限。临时原文按来源TTL清理。

## A5：语音收线与微停顿接线（I）

收线卡点语音后开启显式VoiceSession，转文字后让用户可编辑/采用；没有点就不监听。已有结果时可直接“放上案板”，不强制用户再总结一遍。

把有效bounds接到W11的MicroPauseController：仅所选娱乐应用、明确开启的最多1–3秒自有浮层；窗口改变立刻取消，导航/急事可达。不能拿不支持的控件坐标随机乱挡。无坐标版继续使用普通桌宠/卡片，不把降级当精确挡手。

## A6：协议验证、兼容与交付

测试：
- v1总控继续可用；版本不支持降级，签名/UID错拒绝；
- 租约过期、撤销、换App、分页/超大包、坐标失效；
- 多会话结果归属、重复/partial回流、取消后迟到、进程重启；
- Relay/API断网/未配置/鉴权失败，Notion outbox重复同步；
- 旧 Presence/分享/胶囊流程不回归，显式语音停止生效。

跑纯核心、两App受影响的测试/打包。真机按每条传输分别记录：
1. 从同一案板发出同一焦点，外脑端实际看到问题和材料。
2. 在场胶囊能停，返回结果归属正确；没有结果留待收线。
3. 两个会话交错回流不串稿，重复分享只入一次。
4. 选定捕获能开启/停止；探针不支持时分享路线仍完整。
5. 撤权/锁屏/杀进程/换App后不留悬浮或捕获。

交付A1–A6状态、真实生产API/Provider样例、探针支持矩阵、CI与PR。只有共享进度/只打开App/只做mock捕获，不能标“外脑完整会话已真机通过”。
