// Loads all local data and computes the analysis for every entity in one pass.

import * as db from '../store/db.js';
import { analyze } from '../core/scoring.js';
import { getSettings } from '../services/settings.js';

export async function loadWorld() {
  const [entities, interactions, cases] = await Promise.all([
    db.all('entities'),
    db.all('interactions'),
    db.all('cases'),
  ]);
  const { prefs } = getSettings();
  const byEntity = new Map(entities.map((e) => [e.id, []]));
  for (const it of interactions) byEntity.get(it.entityId)?.push(it);
  const now = Date.now();
  const analysis = new Map(entities.map((e) => [e.id, analyze(e, byEntity.get(e.id), now, prefs)]));
  entities.sort((a, b) => (analysis.get(b.id).alerts.length - analysis.get(a.id).alerts.length) || a.name.localeCompare(b.name, 'zh'));
  return {
    entities,
    interactions,
    cases,
    byEntity,
    analysis,
    prefs,
    entityById: new Map(entities.map((e) => [e.id, e])),
  };
}
