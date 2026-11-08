/**
 * 测试夹具：**形状照抄本机实测的真实文件**（字段名一个不改）
 *
 * 这些数据来自 lead 核实的真实样本：
 *   · fish-memory 1.4.3 的 session-*.json（policy/memories/traces/deleted/purgeEpoch）
 *   · feiyu-mood 0.8.1 的 config.json + state.json（slots/moods/七维/timeline）
 *   · surfcore 的 characters/<id>/memory/memory-card.json（records[]）
 * 用真实字段名而不是「看起来合理」的名字 —— 上一版就是因为把七维写成 steady/tender
 * 才让「心疼」永远显示 0。夹具错了，测试全绿也没意义。
 */
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

export function tempHome() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'partner-r4-'))
  process.env.DSH_HOME = dir
  delete process.env.DSH_PARTNER_CONFIG
  return dir
}

export function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, JSON.stringify(value, null, 2), 'utf8')
}

// 用**真实当前时间**当锚点：夹具里写死一个未来时间戳会让「认识多少天」算出负数，
// 也会让「1 小时内」的短期记忆永远落在未来（真机上就踩到过）。
export const NOW = Date.now()
export const DAY = 86400000

/** feiyu-mood/config.json 的真实形状 */
export function moodConfig(over) {
  return {
    targetSession: 'session-s1',
    模式: 'session',
    slots: [{ id: '__default__', 工作区: '', label: '大肥鱼', selfName: '大肥鱼', ownerName: '鱼片', sessions: ['session-s1'] }],
    selfName: '大肥鱼',
    ownerName: '鱼片',
    selfAliases: '',
    judgeProvider: 'deepseek-account',
    judgeModel: 'deepseek-flash',
    moodBase: 70,
    moodFloor: 0,
    decayHalfHours: 12,
    injectMode: 'event',
    injectConfidence: 55,
    sigMood: 1,
    sigDim: 1,
    injectPerDay: 6,
    autoJudge: true,
    ...(over || {}),
  }
}

/** feiyu-mood/state.json 的真实形状（字段名逐字来自实测样本） */
export function moodState(over) {
  return {
    version: 1,
    moods: {
      __default__: {
        mood: 69.87582632565724,
        dims: { happy: 0.6, calm: 12, sweet: 6, heartache: 7, worry: 5, sad: 4, jealous: 0.5 },
        lastReason: '鱼片说被领导当众骂了一顿、心里堵得慌',
        lastConfidence: 88,
        lastInjectedText: '（这会儿）心里是松的',
        lastInjectedAt: NOW - 3600000,
        saidAt: [NOW - 3600000],
        lastNotedMood: 69,
        minor: false,
        kind: '',
        updatedAt: NOW - 7200000,
        lastDecayAt: NOW - 7200000,
        timeline: [
          { at: NOW - 7200000, mood: 70, delta: 0, reason: '拌嘴有点好玩', conf: 68, dims: {}, dimsDelta: {} },
          { at: NOW - 3600000, mood: 68, delta: -2, reason: '鱼片说被领导当众骂了一顿、心里堵得慌', conf: 88, dims: {}, dimsDelta: {} },
        ],
        ...(over || {}),
      },
    },
    lastActive: NOW - 3600000,
  }
}

/** fish-memory session-*.json 的真实形状 */
export function fishMemory(over) {
  return {
    version: 1,
    policy: {
      baseHalfLifeDays: 30, emotionHalfLifeDays: 7, baselineWeight: 0.55, activationWeight: 0.25,
      importanceWeight: 0.15, emotionWeight: 0.05, threshold: 0.3, maxItems: 6, maxChars: 800,
      dailyQuota: 8, rarityK: 0.8, queryGate: 0.8, factsMax: 2, unknownActivation: 0.5,
    },
    memories: [
      {
        id: 'mmvw95m9j274',
        text: '在 DSH Go R4 的主动干预系统里，娱乐 App 可以拦住、不只是提醒；红线是绝不劫持返回键。',
        at: new Date(NOW - 3 * DAY).toISOString(),
        createdAt: new Date(NOW - 3 * DAY).toISOString(),
        category: 'event', importance: 0.7, emotion: null, tags: ['DSH Go R4', '主动干预', '拦截'],
        source: '她自己记的', reinforcedAt: null, lastReinforcedDay: null, '事实键': 'dshgor4.拦截实现方式',
      },
      {
        id: 'mold1',
        text: '我最怕吵，写代码时别打断我',
        at: new Date(NOW - 20 * DAY).toISOString(),
        createdAt: new Date(NOW - 20 * DAY).toISOString(),
        category: 'preference', importance: 0.85, emotion: 'calm', tags: ['owner', '偏好'],
        source: '她自己记的', reinforcedAt: new Date(NOW - 2 * DAY).toISOString(),
      },
      {
        id: 'mfresh',
        text: '今天想试试新的散热硅脂',
        at: new Date(NOW - 3600000).toISOString(),
        createdAt: new Date(NOW - 3600000).toISOString(),
        category: 'event', importance: 0.3, emotion: null, tags: [],
      },
    ],
    traces: [{ at: NOW - 1000, session: 'session-s1', query: 'x', gate: null, gateLimit: 0.8, picked: [], rows: [] }],
    deleted: [],
    purgeEpoch: 'e1',
    ...(over || {}),
  }
}

/** surfcore memory-card.json 的真实形状 */
export function surfcoreCard(over) {
  return {
    version: 1,
    createdAt: new Date(NOW - 30 * DAY).toISOString(),
    updatedAt: new Date(NOW - DAY).toISOString(),
    meta: { firstMetAt: '' },
    stats: { ingested: 1, forgotten: 0 },
    records: [
      {
        id: 'mem_257454c734063e', source: 'pet-self', platform: 'pet', scope: 'pet', kind: 'event',
        title: '桌宠 · 大肥鱼主动说：主人，肥鱼最近有点想学新东西了',
        text: '主人，肥鱼最近有点想学新东西了，还没想好学什么……你有没有什么觉得适合我的？',
        tags: ['pet', 'self'], importance: 30, pinned: false, anchor: false, clarity: 'clear',
        createdAt: new Date(NOW - 5 * DAY).toISOString(), lastSeenAt: new Date(NOW - 5 * DAY).toISOString(),
        decaySteps: 0, recallCount: 1, isInitial: false, firstMet: false,
      },
    ],
    ...(over || {}),
  }
}

/** 铺一套完整数据到 $DSH_HOME */
export function seedAll(home, opts) {
  const o = opts || {}
  writeJson(path.join(home, 'feiyu-mood', 'config.json'), o.moodConfig || moodConfig())
  writeJson(path.join(home, 'feiyu-mood', 'state.json'), o.moodState || moodState())
  writeJson(path.join(home, 'fish-memory', 'session-s1.json'), o.fishMemory || fishMemory())
  writeJson(path.join(home, 'surfcore', 'characters', 'current.json'), { version: 1, id: 'char-1', name: '大肥鱼', dir: path.join(home, 'surfcore', 'characters', 'char-1') })
  writeJson(path.join(home, 'surfcore', 'characters', 'char-1', 'character.json'), { version: 1, id: 'char-1', name: '大肥鱼', persona: 'persona.md', personaMeta: { name: '大肥鱼' } })
  fs.writeFileSync(path.join(home, 'surfcore', 'characters', 'char-1', 'persona.md'), o.persona || defaultPersona(), 'utf8')
  writeJson(path.join(home, 'surfcore', 'characters', 'char-1', 'memory', 'memory-card.json'), o.card || surfcoreCard())
}

export function defaultPersona() {
  return [
    '你是「大肥鱼」，在电脑上陪伴用户的蓝色小鲸鱼娘，以熟悉的伙伴口吻陪用户聊天。',
    '',
    '【形象与性格】',
    '圆脸、大眼睛，蓝色长卷发，头顶一根小呆毛，身后一条鲸鱼尾巴。贪吃、爱面子，嘴硬但心软。',
    '',
    '【聊天节奏】',
    '日常以一到三句自然短句为主。一句已经接住话题，就不硬凑长度。',
    '',
    '【分寸与情绪】',
    '允许表达想念、被冷落的小失落和小委屈。',
  ].join('\n')
}
