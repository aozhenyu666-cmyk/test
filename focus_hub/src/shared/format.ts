import {
  formatAgo,
  formatClock,
  formatDateTime,
  type Snapshot,
  type SourceStatus,
  type WorkflowHealth,
  type WorkflowRow,
} from "./snapshot.js";
import { KIND_LABEL } from "./progress.js";
import { computeMood } from "./mood.js";

export function moodOf(snap: Snapshot) {
  return computeMood({
    now: snap.generatedAt,
    task: snap.task?.task ?? "",
    focus: snap.events?.focus ?? null,
    progress: snap.progress ?? [],
    checkins: snap.checkins,
    execRules: snap.execRules,
  });
}

export const HEALTH_LABEL: Record<WorkflowHealth, string> = {
  FAILED: "最近失败",
  STALE: "疑似迟到",
  RUNNING_NOW: "运行中",
  OK: "正常",
  NO_HISTORY: "暂无运行记录",
  DISABLED: "已停用",
};

export const SOURCE_LABEL: Record<SourceStatus, string> = {
  OK: "已读取",
  MISSING: "不存在",
  ERROR: "读取失败",
};

export function workflowLine(row: WorkflowRow, now: number): string {
  const parts = [HEALTH_LABEL[row.health]];
  if (row.lastExecutionTime != null) {
    parts.push(`${formatDateTime(row.lastExecutionTime)}（${formatAgo(row.lastExecutionTime, now)}）`);
  }
  if (row.total > 0) {
    parts.push(`成功 ${row.success} / 失败 ${row.failed} / 累计 ${row.total}`);
  }
  if (row.note) parts.push(row.note);
  return parts.join(" · ");
}

export function snapshotToText(snap: Snapshot): string {
  const now = snap.generatedAt;
  const out: string[] = [];
  out.push(`【主控台快照】生成于 ${formatDateTime(now)}`);

  out.push("", "■ 当前任务（task_state.txt）");
  if (snap.task) {
    out.push(
      `${snap.task.task || "未记录"}｜状态 ${snap.task.state || "未记录"}｜模式 ${snap.task.mode || "未记录"}｜同步于 ${snap.task.syncedAt || "未记录"}`
    );
  } else {
    out.push("未读取到");
  }

  const mood = moodOf(snap);
  out.push("", `■ 陪伴状态：${mood.name}（${mood.score}/100，看板按数据计算，不执行动作）`);
  out.push(`依据：${mood.reasons.join("；")}`);
  if (mood.pendingCheckin) out.push(`${mood.pendingCheckin.iso.slice(11, 16)} 打卡过一次，用户还没回应：「${mood.pendingCheckin.line}」`);

  const focus = snap.events?.focus;
  if (focus) {
    const known = focus.onTask + focus.offTask;
    out.push(
      "",
      `■ 今日专注（前台采样口径，不是时长）：在任务上 ${focus.onTask}/${known}${known > 0 ? `（${Math.round((focus.onTask / known) * 100)}%）` : ""}，未知 ${focus.unknown}`
    );
    if (focus.offApps.length > 0) out.push(`偏离时最常开：${focus.offApps.map((a) => `${a.name} ${a.count} 次`).join("、")}`);
  }

  out.push("", "■ 用户最近亲口说的进展（REAL_USER，新的在前；判断和提醒前先看这里）");
  if (snap.progress && snap.progress.length > 0) {
    for (const r of snap.progress.slice(0, 8)) {
      out.push(`- ${r.iso} ${KIND_LABEL[r.kind] ?? r.kind}：「${r.user_quote}」${r.note ? `（${r.note}）` : ""}`);
    }
  } else {
    out.push(snap.progress ? "今天和昨天没有记录" : "未读取到");
  }

  out.push("", "■ 工作流（SUCCESS 只代表执行层成功，不代表动作生效或用户已看到）");
  const attention = snap.workflows.filter((w) => w.health === "FAILED" || w.health === "STALE");
  const enabled = snap.workflows.filter((w) => w.enabled);
  out.push(`共 ${snap.workflows.length} 个，启用 ${enabled.length} 个，需要关注 ${attention.length} 个`);
  for (const row of snap.workflows.filter((w) => w.health !== "DISABLED")) {
    out.push(`- ${row.name}：${workflowLine(row, now)}`);
  }

  out.push("", "■ App 使用（系统使用记录；最近使用时间不等于当前仍在前台）");
  if (snap.usage && snap.usage.rows.length > 0) {
    for (const row of snap.usage.rows) {
      out.push(`- ${row.appName}：${row.foregroundMinutes} 分钟，最近系统记录 ${formatClock(row.lastTimeUsed)}`);
    }
  } else {
    out.push(snap.usage ? "统计窗口内没有记录" : "未读取到");
  }

  out.push("", "■ 最近事件（events.jsonl，新的在前）");
  if (snap.events) {
    out.push(`日期 ${snap.events.date}，文件共 ${snap.events.totalLines} 行`);
    for (const row of snap.events.rows.slice(0, 12)) {
      const repeat = row.count > 1 ? ` ×${row.count}` : "";
      out.push(`- ${row.time}${repeat} ${row.type} [${row.src}] ${row.summary}`);
    }
    if (snap.events.unparsable > 0) out.push(`（另有 ${snap.events.unparsable} 行无法解析）`);
  } else {
    out.push("未读取到");
  }

  out.push("", "■ 最近动作审计（ACTION_LOG.tsv；历史记录，不代表当前状态）");
  if (snap.actions && snap.actions.rows.length > 0) {
    for (const cells of snap.actions.rows.slice(0, 8)) out.push(`- ${cells.join(" · ")}`);
  } else {
    out.push(snap.actions ? "没有记录" : "未读取到");
  }

  out.push("", "■ 系统体检（文件多久没更新）");
  for (const h of snap.health) {
    const age = h.modifiedAt != null ? formatAgo(h.modifiedAt, now) : "—";
    out.push(`- ${h.label}：${h.status === "FRESH" ? "正常" : h.status === "STALE" ? "偏旧" : h.status === "MISSING" ? "缺失" : "未知"} · ${age}${h.note ? ` · ${h.note}` : ""}`);
  }

  out.push("", "■ 数据源");
  for (const source of snap.sources) {
    out.push(`- ${source.label}：${SOURCE_LABEL[source.status]}${source.status === "OK" ? "" : `（${source.detail}）`}`);
  }
  return out.join("\n");
}
