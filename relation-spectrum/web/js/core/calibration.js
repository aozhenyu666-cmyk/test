// Intuition calibration: compares my first guess and the AI's prediction with what really happened.
// Fed by the "回填真实结局" form on each saved sandbox case.
//
// case.myGuess: string            what I believed before asking the AI
// case.outcome: {
//   at, usedLevel: 'gentle'|'equal'|'exit'|'own'|'none',
//   sentDraft: boolean,            did I send my impulsive draft anyway
//   reaction: string,              what the other side actually did
//   truth: { kind: 'my_guess'|'ai'|'other', label: string },
//   result: 'better'|'same'|'worse'
// }

const round = (x) => Math.round(x * 100) / 100;

export function topSubtext(result) {
  const subs = [...(result?.subtexts || [])].sort((a, b) => b.probability - a.probability);
  return subs[0] || null;
}

export function needsOutcome(c, now = Date.now(), minDays = 2) {
  return ['decode', 'game'].includes(c.mode) && c.result && !c.outcome && now - c.createdAt >= minDays * 86400000;
}

/** One line per resolved case where my guess turned out wrong, for the prompts' <我过往的误判>. */
export function mismatchLines(cases, limit = 5) {
  return resolvedCases(cases)
    .filter((c) => c.myGuess && c.outcome.truth.kind !== 'my_guess')
    .sort((a, b) => b.outcome.at - a.outcome.at)
    .slice(0, limit)
    .map((c) => `曾以为「${c.myGuess.trim()}」，实际是「${truthText(c)}」${c.outcome.sentDraft ? '（当时还是发了冲动原话）' : ''}`);
}

export function truthText(c) {
  const t = c.outcome?.truth;
  if (!t) return '';
  if (t.kind === 'my_guess') return c.myGuess || '我的猜测';
  return t.label || '其他原因';
}

function resolvedCases(cases) {
  return cases.filter((c) => !c.deleted && c.outcome && c.outcome.truth && ['decode', 'game'].includes(c.mode));
}

/** Aggregate stats for the calibration page and dashboard card. */
export function calibrationStats(cases) {
  const resolved = resolvedCases(cases);
  const withGuess = resolved.filter((c) => c.myGuess && c.myGuess.trim());
  const myHits = withGuess.filter((c) => c.outcome.truth.kind === 'my_guess').length;

  let aiTopHits = 0;
  let aiAnyHits = 0;
  let probSum = 0;
  for (const c of resolved) {
    const t = c.outcome.truth;
    const subs = c.result?.subtexts || [];
    const top = topSubtext(c.result);
    // My guess may also be one of the AI's listed explanations.
    const label = t.kind === 'my_guess' ? null : t.label;
    const match = label ? subs.find((s) => s.label === label) : null;
    if (match) {
      aiAnyHits++;
      probSum += match.probability;
      if (top && top.label === label) aiTopHits++;
    }
  }

  const byLevel = {};
  for (const c of resolved) {
    const lv = c.outcome.usedLevel || 'none';
    byLevel[lv] ||= { n: 0, better: 0, same: 0, worse: 0 };
    byLevel[lv].n++;
    byLevel[lv][c.outcome.result || 'same']++;
  }

  const drafts = resolved.filter((c) => c.outcome.sentDraft);
  const draftWorse = drafts.filter((c) => c.outcome.result === 'worse').length;

  const n = resolved.length;
  return {
    resolved: n,
    withGuess: withGuess.length,
    myHitRate: withGuess.length ? round(myHits / withGuess.length) : null,
    aiTopHitRate: n ? round(aiTopHits / n) : null,
    aiAnyHitRate: n ? round(aiAnyHits / n) : null,
    aiAvgProbOnTruth: aiAnyHits ? Math.round(probSum / aiAnyHits) : null,
    byLevel,
    sentDraft: drafts.length,
    sentDraftWorse: draftWorse,
    mistakes: withGuess
      .filter((c) => c.outcome.truth.kind !== 'my_guess')
      .sort((a, b) => b.outcome.at - a.outcome.at)
      .map((c) => ({ id: c.id, title: c.title, guess: c.myGuess, truth: truthText(c), result: c.outcome.result })),
    lesson: lessonFor({ n, withGuess: withGuess.length, myHits, aiTopHits, draftWorse, drafts: drafts.length }),
  };
}

function lessonFor({ n, withGuess, myHits, aiTopHits, draftWorse, drafts }) {
  if (n < 3) return '回填满 3 条结局后，这里会告诉你你的直觉在哪类事情上最容易出错。';
  if (drafts && draftWorse / drafts >= 0.5) return '你发出冲动原话的那几次，多数让关系变差了。下次先在沙盘里测一下破坏力。';
  if (withGuess >= 3 && myHits / withGuess < 0.4) return '你的第一反应猜中的不到四成。看到模糊信号时，先把它当成“待验证”，而不是结论。';
  if (withGuess >= 3 && myHits / withGuess >= 0.7) return '你的直觉准确率不错。保持先验证、后行动的习惯。';
  if (aiTopHits / n < 0.4) return 'AI 的首选解释也经常不对，说明这些场景信息太少。多用“怎么验证”那一栏去收集事实。';
  return '继续回填结局，样本越多，校准越准。';
}
