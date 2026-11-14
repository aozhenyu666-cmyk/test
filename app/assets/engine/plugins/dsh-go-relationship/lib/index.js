/**
 * dsh-go-relationship —— R4 的关系内核 P0。
 *
 * 定位：
 *   · 线程 / 承诺 / 经历 / 用户理解都在本地 SQLite（$DSH_HOME/relationship/relationship.db）；
 *   · 不读屏、不联网、不写别的插件的文件；
 *   · 工具面向模型：记录用户回合、记承诺、查状态、判断是否介入、夜间反思。
 *
 * 宿主没有 tools 服务时插件仍然加载，只是不注册工具（返回一个只负责关闭 DB 的 disposer）。
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { Engine } from './core/engine.js';
import { listThreads, listCommitments } from './core/db.js';
import { loadDefineTool, loadDefineToolSync } from './hostdeps.mjs';

export const name = 'dsh-go-relationship';
export const inject = ['tools'];

const HINT = [
  '【关系】你有一份跨会话的关系账本：线程（正在发生的事）、双向承诺、用户理解。',
  '用户说到承诺、进展、休息、回避或放弃时，用 `relationship_observe` 记录原话；不要只在对话里答应。',
  '重要约定（用户答应的、你答应的）用 `relationship_commit` 记入账本，并写清 dueAt。',
  '需要判断现在该不该主动、用几级介入时，先用 `mcp__phone__now` 读取前台应用/停留分钟/亮屏/充电，再把 app、appMinutes 传给 `relationship_check`；不要凭感觉直接发消息。',
  '这些工具只读写本机的 SQLite，不联网、不读屏幕。',
].join('\n');

export function dshHome() {
  return process.env.DSH_HOME || path.join(os.homedir(), '.dsh');
}

export function defaultDbPath() {
  return path.join(dshHome(), 'relationship', 'relationship.db');
}

export function createEngine(config = {}) {
  const dbPath = config.dbPath || defaultDbPath();
  if (dbPath !== ':memory:') {
    fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  }
  return new Engine({ dbPath, config: config.engine ?? {} });
}

const OK_TEXT = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true },
    text: { type: 'string', required: true },
  },
};

const OBSERVE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true },
    text: { type: 'string', required: true },
    threadId: { oneOf: [{ type: 'string' }, { type: 'null' }], required: true },
    events: { type: 'array', required: true, items: { type: 'string' } },
  },
};

const COMMIT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true },
    text: { type: 'string', required: true },
    id: { type: 'string', required: true },
    threadId: { type: 'string', required: true },
    statement: { type: 'string', required: true },
    dueAt: { type: 'string', required: true },
  },
};

const STATUS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ok: { type: 'boolean', required: true },
    text: { type: 'string', required: true },
    threads: { type: 'array', required: true, items: { type: 'string' } },
    commitments: { type: 'array', required: true, items: { type: 'string' } },
  },
};

function openThreadId(engine) {
  const thread = listThreads(engine.db).find(
    (t) => !['resolved', 'dropped', 'archived'].includes(t.state),
  );
  return thread ? thread.id : '';
}

export function buildTools(defineTool, engine) {
  return [
    defineTool({
      name: 'relationship_status',
      description:
        'Read the relationship ledger: active threads, pending commitments and the new-session brief. ' +
        'Use it before claiming you do not remember a shared plan. Local SQLite only.',
      parameters: {},
      output: {
        schema: STATUS_SCHEMA,
        render: (_args, value) => [{ type: 'text', text: value.text }],
      },
      execute: () => {
        const brief = engine.newSessionBrief(Date.now());
        const threads = listThreads(engine.db).map(
          (t) => `${t.title}（${t.state}，${t.domain}）`,
        );
        const commitments = listCommitments(engine.db)
          .filter((c) => c.status === 'pending')
          .map((c) => `${c.owner === 'agent' ? '她' : '你'}：${c.statement} @ ${c.due_at}`);
        return {
          ok: true,
          text: brief.text || '现在没有进行中的线程。',
          threads,
          commitments,
        };
      },
    }),

    defineTool({
      name: 'relationship_observe',
      description:
        'Record one user turn into the relationship ledger. Call it with the user\'s original words ' +
        'when they mention a commitment, progress, rest, avoidance or giving up. Returns the thread and events.',
      parameters: {
        text: { type: 'string', required: true, description: 'The user\'s original words for this turn.' },
        at: { type: 'string', description: 'Optional ISO timestamp. Default now.' },
      },
      output: {
        schema: OBSERVE_SCHEMA,
        render: (_args, value) => [{ type: 'text', text: value.text }],
      },
      execute: (args) => {
        const at = args.at ? Date.parse(args.at) : Date.now();
        const result = engine.onUserTurn(String(args.text ?? ''), Number.isFinite(at) ? at : Date.now());
        return {
          ok: true,
          text: `已记录（${result.events.join('、') || '无状态变化'}）`,
          threadId: result.threadId ?? null,
          events: result.events,
        };
      },
    }),

    defineTool({
      name: 'relationship_commit',
      description:
        'Put a commitment into the ledger. owner=user means the user promised it; owner=agent means you promised it. ' +
        'Both directions are tracked and can become missed. dueAt is ISO; default +1 day.',
      parameters: {
        statement: { type: 'string', required: true, description: 'What exactly was promised.' },
        threadId: { type: 'string', description: 'Thread id. Default: the most recent open thread.' },
        owner: { type: 'string', enum: ['user', 'agent'], description: 'Who promised. Default user.' },
        dueAt: { type: 'string', description: 'ISO timestamp. Default now + 1 day.' },
      },
      output: {
        schema: COMMIT_SCHEMA,
        render: (_args, value) => [{ type: 'text', text: value.text }],
      },
      execute: (args) => {
        const threadId = args.threadId || openThreadId(engine);
        if (!threadId) {
          return { ok: false, text: '没有可挂载的线程，先让用户说清正在发生的事。', id: '', threadId: '', statement: '', dueAt: '' };
        }
        const due = args.dueAt ? Date.parse(args.dueAt) : Date.now() + 86_400_000;
        const commitment = engine.memory.addCommitment(
          threadId,
          args.owner === 'agent' ? 'agent' : 'user',
          String(args.statement ?? ''),
          Number.isFinite(due) ? due : Date.now() + 86_400_000,
          Date.now(),
        );
        return {
          ok: true,
          text: `已记入承诺：${commitment.statement}`,
          id: commitment.id,
          threadId,
          statement: commitment.statement,
          dueAt: commitment.due_at,
        };
      },
    }),

    defineTool({
      name: 'relationship_correct',
      description:
        'Retract a user-model item the user says is wrong. Keeps history; the corrected item is no longer used.',
      parameters: {
        itemId: { type: 'string', required: true, description: 'The user-model item id.' },
        correction: { type: 'string', required: true, description: 'What is wrong, in the user\'s words.' },
      },
      output: {
        schema: OK_TEXT,
        render: (_args, value) => [{ type: 'text', text: value.text }],
      },
      execute: (args) => {
        engine.memory.correct(String(args.itemId), String(args.correction ?? ''), Date.now());
        return { ok: true, text: '已更正，旧理解不会再被引用。' };
      },
    }),

    defineTool({
      name: 'relationship_check',
      description:
        'Ask the intervention engine whether to speak now, and at which level (0-3 by default). ' +
        'Call mcp__phone__now first and pass its app/appMinutes; do not send a message yourself.',
      parameters: {
        app: { type: 'string', description: 'Foreground package name, e.g. app.bilibili.' },
        appMinutes: { type: 'integer', description: 'Minutes in the current app.' },
        screenOn: { type: 'boolean', description: 'Defaults true.' },
      },
      output: {
        schema: OK_TEXT,
        render: (_args, value) => [{ type: 'text', text: value.text }],
      },
      execute: (args) => {
        const action = engine.tick(
          {
            app: args.app,
            appMinutes: Number(args.appMinutes ?? 0),
            screenOn: args.screenOn !== false,
          },
          Date.now(),
        );
        if (!action) return { ok: true, text: '现在不需要介入。' };
        return {
          ok: true,
          text: `L${action.level}（${action.channel}）：${action.reason}；${action.hypothesis}`,
        };
      },
    }),

    defineTool({
      name: 'relationship_nightly',
      description:
        'Run the nightly consolidation: mark missed commitments, close followed-up agent promises, produce observations. ' +
        'Call once at the end of the day, not during quiet hours.',
      parameters: {},
      output: {
        schema: OK_TEXT,
        render: (_args, value) => [{ type: 'text', text: value.text }],
      },
      execute: () => {
        const result = engine.nightly(Date.now());
        return {
          ok: true,
          text: `夜间整理完成：${result.openThreads} 条线程，${result.staleItems} 条理解过期。`,
        };
      },
    }),

    defineTool({
      name: 'relationship_resolve',
      description: 'Mark a thread resolved. It stays silent for 7 days, but the user can reopen it.',
      parameters: {
        threadId: { type: 'string', required: true, description: 'Thread id.' },
      },
      output: {
        schema: OK_TEXT,
        render: (_args, value) => [{ type: 'text', text: value.text }],
      },
      execute: (args) => {
        const result = engine.resolveThread(String(args.threadId), Date.now());
        if (!result) return { ok: false, text: '没有找到这条线程。' };
        return { ok: true, text: `「${result.title}」已解决，7 天内不重复提醒。` };
      },
    }),
  ];
}

export function apply(ctx, config = {}, options = {}) {
  const engine = options.engine ?? createEngine(config);
  const offs = [];
  let disposed = false;

  let tools = null;
  try {
    tools = ctx ? ctx.tools : null;
  } catch {
    tools = null;
  }

  const registerAll = (defineTool) => {
    if (!tools || typeof tools.register !== 'function') return 0;
    let count = 0;
    for (const def of buildTools(defineTool, engine)) {
      const off = tools.register(def);
      count++;
      if (typeof off === 'function') {
        if (disposed) off();
        else offs.push(off);
      }
    }
    return count;
  };

  const register = (defineTool) => {
    console.error(`[relationship] registered ${registerAll(defineTool)} tools`);
  };
  const syncDefineTool = options.defineTool ?? loadDefineToolSync();
  if (typeof syncDefineTool === 'function') {
    try {
      register(syncDefineTool);
    } catch (error) {
      console.error('[relationship] 工具注册失败：' + (error && error.message));
    }
  } else {
    // 真实宿主通常能同步从 shims 拿到；拿不到时按宿主自己的模块树异步加载。
    const ready = loadDefineTool()
      .then((defineTool) => {
        if (typeof defineTool === 'function') register(defineTool);
        else console.error('[relationship] 找不到 defineTool，工具未注册');
      })
      .catch((error) => {
        console.error('[relationship] 找不到 defineTool，工具未注册：' + (error && error.message));
      });
    apply.__toolsReady = ready;
  }

  try {
    if (ctx && typeof ctx.inject === 'function') {
      ctx.inject(['systemPrompt'], (sc) => {
        const prompt = sc && sc.systemPrompt;
        if (!prompt || typeof prompt.section !== 'function') return;
        const off = prompt.section({
          name: 'relationship:hint',
          order: 89990,
          interpolate: false,
          text: () => HINT,
        });
        if (typeof off === 'function') offs.push(off);
      });
    }
  } catch {
    // host without systemPrompt: tools still work
  }

  return () => {
    disposed = true;
    for (const off of offs.reverse()) {
      try {
        off();
      } catch {
        // ignore
      }
    }
    engine.close();
  };
}
