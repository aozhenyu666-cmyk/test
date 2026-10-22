// Per-device settings in localStorage. API keys entered here stay on this device. They go
// to your own local server, or, in direct mode, straight from this browser to the provider.

import { DEFAULT_PREFS } from '../core/models.js';

const KEY = 'rs.settings.v1';

const DEFAULTS = {
  accessToken: '',
  llm: { mode: 'auto', model: '', apiKey: '', baseUrl: '', jsonMode: 'auto' },
  notion: { token: '', parentPageId: '', entitiesDb: '', interactionsDb: '', casesDb: '', autoSync: false },
  prefs: { ...DEFAULT_PREFS },
};

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '{}');
  } catch {
    return {};
  }
}

export function getSettings() {
  const s = read();
  return {
    accessToken: s.accessToken || '',
    llm: cleanLlm({ ...DEFAULTS.llm, ...(s.llm || {}) }),
    notion: { ...DEFAULTS.notion, ...(s.notion || {}) },
    prefs: { ...DEFAULTS.prefs, ...(s.prefs || {}) },
  };
}

export function saveSettings(next) {
  try {
    localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    /* storage blocked: settings stay in memory for this session */
  }
  return next;
}

export function updateSettings(patch) {
  const cur = getSettings();
  return saveSettings({
    ...cur,
    ...patch,
    llm: { ...cur.llm, ...(patch.llm || {}) },
    notion: { ...cur.notion, ...(patch.notion || {}) },
    prefs: { ...cur.prefs, ...(patch.prefs || {}) },
  });
}

/** Keep only the GPT fields; drop options from older versions that supported other providers. */
function cleanLlm(llm) {
  const { mode, model, apiKey, baseUrl, jsonMode } = llm;
  const oldProvider = llm.provider && llm.provider !== 'openai';
  return {
    mode,
    model: oldProvider ? '' : model,
    apiKey: oldProvider ? '' : apiKey,
    baseUrl: oldProvider ? '' : baseUrl,
    jsonMode: jsonMode || 'auto',
  };
}
