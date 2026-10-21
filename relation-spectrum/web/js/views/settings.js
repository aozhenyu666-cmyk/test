// Settings: AI provider, Notion connection + sync, personal parameters, data import/export.

import { h, mount, field, segmented, toast } from '../ui/dom.js';
import { getSettings, updateSettings } from '../services/settings.js';
import * as api from '../services/api.js';
import * as db from '../store/db.js';
import { initDatabases, syncAll, lastSyncAt, normalizeNotionId } from '../services/notion-sync.js';
import { loadDemoData } from '../data/demo.js';

const MODEL_HINT = {
  anthropic: '默认 claude-opus-5-5；想省钱可填 claude-sonnet-5-5 或 claude-haiku-5-5',
  openai: '如 gpt-4o-mini、deepseek-chat。浏览器直连时请选允许跨域的服务商，如 OpenRouter、DeepSeek',
};

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
  const provider = segmented({ anthropic: 'Claude', openai: 'OpenAI 兼容' }, s.llm.provider || serverInfo?.llm?.provider || 'anthropic', () => syncProvider());
  const model = h('input', { value: s.llm.model, placeholder: '留空用服务器默认值' });
  const apiKey = h('input', { type: 'password', value: s.llm.apiKey, placeholder: '留空则用服务器 .env 里的 Key', autocomplete: 'off' });
  const baseUrl = h('input', { value: s.llm.baseUrl, placeholder: 'https://api.deepseek.com/v1' });
  const effort = h('select', ['', 'low', 'medium', 'high'].map((v) => h('option', { value: v, selected: v === s.llm.effort }, v || '默认（medium）')));
  const modelHint = h('span.field-hint');
  const baseUrlField = field('接口地址（Base URL）', baseUrl);
  const effortField = field('思考深度', effort, '越高越仔细，也越慢越贵');
  const syncProvider = () => {
    const p = provider.value;
    modeHint.textContent = {
      auto: '电脑上的服务器开着就走服务器，连不上时自动改为浏览器直连（需要在下面填 Key）。出门用手机时选这个。',
      server: 'Key 可以只写在电脑的 .env 里，手机上不存 Key。服务器没开时 AI 功能不可用。',
      direct: '不经过电脑，手机浏览器直接调用大模型。Key 保存在这台设备上，只在自己的手机上这样用。',
    }[mode.value];
    modelHint.textContent = MODEL_HINT[p];
    baseUrlField.hidden = p !== 'openai';
    effortField.hidden = p !== 'anthropic';
  };
  syncProvider();

  const saveAi = () => {
    updateSettings({ llm: { mode: mode.value, provider: provider.value, model: model.value.trim(), apiKey: apiKey.value.trim(), baseUrl: baseUrl.value.trim(), effort: effort.value } });
    toast('已保存', 'good');
  };

  const testAi = async (ev) => {
    saveAi();
    ev.target.disabled = true;
    ev.target.textContent = '测试中…';
    try {
      const { model: m, via } = await api.analyze('parse', { text: '我：周末一起吃饭吗\nTA：最近忙，改天吧' });
      toast(`连接成功：${m}（${via === 'direct' ? '浏览器直连' : '经过服务器'}）`, 'good');
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
    if (!confirm('将在这个页面下新建三张数据库。继续？')) return;
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
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const a = h('a', { href: URL.createObjectURL(blob), download: `relation-spectrum-${new Date().toISOString().slice(0, 10)}.json` });
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
    : h('p.small.muted', `服务器默认：${serverInfo.llm.provider} / ${serverInfo.llm.model}${serverInfo.llm.hasKey ? '（已配置 Key）' : '（未配置 Key）'} · Notion ${serverInfo.notion.hasToken ? '已配置' : '未配置'}`);

  mount(root,
    h('header.page-head', h('h1', '设置')),

    h('section.card.form',
      h('h3', 'AI 模型'),
      serverLine,
      h('div.field', h('span.field-label', '连接方式'), mode.el, modeHint),
      h('div.field', h('span.field-label', '服务商'), provider.el),
      h('label.field', h('span.field-label', '模型'), model, modelHint),
      field('API Key', apiKey, 'Key 只保存在这台设备上，通过你自己的本地服务器转发'),
      baseUrlField,
      effortField,
      h('div.row-wrap', h('button.btn.primary', { type: 'button', onclick: saveAi }, '保存'), h('button.btn', { type: 'button', onclick: testAi }, '测试连接')),
    ),

    h('section.card.form',
      h('h3', 'Notion 同步（可选）'),
      h('p.small.muted', '在 notion.so/my-integrations 创建一个 Integration，把目标页面通过“连接”分享给它。'),
      field('Integration Token', nToken),
      field('父页面', nParent, '三张数据库会建在这个页面下面'),
      h('button.btn.small', { type: 'button', onclick: initNotion }, '一键创建三张表'),
      field('Entities_DB ID', nEnt),
      field('Interactions_DB ID', nInt),
      field('Sandbox_Cases_DB ID', nCase),
      h('div.row-wrap', h('button.btn', { type: 'button', onclick: () => { saveNotion(); toast('已保存', 'good'); } }, '保存'), h('button.btn.primary', { type: 'button', onclick: runSync }, '立即同步')),
      syncStatus,
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
            if (!confirm('清空这台设备上的全部数据？Notion 上的不受影响。')) return;
            await db.clearAll();
            toast('已清空');
          },
        }, '清空本地数据'),
      ),
    ),
  );
}
