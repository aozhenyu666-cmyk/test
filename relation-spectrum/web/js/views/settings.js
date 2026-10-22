// Settings: GPT connection, Notion connection + sync, personal parameters, data import/export.

import { h, mount, field, segmented, toast, ask, copyText } from '../ui/dom.js';
import { getSettings, updateSettings } from '../services/settings.js';
import * as api from '../services/api.js';
import * as db from '../store/db.js';
import { initDatabases, syncAll, lastSyncAt, normalizeNotionId, pushSeedCases } from '../services/notion-sync.js';
import { loadDemoData } from '../data/demo.js';
import { gptInstructions, notionOpenApi, GPT_SETUP_STEPS } from '../core/gpt-kit.js';

// One-tap presets for OpenAI and OpenAI-compatible services. Model names change often:
// edit the model field if your account offers a newer one.
const PRESETS = [
  { label: 'OpenAI GPT', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', model: 'openai/gpt-4o-mini' },
];

export async function renderSettings(root) {
  const s = getSettings();
  let serverInfo = null;
  try {
    serverInfo = await api.health({ force: true });
  } catch (err) {
    serverInfo = { error: err.message };
  }

  // ---------------- AI
  const mode = segmented({ auto: '自动', server: '只走服务器', direct: '浏览器直连' }, s.llm.mode || 'auto', () => syncProvider());
  const modeHint = h('span.field-hint');
  const model = h('input', { value: s.llm.model, placeholder: '留空用默认值 gpt-4o-mini' });
  const apiKey = h('input', { type: 'password', value: s.llm.apiKey, placeholder: '留空则用服务器 .env 里的 OPENAI_API_KEY', autocomplete: 'off' });
  const baseUrl = h('input', { value: s.llm.baseUrl, placeholder: 'https://api.openai.com/v1' });
  const jsonMode = h('select', [['auto', '自动（OpenAI 官方用严格格式，其他用普通 JSON）'], ['schema', '严格 JSON Schema'], ['object', '普通 JSON']]
    .map(([v, label]) => h('option', { value: v, selected: v === (s.llm.jsonMode || 'auto') }, label)));
  const syncProvider = () => {
    modeHint.textContent = {
      auto: '电脑上的服务器开着就走服务器，连不上时自动改为浏览器直连（需要在下面填 Key）。出门用手机时选这个。',
      server: 'Key 可以只写在电脑的 .env 里，手机上不存 Key。服务器没开时 AI 功能不可用。',
      direct: '不经过电脑，手机浏览器直接调用 GPT。Key 保存在这台设备上，只在自己的手机上这样用。',
    }[mode.value];
  };
  syncProvider();

  const presets = h('div.chips-row', PRESETS.map((pr) => h('button.chip', {
    type: 'button',
    onclick: () => {
      baseUrl.value = pr.baseUrl;
      model.value = pr.model;
      syncProvider();
      apiKey.focus();
      toast(`已填好 ${pr.label}，再填上 API Key 后保存`);
    },
  }, pr.label)));

  const saveAi = () => {
    updateSettings({ llm: { mode: mode.value, model: model.value.trim(), apiKey: apiKey.value.trim(), baseUrl: baseUrl.value.trim(), jsonMode: jsonMode.value } });
    toast('已保存', 'good');
  };

  const testAi = async (ev) => {
    saveAi();
    ev.target.disabled = true;
    ev.target.textContent = '测试中…';
    try {
      const { model: m, via } = await api.analyze('parse', { text: '我：周末一起吃饭吗\nTA：最近忙，改天吧' });
      toast(`连接成功：${m}（${{ direct: '浏览器直连', server: '经过服务器' }[via] || via}）`, 'good');
    } catch (err) {
      toast(err.message, 'danger');
    } finally {
      ev.target.disabled = false;
      ev.target.textContent = '测试连接';
    }
  };

  // ---------------- Notion
  const nToken = h('input', { type: 'password', value: s.notion.token, placeholder: '留空则用服务器 .env 里的 NOTION_TOKEN', autocomplete: 'off' });
  const nParent = h('input', { value: s.notion.parentPageId, placeholder: '粘贴 Notion 页面链接或 ID' });
  const envDbs = serverInfo?.notion?.dbs || {};
  const nEnt = h('input', { value: s.notion.entitiesDb || envDbs.entities || '' });
  const nInt = h('input', { value: s.notion.interactionsDb || envDbs.interactions || '' });
  const nCase = h('input', { value: s.notion.casesDb || envDbs.cases || '' });
  const syncStatus = h('p.small.muted');
  const last = await lastSyncAt();
  syncStatus.textContent = last ? `上次同步：${new Date(last).toLocaleString('zh-CN')}` : '还没有同步过';

  const saveNotion = () => {
    updateSettings({
      notion: {
        token: nToken.value.trim(),
        parentPageId: normalizeNotionId(nParent.value),
        entitiesDb: normalizeNotionId(nEnt.value),
        interactionsDb: normalizeNotionId(nInt.value),
        casesDb: normalizeNotionId(nCase.value),
      },
    });
  };

  const initNotion = async (ev) => {
    saveNotion();
    if (!(await ask('将在这个 Notion 页面下新建三张数据库。继续？', { ok: '创建' }))) return;
    ev.target.disabled = true;
    try {
      const ids = await initDatabases(nParent.value);
      nEnt.value = ids.entitiesDb;
      nInt.value = ids.interactionsDb;
      nCase.value = ids.casesDb;
      toast('三张表已创建', 'good');
    } catch (err) {
      toast(`创建失败：${err.message}`, 'danger');
    } finally {
      ev.target.disabled = false;
    }
  };

  const runSync = async (ev) => {
    saveNotion();
    ev.target.disabled = true;
    try {
      const st = await syncAll((t) => (syncStatus.textContent = t));
      syncStatus.textContent = `同步完成：拉取 ${st.pulled} 条，推送 ${st.pushed} 条，归档 ${st.archived} 条`;
      toast('同步完成', 'good');
    } catch (err) {
      syncStatus.textContent = `同步失败：${err.message}`;
      toast(err.message, 'danger');
    } finally {
      ev.target.disabled = false;
    }
  };

  const seedBtn = h('button.btn.small', {
    type: 'button',
    onclick: async (ev) => {
      saveNotion();
      if (!(await ask('把 15 个参考案例写进 Notion 的案例库？已经写过的话会再写一遍。', { ok: '写入' }))) return;
      ev.target.disabled = true;
      try {
        const r = await pushSeedCases((t) => (syncStatus.textContent = t), { force: true });
        syncStatus.textContent = `已写入 ${r.written} 个参考案例`;
        toast('参考案例已写入 Notion', 'good');
      } catch (err) {
        syncStatus.textContent = `写入失败：${err.message}`;
        toast(err.message, 'danger');
      } finally {
        ev.target.disabled = false;
      }
    },
  }, '写入 15 个参考案例');

  // ---------------- ChatGPT side by side
  const kitIds = () => ({ entitiesDb: normalizeNotionId(nEnt.value), interactionsDb: normalizeNotionId(nInt.value), casesDb: normalizeNotionId(nCase.value) });
  const kitStatus = h('p.small');
  const refreshKit = () => {
    const ids = kitIds();
    const ready = ids.entitiesDb && ids.interactionsDb && ids.casesDb;
    kitStatus.className = `small ${ready ? 'muted' : 'warn-text'}`;
    kitStatus.textContent = ready ? '三张表的 ID 已填好，复制出来的指令里会自动带上。' : '还没有填三张表的 ID。先完成上面的 Notion 连接，否则 GPT 不知道往哪里写。';
  };
  [nEnt, nInt, nCase].forEach((el) => el.addEventListener('input', refreshKit));
  refreshKit();

  // ---------------- prefs
  const p = s.prefs;
  const moneyUnit = h('input', { type: 'number', min: 1, value: p.moneyUnit, inputmode: 'decimal' });
  const hourly = h('input', { type: 'number', min: 0, value: p.hourlyValue, inputmode: 'decimal' });
  const life = h('input', { type: 'number', min: 50, max: 110, value: p.lifeExpectancy, inputmode: 'numeric' });
  const halfLife = h('input', { type: 'number', min: 7, max: 365, value: p.halfLifeDays, inputmode: 'numeric' });
  const savePrefs = () => {
    updateSettings({
      prefs: {
        moneyUnit: Number(moneyUnit.value) || 100,
        hourlyValue: Number(hourly.value) || 50,
        lifeExpectancy: Number(life.value) || 82,
        halfLifeDays: Number(halfLife.value) || 60,
      },
    });
    toast('已保存', 'good');
  };

  // ---------------- data
  const access = h('input', { type: 'password', value: s.accessToken, placeholder: '服务器设置了 ACCESS_TOKEN 时才需要' });

  const exportData = async () => {
    const data = await db.exportAll();
    const filename = `relation-spectrum-${new Date().toISOString().slice(0, 10)}.json`;
    const text = JSON.stringify(data, null, 2);
    const blob = new Blob([text], { type: 'application/json' });
    const a = h('a', { href: URL.createObjectURL(blob), download: filename });
    document.body.append(a);
    a.click();
    a.remove();
  };
  const fileInput = h('input', {
    type: 'file', accept: 'application/json', hidden: true,
    onchange: async () => {
      const f = fileInput.files[0];
      if (!f) return;
      try {
        await db.importAll(JSON.parse(await f.text()));
        toast('导入完成', 'good');
      } catch (err) {
        toast(`导入失败：${err.message}`, 'danger');
      }
    },
  });

  const serverLine = serverInfo?.error
    ? h('p.small.warn-text', '现在连不上电脑上的服务器。AI 可以用“自动”或“浏览器直连”继续用；Notion 同步要等连上服务器。数据都保存在这台设备上。')
    : h('p.small.muted', `服务器默认：${serverInfo.llm.model}${serverInfo.llm.hasKey ? '（已配置 Key）' : '（未配置 Key）'} · Notion ${serverInfo.notion.hasToken ? '已配置' : '未配置'}`);

  mount(root,
    h('header.page-head', h('h1', '设置')),

    h('section.card.form',
      h('h3', 'AI 模型（GPT）'),
      serverLine,
      h('div.field', h('span.field-label', '快速选择'), presets),
      h('div.field', h('span.field-label', '连接方式'), mode.el, modeHint),
      field('API Key', apiKey, '只保存在这台设备上。走服务器时由你自己的服务器转发；浏览器直连时直接发给服务商'),
      field('模型', model, '填你账号可用的模型名，如 gpt-4o-mini、deepseek-chat'),
      field('接口地址（Base URL）', baseUrl, '用 OpenAI 官方留空即可；用 DeepSeek、OpenRouter 等点上面的快速选择'),
      field('返回格式', jsonMode, '某些兼容服务不支持严格格式时，改成“普通 JSON”'),
      h('div.row-wrap',
        h('button.btn.primary', { type: 'button', onclick: saveAi }, '保存'),
        h('button.btn', { type: 'button', onclick: testAi }, '测试连接')),
    ),

    h('section.card.form',
      h('h3', 'Notion 同步（可选）'),
      h('ol.steps',
        h('li', '打开 notion.so/my-integrations，新建一个 Integration，复制它的 Token。'),
        h('li', '在 Notion 里新建一个空页面，比如“关系光谱”。点右上角 ··· → 连接 → 选你的 Integration。'),
        h('li', '把 Token 和这个页面的链接填到下面，点“一键创建三张表”。'),
        h('li', '点“写入 15 个参考案例”和“立即同步”，数据库就有内容了。')),
      field('Integration Token', nToken),
      field('父页面', nParent, '三张数据库会建在这个页面下面'),
      h('button.btn.small', { type: 'button', onclick: initNotion }, '一键创建三张表'),
      field('Entities_DB ID', nEnt),
      field('Interactions_DB ID', nInt),
      field('Sandbox_Cases_DB ID', nCase),
      h('div.row-wrap',
        h('button.btn', { type: 'button', onclick: () => { saveNotion(); toast('已保存', 'good'); } }, '保存'),
        seedBtn,
        h('button.btn.primary', { type: 'button', onclick: runSync }, '立即同步')),
      syncStatus,
    ),

    h('section.card.form',
      h('h3', '和 ChatGPT 并肩使用'),
      h('p.small.muted', '在 ChatGPT 里建一个自己的 GPT，让它读写同一个 Notion 数据库。你在 ChatGPT 里聊完、记下的事，同步后 App 里也有；App 里的记录，ChatGPT 也能查到。'),
      kitStatus,
      h('ol.steps', GPT_SETUP_STEPS.map((t) => h('li', t))),
      h('div.row-wrap',
        h('button.btn.primary', { type: 'button', onclick: () => copyText(gptInstructions(kitIds())) }, '复制 GPT 指令'),
        h('button.btn', { type: 'button', onclick: () => copyText(JSON.stringify(notionOpenApi(), null, 2)) }, '复制 Action 结构')),
      h('p.small.muted', 'GPT 用的是你自己的 Notion Token，只在你的 ChatGPT 账号里保存。'),
    ),

    h('section.card.form',
      h('h3', '计算参数'),
      field('金额基准（元）', moneyUnit, '多少钱算“一份心意”。学生可以设 50，上班族 100–200'),
      field('我的时间价值（元/小时）', hourly, '契约圈计算投入产出比时使用'),
      field('预期寿命', life, '估算与父母剩余见面次数时使用'),
      field('记忆半衰期（天）', halfLife, '多久以前的记录权重减半'),
      h('button.btn.primary', { type: 'button', onclick: savePrefs }, '保存'),
    ),

    h('section.card.form',
      h('h3', '数据'),
      field('访问口令', access),
      h('button.btn.small', { type: 'button', onclick: () => { updateSettings({ accessToken: access.value.trim() }); toast('已保存', 'good'); } }, '保存口令'),
      h('div.row-wrap',
        h('button.btn', { type: 'button', onclick: exportData }, '导出 JSON'),
        h('button.btn', { type: 'button', onclick: () => fileInput.click() }, '导入 JSON'),
        fileInput,
        h('button.btn.ghost', { type: 'button', onclick: async () => { await loadDemoData(); toast('已载入示例数据', 'good'); } }, '载入示例数据'),
        h('button.btn.danger', {
          type: 'button',
          onclick: async () => {
            if (!(await ask('清空这台设备上的全部数据？Notion 上的不受影响。', { ok: '清空', danger: true }))) return;
            await db.clearAll();
            toast('已清空');
          },
        }, '清空本地数据'),
      ),
    ),
  );
}
