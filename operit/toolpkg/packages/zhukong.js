/* METADATA
{
  "name": "zhukong",
  "display_name": {"zh": "主控中枢", "en": "Control Hub"},
  "description": {
    "zh": "核心对话台的工具：当前任务与回答、AI 接续、专注时段与对话门（1 分钟对话换限时钥匙）、Gemini 伴读、收线、Focus Hub 看板、切换核心对话/小满。执行限制只走已绑定的后台通道。",
    "en": "Conversation gate with timed keys, Gemini Live reading companion launcher, and thread capture into cognitive_core."
  },
  "category": "Automatic",
  "enabledByDefault": false,
  "tools": [
    {"name": "status", "description": "读取今日钥匙、冷却、进行中的对话门与伴读、执行通道绑定情况和思考线状态。", "parameters": []},
    {"name": "configure", "description": "修改配置（重度名单、问题、钥匙时长与每日次数、伴读时间、说话方式、守门人会话、解锁/回锁路由）。放宽钥匙规则属于立法，只在用户亲口要求时修改。", "parameters": [
      {"name": "config_json", "type": "string", "required": true, "description": "要修改的字段组成的 JSON，例如 {\"gate\":{\"key_default_minutes\":10}}"}]},
    {"name": "gate_open", "description": "用户想打开重度 App 或要钥匙时调用。返回要问的问题；冷却中、今日钥匙用完或已有钥匙时如实返回。", "parameters": [
      {"name": "app", "type": "string", "required": true, "description": "App 包名，例如 tv.danmaku.bili"}]},
    {"name": "gate_answer", "description": "记录用户对第 index 个问题（从 0 开始）的原话。只能写用户亲口说的内容，不得代答或润色。", "parameters": [
      {"name": "gate_id", "type": "string", "required": true, "description": "gate_open 返回的 id"},
      {"name": "index", "type": "number", "required": true, "description": "问题序号，从 0 开始"},
      {"name": "text", "type": "string", "required": true, "description": "用户原话"}]},
    {"name": "gate_submit", "description": "面板一次性提交全部回答；全部有效则按默认时长自动放行。", "parameters": [
      {"name": "gate_id", "type": "string", "required": true, "description": "gate_open 返回的 id"},
      {"name": "answers_json", "type": "string", "required": true, "description": "JSON 字符串数组，按问题顺序"}]},
    {"name": "gate_decide", "description": "守门人在全部问题答完后作决定。grant 需要全部回答有效；deny 会延长冷却。时长不超过配置上限。", "parameters": [
      {"name": "gate_id", "type": "string", "required": true, "description": "gate_open 返回的 id"},
      {"name": "decision", "type": "string", "required": true, "description": "grant 或 deny"},
      {"name": "minutes", "type": "number", "required": false, "description": "放行分钟数，缺省用默认值；用户自己说的更短时按用户说的"},
      {"name": "note", "type": "string", "required": false, "description": "一句话理由"}]},
    {"name": "gate_voice", "description": "打开对话门并切到守门人会话进入语音，由守门人逐个提问。", "parameters": [
      {"name": "app", "type": "string", "required": true, "description": "App 包名"}]},
    {"name": "gate_tick", "description": "定时调用：把超时未答的门记为失误并延长冷却；收回到期钥匙并提醒回来做什么。", "parameters": []},
    {"name": "reading_start", "description": "开始一次伴读：登记材料到思考线，打开 Gemini。屏幕共享需要用户在 Gemini 里自己点。", "parameters": [
      {"name": "title", "type": "string", "required": true, "description": "材料标题"},
      {"name": "ref", "type": "string", "required": false, "description": "网址、手机文件绝对路径或 App 包名"},
      {"name": "ref_kind", "type": "string", "required": false, "description": "web / file / app / none，缺省自动判断"}]},
    {"name": "reading_open_material", "description": "打开当前伴读登记的材料。", "parameters": []},
    {"name": "reading_finish", "description": "结束伴读并收线：保存用户自己的一句收获到思考线。只能写用户原话。", "parameters": [
      {"name": "takeaway", "type": "string", "required": true, "description": "用户自己说的收获"},
      {"name": "next_step", "type": "string", "required": false, "description": "用户说的下一步"}]},
    {"name": "thread_capture", "description": "和任何 AI 聊完、下完棋、做完题后收一句线，写入思考线。只能写用户原话。", "parameters": [
      {"name": "text", "type": "string", "required": true, "description": "用户原话"},
      {"name": "source", "type": "string", "required": false, "description": "来自哪里，例如 GPT、Gemini、象棋"}]},
    {"name": "reading_invite", "description": "定时调用：伴读时间到时发出一句邀请（伴读进行中则跳过）。", "parameters": []},
    {"name": "thread_nudge", "description": "定时调用：当天有伴读没收线时问一句。", "parameters": []},
    {"name": "console_state", "description": "核心对话台读取：当前任务与问题、专注时段、钥匙、版本。", "parameters": []},
    {"name": "answer", "description": "把用户对当前问题的回答（原话）写进账本，然后请后台 AI 接着问。只能写用户亲口/亲手给出的内容。", "parameters": [
      {"name": "text", "type": "string", "required": true, "description": "用户原话"}]},
    {"name": "ask_again", "description": "AI 没接上时，请后台再试一次（后台不允许时如实返回原因）。", "parameters": []},
    {"name": "pause_task", "description": "用户说要歇一会儿时暂停当前任务，保留问题和材料。", "parameters": []},
    {"name": "resume_task", "description": "用户说回来了时接续当前任务。", "parameters": []},
    {"name": "start_task", "description": "开始一件用户自己选的真实的事。后台没有开始接口时返回 use_backend_console。", "parameters": [
      {"name": "object", "type": "string", "required": true, "description": "用户说的要做的事"},
      {"name": "material_text", "type": "string", "required": false, "description": "用户贴的材料"},
      {"name": "material_ref", "type": "string", "required": false, "description": "材料链接或位置"}]},
    {"name": "dashboard", "description": "只读：今天重度 App 用时和日报开头（原 Focus Hub 看板）。", "parameters": []},
    {"name": "switch_chat", "description": "把对话台嵌入的对话切到核心对话（core）或小满（companion）。", "parameters": [
      {"name": "target", "type": "string", "required": true, "description": "core 或 companion"}]},
    {"name": "voice", "description": "切到指定对话并请求进入语音。", "parameters": [
      {"name": "target", "type": "string", "required": true, "description": "core 或 companion"}]},
    {"name": "install_workflows", "description": "创建或更新本包的定时工作流（钥匙巡检、伴读邀请、收线）。默认创建为停用。", "parameters": [
      {"name": "enable", "type": "boolean", "required": false, "description": "true 时创建为启用"}]}
  ]
}
*/
'use strict';
const {makeService} = require('../lib/service.js');
const {phoneStore} = require('../lib/store.js');
const {phoneHost} = require('../lib/host.js');

let cached;
function service() { if (!cached) cached = makeService({store: phoneStore(), host: phoneHost()}); return cached; }

async function wrap(fn) {
  let result;
  try { result = {success: true, data: await fn()}; }
  catch (e) { result = {success: false, message: String(e.message || e), data: {code: e.code || 'ERROR'}}; }
  complete(result);
  return result;
}

function json(text, name) {
  try { return JSON.parse(text); } catch (e) { const x = new Error(name + ' 不是合法 JSON'); x.code = 'INVALID_JSON'; throw x; }
}

exports.status = () => wrap(() => service().status());
exports.configure = p => wrap(() => service().configure(json(p.config_json, 'config_json')));
exports.gate_open = p => wrap(() => service().gateOpen({app: p.app}));
exports.gate_answer = p => wrap(() => service().gateAnswer({gate_id: p.gate_id, index: Number(p.index), text: p.text}));
exports.gate_submit = p => wrap(() => service().gateSubmit({gate_id: p.gate_id, answers: json(p.answers_json, 'answers_json')}));
exports.gate_decide = p => wrap(() => service().gateDecide({gate_id: p.gate_id, decision: p.decision, minutes: p.minutes, note: p.note}));
exports.gate_voice = p => wrap(() => service().gateVoice({app: p.app}));
exports.gate_tick = () => wrap(() => service().gateTick());
exports.reading_start = p => wrap(() => service().readingStart({title: p.title, ref: p.ref, ref_kind: p.ref_kind}));
exports.reading_open_material = () => wrap(() => service().readingOpenMaterial());
exports.reading_finish = p => wrap(() => service().readingFinish({takeaway: p.takeaway, next_step: p.next_step}));
exports.thread_capture = p => wrap(() => service().threadCapture({text: p.text, source: p.source}));
exports.reading_invite = () => wrap(() => service().readingInvite());
exports.thread_nudge = () => wrap(() => service().threadNudge());
exports.console_state = () => wrap(() => service().consoleState());
exports.answer = p => wrap(() => service().answer({text: p.text}));
exports.ask_again = () => wrap(() => service().askAgain());
exports.pause_task = () => wrap(() => service().pause());
exports.resume_task = () => wrap(() => service().resume());
exports.start_task = p => wrap(() => service().startTask({object: p.object, material_text: p.material_text, material_ref: p.material_ref}));
exports.dashboard = () => wrap(() => service().dashboard());
exports.switch_chat = p => wrap(() => service().switchChat({target: p.target}));
exports.voice = p => wrap(() => service().voice({target: p.target}));
exports.install_workflows = p => wrap(() => service().installWorkflows({enable: p && p.enable}));
