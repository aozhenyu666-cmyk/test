import type {
  ComposeColor,
  ComposeDslContext,
  ComposeNode,
} from "../../../../types/compose-dsl";
import {
  collectSnapshot,
  formatAgo,
  formatClock,
  formatDateTime,
  type Snapshot,
  type WorkflowHealth,
  type WorkflowRow,
} from "../../shared/snapshot.js";
import { HEALTH_LABEL, SOURCE_LABEL } from "../../shared/format.js";
import {
  isPinned,
  listChats,
  LONG_CHAT_MESSAGES,
  NATIVE_CHAT_ROUTE,
  setMainChat,
  type ChatEntry,
} from "../../shared/nav.js";

type Tab = "board" | "chats";

const COLLAPSED_WORKFLOWS = 6;
const RECENT_CHATS = 30;

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

function parseTime(value: string): number | null {
  if (!value) return null;
  const n = /^\d+$/.test(value) ? Number(value) : Date.parse(value);
  return Number.isFinite(n) && n > 0 ? n : null;
}

export default function Screen(ctx: ComposeDslContext): ComposeNode {
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;

  const [tab, setTab] = ctx.useState<Tab>("tab", "board");
  const [snap, setSnap] = ctx.useState<Snapshot | null>("snap", null);
  const [loading, setLoading] = ctx.useState("loading", false);
  const [loadError, setLoadError] = ctx.useState("loadError", "");
  const [showAllWorkflows, setShowAllWorkflows] = ctx.useState("showAllWorkflows", false);
  const [showMore, setShowMore] = ctx.useState("showMore", false);

  const [chats, setChats] = ctx.useState<ChatEntry[] | null>("chats", null);
  const [chatsError, setChatsError] = ctx.useState("chatsError", "");
  const [query, setQuery] = ctx.useState("query", "");
  const [openingId, setOpeningId] = ctx.useState("openingId", "");

  const loadingRef = ctx.useRef("loadingRef", false);
  const chatsLoadingRef = ctx.useRef("chatsLoadingRef", false);
  const autoLoadedRef = ctx.useRef("autoLoadedRef", false);

  async function refreshBoard() {
    if (loadingRef.current) return;
    loadingRef.current = true;
    setLoading(true);
    setLoadError("");
    try {
      setSnap(await collectSnapshot());
    } catch (error) {
      setLoadError(errorText(error));
    } finally {
      loadingRef.current = false;
      setLoading(false);
    }
  }

  async function refreshChats() {
    if (chatsLoadingRef.current) return;
    chatsLoadingRef.current = true;
    setChatsError("");
    try {
      setChats(await listChats(""));
    } catch (error) {
      setChatsError(errorText(error));
    } finally {
      chatsLoadingRef.current = false;
    }
  }

  async function refreshCurrent() {
    if (tab === "chats") await refreshChats();
    else await refreshBoard();
  }

  async function openTab(next: Tab) {
    setTab(next);
    if (next === "chats" && chats == null) await refreshChats();
  }

  async function openChat(entry: ChatEntry) {
    if (openingId) return;
    setOpeningId(entry.id);
    try {
      await setMainChat(entry.id);
      await ctx.navigate(NATIVE_CHAT_ROUTE);
    } catch (error) {
      await ctx.showToast(`打开失败：${errorText(error)}`);
    } finally {
      setOpeningId("");
    }
  }

  // ---------- 基础组件 ----------

  function muted(text: string, maxLines?: number): ComposeNode {
    return UI.Text({ text, style: "bodySmall", color: colors.onSurfaceVariant, maxLines });
  }

  function card(children: ComposeNode[], spacing = 8): ComposeNode {
    return UI.Card(
      { fillMaxWidth: true, containerColor: colors.surface, elevation: 1 },
      UI.Column({ fillMaxWidth: true, padding: 16, spacing }, children)
    );
  }

  function sectionTitle(title: string, trailing?: ComposeNode): ComposeNode {
    return UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
      UI.Text({ text: title, style: "titleMedium", color: colors.onSurface, weight: 1 }),
      ...(trailing ? [trailing] : []),
    ]);
  }

  function textButton(label: string, onClick: () => void | Promise<void>): ComposeNode {
    return UI.TextButton({ onClick }, UI.Text({ text: label, style: "labelLarge", color: colors.primary }));
  }

  function tile(label: string, value: string, caption: string, valueColor?: ComposeColor): ComposeNode {
    return UI.Card(
      { weight: 1, containerColor: colors.surfaceVariant, elevation: 0 },
      UI.Column({ fillMaxWidth: true, padding: 12, spacing: 2 }, [
        UI.Text({ text: label, style: "labelMedium", color: colors.onSurfaceVariant }),
        UI.Text({ text: value, style: "titleMedium", color: valueColor ?? colors.onSurface, maxLines: 1 }),
        muted(caption, 1),
      ])
    );
  }

  function healthColor(health: WorkflowHealth): ComposeColor {
    if (health === "FAILED" || health === "STALE") return colors.error;
    if (health === "OK" || health === "RUNNING_NOW") return colors.primary;
    return colors.onSurfaceVariant;
  }

  // ---------- 顶部 ----------

  function header(): ComposeNode {
    let status = "只读看板";
    if (tab === "chats") {
      status = chats ? `共 ${chats.length} 个对话` : "正在读取对话…";
    } else if (loading) {
      status = "正在读取…";
    } else if (snap) {
      status = `更新于 ${formatClock(snap.generatedAt)}`;
    }
    return UI.Row(
      // 单边 padding 会让宿主忽略 paddingHorizontal，所以三边都显式写
      { fillMaxWidth: true, paddingStart: 20, paddingEnd: 8, paddingTop: 12, verticalAlignment: "center" },
      [
        UI.Column({ weight: 1, spacing: 2 }, [
          UI.Text({ text: "主控台", style: "headlineSmall", color: colors.onSurface }),
          muted(status),
        ]),
        UI.IconButton({ icon: Icons.Refresh, enabled: !loading, onClick: refreshCurrent }),
      ]
    );
  }

  function tabs(): ComposeNode {
    const item = (value: Tab, label: string) =>
      tab === value
        ? UI.Button({ text: label, weight: 1, onClick: () => openTab(value) })
        : UI.OutlinedButton({ weight: 1, onClick: () => openTab(value) }, UI.Text({ text: label }));
    return UI.Row({ fillMaxWidth: true, padding: { horizontal: 16 }, spacing: 8 }, [
      item("board", "看板"),
      item("chats", "会话"),
    ]);
  }

  // ---------- 看板 ----------

  function overview(s: Snapshot): ComposeNode {
    const attention = s.workflows.filter((w) => w.health === "FAILED" || w.health === "STALE").length;
    const enabled = s.workflows.filter((w) => w.enabled).length;
    const latestEvent = s.events?.rows[0];
    const topApp = s.usage?.rows[0];
    return UI.Column({ fillMaxWidth: true, spacing: 8 }, [
      UI.Row({ fillMaxWidth: true, spacing: 8 }, [
        tile(
          "当前任务",
          s.task?.task || "未读取到",
          s.task ? `${s.task.state || "?"} · ${s.task.mode || "?"}` : "task_state.txt",
          s.task ? colors.primary : colors.error
        ),
        tile(
          "工作流",
          attention > 0 ? `需关注 ${attention}` : "全部正常",
          `共 ${s.workflows.length} · 启用 ${enabled}`,
          attention > 0 ? colors.error : colors.primary
        ),
      ]),
      UI.Row({ fillMaxWidth: true, spacing: 8 }, [
        tile(
          "今日事件",
          s.events ? `${s.events.totalLines} 条` : "未读取到",
          latestEvent ? `最新 ${latestEvent.time.split("–").pop()} ${latestEvent.type}` : "—"
        ),
        tile(
          "用得最多",
          topApp ? topApp.appName : "—",
          topApp ? `${topApp.foregroundMinutes} 分钟 · 过去 24 小时` : "没有记录"
        ),
      ]),
    ]);
  }

  function sourceWarning(s: Snapshot): ComposeNode | null {
    const bad = s.sources.filter((source) => source.status !== "OK");
    if (bad.length === 0) return null;
    return card([
      UI.Text({ text: `${bad.length} 个数据源没读到`, style: "titleSmall", color: colors.error }),
      ...bad.map((source) => muted(`${source.label}：${SOURCE_LABEL[source.status]} · ${source.detail}`, 2)),
    ]);
  }

  function workflowMeta(row: WorkflowRow, now: number): string {
    const parts: string[] = [];
    if (row.lastExecutionTime != null) {
      parts.push(`${formatDateTime(row.lastExecutionTime)} · ${formatAgo(row.lastExecutionTime, now)}`);
    }
    if (row.note) parts.push(row.note);
    return parts.join(" · ") || "—";
  }

  function workflowRow(row: WorkflowRow, now: number): ComposeNode {
    const failRate = row.total > 0 ? row.failed / row.total : 0;
    const counts = row.total > 0 ? `成功 ${row.success} · 失败 ${row.failed} · 累计 ${row.total}` : "";
    return UI.Column({ fillMaxWidth: true, spacing: 2, padding: { vertical: 6 } }, [
      UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
        UI.Text({ text: row.name, style: "bodyLarge", color: colors.onSurface, weight: 1, maxLines: 1 }),
        UI.Text({ text: HEALTH_LABEL[row.health], style: "labelLarge", color: healthColor(row.health) }),
      ]),
      muted(workflowMeta(row, now), 2),
      ...(counts
        ? [
            UI.Text({
              text: failRate >= 0.2 ? `${counts} · 失败率 ${Math.round(failRate * 100)}%` : counts,
              style: "bodySmall",
              color: failRate >= 0.2 ? colors.error : colors.onSurfaceVariant,
            }),
          ]
        : []),
    ]);
  }

  function workflowSection(s: Snapshot): ComposeNode {
    const rows = s.workflows;
    const visible = showAllWorkflows ? rows : rows.slice(0, COLLAPSED_WORKFLOWS);
    const toggle =
      rows.length > COLLAPSED_WORKFLOWS
        ? textButton(showAllWorkflows ? "收起" : `全部 ${rows.length} 个`, () => setShowAllWorkflows(!showAllWorkflows))
        : undefined;
    return card(
      [
        sectionTitle("工作流", toggle),
        muted("有问题的排在前面。成功只代表执行层返回成功，不代表动作生效或你已看到。"),
        ...visible.map((row, index) =>
          index === 0 ? workflowRow(row, s.generatedAt) : UI.Column({ fillMaxWidth: true }, [
            UI.HorizontalDivider({ color: colors.surfaceVariant }),
            workflowRow(row, s.generatedAt),
          ])
        ),
      ],
      4
    );
  }

  function usageSection(s: Snapshot): ComposeNode {
    if (!s.usage) return card([sectionTitle("App 使用"), muted("未读取到")]);
    const max = Math.max(1, ...s.usage.rows.map((row) => row.foregroundMinutes));
    return card([
      sectionTitle("App 使用"),
      muted(`过去 ${s.usage.windowHours} 小时 · 系统使用记录。"最近"不代表现在还在前台。`),
      ...s.usage.rows.map((row) =>
        UI.Column({ fillMaxWidth: true, spacing: 4 }, [
          UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
            UI.Text({ text: row.appName, style: "bodyMedium", color: colors.onSurface, weight: 1, maxLines: 1 }),
            UI.Text({ text: `${row.foregroundMinutes} 分钟`, style: "labelLarge", color: colors.onSurface }),
            UI.Text({ text: `最近 ${formatClock(row.lastTimeUsed)}`, style: "bodySmall", color: colors.onSurfaceVariant }),
          ]),
          UI.LinearProgressIndicator({ fillMaxWidth: true, progress: row.foregroundMinutes / max }),
        ])
      ),
    ]);
  }

  function eventsSection(s: Snapshot): ComposeNode {
    if (!s.events) return card([sectionTitle("最近事件"), muted("未读取到")]);
    const rows = s.events.rows.slice(0, 12).map((row) =>
      UI.Column({ fillMaxWidth: true, spacing: 1 }, [
        UI.Row({ fillMaxWidth: true, spacing: 8 }, [
          UI.Text({ text: row.type, style: "labelLarge", color: colors.primary }),
          UI.Text({
            text: `${row.time}${row.count > 1 ? ` ×${row.count}` : ""}`,
            style: "labelMedium",
            color: colors.onSurfaceVariant,
            weight: 1,
          }),
          UI.Text({ text: row.src, style: "labelSmall", color: colors.onSurfaceVariant, maxLines: 1 }),
        ]),
        ...(row.summary ? [muted(row.summary, 2)] : []),
      ])
    );
    return card([
      sectionTitle("最近事件"),
      muted(`${s.events.date} · 文件共 ${s.events.totalLines} 行 · 连续相同的合并显示`),
      ...(rows.length > 0 ? rows : [muted("没有事件")]),
      ...(s.events.unparsable > 0 ? [muted(`另有 ${s.events.unparsable} 行无法解析`)] : []),
    ]);
  }

  function moreSection(s: Snapshot): ComposeNode {
    const toggle = textButton(showMore ? "收起" : "展开", () => setShowMore(!showMore));
    if (!showMore) {
      return card([sectionTitle("规则与动作审计", toggle), muted("规则表、生效规则标记、最近动作、数据源明细")]);
    }
    const children: ComposeNode[] = [sectionTitle("规则与动作审计", toggle)];
    children.push(UI.Text({ text: "行动规则表", style: "titleSmall", color: colors.onSurface }));
    if (s.execRules && s.execRules.length > 0) {
      for (const cells of s.execRules) children.push(muted(cells.join(" · ")));
    } else {
      children.push(muted(s.execRules ? "没有规则" : "未读取到"));
    }
    children.push(UI.Text({ text: "生效规则标记", style: "titleSmall", color: colors.onSurface }));
    if (s.activeRules) {
      children.push(
        muted(Object.entries(s.activeRules.tagCounts).map(([tag, count]) => `${tag} ${count}`).join(" · "))
      );
      for (const line of s.activeRules.lines) children.push(muted(line, 2));
    } else {
      children.push(muted("未读取到"));
    }
    children.push(UI.Text({ text: "最近动作（历史记录，不代表当前状态）", style: "titleSmall", color: colors.onSurface }));
    if (s.actions && s.actions.rows.length > 0) {
      for (const cells of s.actions.rows) children.push(muted(cells.join(" · "), 2));
    } else {
      children.push(muted(s.actions ? "没有记录" : "未读取到"));
    }
    children.push(UI.Text({ text: "数据源", style: "titleSmall", color: colors.onSurface }));
    for (const source of s.sources) {
      children.push(
        UI.Text({
          text: `${source.label}：${SOURCE_LABEL[source.status]}`,
          style: "bodySmall",
          color: source.status === "OK" ? colors.onSurfaceVariant : colors.error,
        })
      );
    }
    children.push(muted(`读取于 ${formatDateTime(s.generatedAt)}`));
    return card(children);
  }

  function board(): ComposeNode {
    const items: ComposeNode[] = [];
    if (loadError) {
      items.push(card([UI.Text({ text: `读取失败：${loadError}`, style: "bodyMedium", color: colors.error })]));
    }
    if (!snap) {
      items.push(
        UI.Row({ fillMaxWidth: true, padding: 32, horizontalArrangement: "center" }, [UI.CircularProgressIndicator({})])
      );
    } else {
      const warning = sourceWarning(snap);
      items.push(overview(snap));
      if (warning) items.push(warning);
      items.push(workflowSection(snap), usageSection(snap), eventsSection(snap), moreSection(snap));
    }
    return UI.LazyColumn(
      { weight: 1, fillMaxWidth: true, padding: { horizontal: 16, vertical: 12 }, spacing: 12 },
      items
    );
  }

  // ---------- 会话 ----------

  function chatRow(entry: ChatEntry): ComposeNode {
    const updated = parseTime(entry.updatedAt);
    const meta = [
      `${entry.messageCount} 条消息`,
      ...(entry.characterCardName ? [entry.characterCardName] : []),
      updated ? formatAgo(updated, Date.now()) : entry.updatedAt,
    ].join(" · ");
    const isLong = entry.messageCount >= LONG_CHAT_MESSAGES;
    const opening = openingId === entry.id;
    return UI.Surface(
      {
        fillMaxWidth: true,
        containerColor: colors.surface,
        onClick: () => openChat(entry),
      },
      UI.Column({ fillMaxWidth: true, padding: { horizontal: 16, vertical: 12 }, spacing: 2 }, [
        UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
          UI.Text({ text: entry.title, style: "bodyLarge", color: colors.onSurface, weight: 1, maxLines: 1 }),
          ...(opening
            ? [UI.Text({ text: "打开中…", style: "labelMedium", color: colors.primary })]
            : entry.isCurrent
            ? [UI.Text({ text: "当前", style: "labelMedium", color: colors.primary })]
            : []),
        ]),
        muted(meta, 1),
        ...(isLong
          ? [UI.Text({ text: "上下文很长：新话题建议另开对话", style: "bodySmall", color: colors.error })]
          : []),
      ])
    );
  }

  function chatGroup(title: string, entries: ChatEntry[]): ComposeNode {
    return UI.Card(
      { fillMaxWidth: true, containerColor: colors.surface, elevation: 1 },
      UI.Column({ fillMaxWidth: true, padding: { vertical: 8 } }, [
        UI.Text({
          text: title,
          style: "titleSmall",
          color: colors.onSurfaceVariant,
          padding: { horizontal: 16, vertical: 4 },
        }),
        ...entries.flatMap((entry, index) =>
          index === 0 ? [chatRow(entry)] : [UI.HorizontalDivider({ color: colors.surfaceVariant }), chatRow(entry)]
        ),
      ])
    );
  }

  function chatsView(): ComposeNode {
    const items: ComposeNode[] = [
      UI.TextField({
        fillMaxWidth: true,
        value: query,
        onValueChange: setQuery,
        placeholder: "按标题搜索对话",
        singleLine: true,
      }),
      muted("点一下就在主界面打开那个对话。这里只负责找和进，不会新建对话。"),
    ];
    if (chatsError) {
      items.push(card([UI.Text({ text: `读取对话失败：${chatsError}`, style: "bodyMedium", color: colors.error })]));
    }
    if (chats == null) {
      items.push(
        UI.Row({ fillMaxWidth: true, padding: 32, horizontalArrangement: "center" }, [UI.CircularProgressIndicator({})])
      );
    } else {
      const keyword = query.trim();
      if (keyword) {
        const matches = chats.filter((entry) => entry.title.includes(keyword));
        items.push(matches.length > 0 ? chatGroup(`搜索结果 ${matches.length}`, matches) : muted("没有匹配的对话"));
      } else {
        const pinned = chats.filter(isPinned);
        const recent = chats.filter((entry) => !isPinned(entry)).slice(0, RECENT_CHATS);
        if (pinned.length > 0) items.push(chatGroup("固定入口", pinned));
        items.push(chatGroup(`最近 ${recent.length} 个`, recent));
      }
    }
    return UI.LazyColumn(
      { weight: 1, fillMaxWidth: true, padding: { horizontal: 16, vertical: 12 }, spacing: 12 },
      items
    );
  }

  return UI.Column(
    {
      fillMaxSize: true,
      spacing: 8,
      onLoad: async () => {
        if (autoLoadedRef.current) return;
        autoLoadedRef.current = true;
        await refreshBoard();
      },
    },
    [header(), tabs(), tab === "board" ? board() : chatsView()]
  );
}
