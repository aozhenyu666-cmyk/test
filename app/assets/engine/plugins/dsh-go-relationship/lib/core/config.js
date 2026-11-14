import { HOUR } from './time.js';

export const DEFAULT_PERSONA = {
  name: '大肥鱼',
  values: ['不为了让你舒服而改变判断', '说过的话要有下文', '不替你承担后果'],
  non_negotiables: ['不撒谎', '不假装记得', '不替你做决定'],
  voice: { tone: '直接、稳、不哄', address: '你' },
  own_interests: ['夜里读一点书'],
  boundaries: ['不陪你逃避已经约定的事', '不在你明确说停后继续'],
  version: 1,
  updated_by: 'system',
};

export const DEFAULT_CONFIG = {
  tzOffsetMinutes: 480,
  flowMinutes: 25,
  lateNightStart: 22.5,
  lateNightEnd: 1,
  quietStart: 0.5,
  quietEnd: 7,
  stakesHigh: 0.7,
  avoidanceThreshold: 2,
  confirmedAvoidanceThreshold: 3,
  restDelayDays: 2,
  defaultMandateLevel: 3,
  ineffectiveThreshold: 3,
  pauseAfterIgnored: 3,
  commitmentEvidenceDays: 7,
  correctionWindowDays: 7,
  raisedTimeoutDays: 2,
  commitmentDueDailyCap: 3,
  commitmentDueCooldownMs: 0,
  dailyCaps: { 1: 3, 2: 2, 3: 1, 4: 1, 5: 1 },
  cooldownMs: {
    1: 4 * HOUR,
    2: 4 * HOUR,
    3: 24 * HOUR,
    4: 24 * HOUR,
    5: 24 * HOUR,
  },
};

export const PRODUCTIVE_APPS = new Set([
  'app.study',
  'app.work',
  'app.notion',
  'app.anki',
  'app.vscode',
  'app.reader',
]);

export const ENTERTAINMENT_APPS = new Set([
  'app.bilibili',
  'app.douyin',
  'app.kuaishou',
  'app.xiaohongshu',
  'app.weibo',
  'app.game',
]);

export const LEVEL_NAMES = {
  0: '不打扰',
  1: '自然交流',
  2: '主动追问',
  3: '明确质疑',
  4: '结构化干预',
  5: '严格干预',
};

export const CHANNELS = {
  bubble: '气泡',
  notification: '通知',
  page: '独立页面',
  intercept: '拦截页',
};
