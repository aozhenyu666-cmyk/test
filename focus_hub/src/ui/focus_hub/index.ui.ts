import type {
  ComposeColor,
  ComposeDslContext,
  ComposeNode,
} from "../../../../types/compose-dsl";
import {
  collectSnapshot,
  FIXED_CHAT_TITLE,
  formatAgo,
  formatClock,
  formatDateTime,
  type Snapshot,
  type WorkflowHealth,
} from "../../shared/snapshot.js";
import { HEALTH_LABEL, SOURCE_LABEL, workflowLine } from "../../shared/format.js";

type Tab = "board" | "chat";
type ChatPhase = "idle" | "binding" | "ready" | "missing" | "multiple" | "error";

interface ChatCandidate {
  id: string;
  messageCount: number;
  updatedAt: string;
}

function errorText(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return String(error);
}

export default function Screen(ctx: ComposeDslContext): ComposeNode {
  const { UI } = ctx;
  const colors = ctx.MaterialTheme.colorScheme;

  const [tab, setTab] = ctx.useState<Tab>("tab", "board");
  const [snap, setSnap] = ctx.useState<Snapshot | null>("snap", null);
  const [loading, setLoading] = ctx.useState("loading", false);
  const [loadError, setLoadError] = ctx.useState("loadError", "");
  const [chatPhase, setChatPhase] = ctx.useState<ChatPhase>("chatPhase", "idle");
  const [chatId, setChatId] = ctx.useState("chatId", "");
  const [chatError, setChatError] = ctx.useState("chatError", "");
  const [candidates, setCandidates] = ctx.useState<ChatCandidate[]>("candidates", []);
  const loadingRef = ctx.useRef("loadingRef", false);
  const autoLoadedRef = ctx.useRef("autoLoadedRef", false);

  async function refresh() {
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

  async function enterChat(id: string) {
    setChatPhase("binding");
    try {
      await Tools.Chat.switchTo(id);
      setChatId(id);
      setChatPhase("ready");
    } catch (error) {
      setChatError(errorText(error));
      setChatPhase("error");
    }
  }

  async function bindChat() {
    setChatPhase("binding");
    setChatError("");
    try {
      // listAll 默认只返回最近 50 个对话；按标题精确过滤后再取，避免漏掉较旧的固定对话
      const list = await Tools.Chat.listChats({ query: FIXED_CHAT_TITLE, match: "exact", limit: 200 });
      const matches = (list.chats ?? []).filter(
        (chat) => String(chat.title ?? "").trim() === FIXED_CHAT_TITLE
      );
      if (matches.length === 0) {
        setChatPhase("missing");
        return;
      }
      if (matches.length > 1) {
        setCandidates(
          matches.map((chat) => ({
            id: chat.id,
            messageCount: chat.messageCount,
            updatedAt: String(chat.updatedAt ?? ""),
          }))
        );
        setChatPhase("multiple");
        return;
      }
      await enterChat(matches[0].id);
    } catch (error) {
      setChatError(errorText(error));
      setChatPhase("error");
    }
  }

  async function createFixedChat() {
    setChatPhase("binding");
    try {
      const created = await Tools.Chat.createNew(undefined, true);
      await Tools.Chat.updateTitle(created.chatId, FIXED_CHAT_TITLE);
      await enterChat(created.chatId);
    } catch (error) {
      setChatError(errorText(error));
      setChatPhase("error");
    }
  }

  async function openTab(next: Tab) {
    setTab(next);
    if (next !== "chat") return;
    // 主界面可能已切到别的对话，每次进入都重新切回固定对话
    if (chatId) {
      await enterChat(chatId);
    } else {
      await bindChat();
    }
  }

  function healthColor(health: WorkflowHealth): ComposeColor {
    if (health === "FAILED" || health === "STALE") return colors.error;
    if (health === "OK" || health === "RUNNING_NOW") return colors.primary;
    return colors.onSurfaceVariant;
  }

  function muted(text: string): ComposeNode {
    return UI.Text({ text, style: "bodySmall", color: colors.onSurfaceVariant });
  }

  function body(text: string): ComposeNode {
    return UI.Text({ text, style: "bodyMedium", color: colors.onSurface });
  }

  function section(title: string, subtitle: string, children: ComposeNode[]): ComposeNode {
    return UI.Card(
      { fillMaxWidth: true, containerColor: colors.surface, elevation: 1 },
      UI.Column({ fillMaxWidth: true, padding: 14, spacing: 6 }, [
        UI.Text({ text: title, style: "titleMedium", color: colors.onSurface }),
        ...(subtitle ? [muted(subtitle)] : []),
        ...children,
      ])
    );
  }

  function header(): ComposeNode {
    const status = loading
      ? "正在读取…"
      : snap
      ? `只读看板 · 更新于 ${formatClock(snap.generatedAt)}`
      : "只读看板";
    return UI.Row(
      { fillMaxWidth: true, paddingHorizontal: 16, paddingTop: 12, verticalAlignment: "center" },
      [
        UI.Column({ weight: 1, spacing: 2 }, [
          UI.Text({ text: "主控台", style: "headlineSmall", color: colors.onSurface }),
          muted(status),
        ]),
        UI.IconButton({ icon: Icons.Refresh, enabled: !loading, onClick: refresh }),
      ]
    );
  }

  function tabs(): ComposeNode {
    const item = (value: Tab, label: string) =>
      tab === value
        ? UI.Button({ text: label, weight: 1, onClick: () => openTab(value) })
        : UI.OutlinedButton({ weight: 1, onClick: () => openTab(value) }, UI.Text({ text: label }));
    return UI.Row({ fillMaxWidth: true, paddingHorizontal: 16, spacing: 8 }, [
      item("board", "看板"),
      item("chat", "对话"),
    ]);
  }

  function taskSection(s: Snapshot): ComposeNode {
    if (!s.task) return section("当前任务", "task_state.txt", [muted("未读取到，见底部数据源")]);
    return section("当前任务", `同步于 ${s.task.syncedAt || "未记录"}`, [
      UI.Text({ text: s.task.task || "未记录", style: "titleLarge", color: colors.primary }),
      body(`状态 ${s.task.state || "未记录"} · 模式 ${s.task.mode || "未记录"}`),
    ]);
  }

  function workflowSection(s: Snapshot): ComposeNode {
    const attention = s.workflows.filter((w) => w.health === "FAILED" || w.health === "STALE").length;
    const rows = s.workflows.map((row) =>
      UI.Column({ fillMaxWidth: true, spacing: 2, paddingVertical: 4 }, [
        UI.Row({ fillMaxWidth: true, spacing: 8 }, [
          UI.Text({ text: row.name, style: "bodyLarge", color: colors.onSurface, weight: 1, maxLines: 1 }),
          UI.Text({ text: HEALTH_LABEL[row.health], style: "labelLarge", color: healthColor(row.health) }),
        ]),
        muted(workflowLine(row, s.generatedAt)),
      ])
    );
    return section(
      "工作流",
      `共 ${s.workflows.length} 个，需要关注 ${attention} 个。成功只代表执行层返回成功，不代表动作生效或你已看到。`,
      rows.length > 0 ? rows : [muted("没有工作流，或读取失败")]
    );
  }

  function usageSection(s: Snapshot): ComposeNode {
    if (!s.usage) return section("App 使用", "", [muted("未读取到，见底部数据源")]);
    const rows = s.usage.rows.map((row) =>
      UI.Row({ fillMaxWidth: true, spacing: 8 }, [
        UI.Text({ text: row.appName, style: "bodyMedium", color: colors.onSurface, weight: 1, maxLines: 1 }),
        UI.Text({ text: `${row.foregroundMinutes} 分钟`, style: "bodyMedium", color: colors.onSurface }),
        UI.Text({ text: `最近 ${formatClock(row.lastTimeUsed)}`, style: "bodySmall", color: colors.onSurfaceVariant }),
      ])
    );
    return section(
      "App 使用",
      `过去 ${s.usage.windowHours} 小时 · 系统使用记录。"最近"是系统最后记录的使用时间，不代表现在还在前台。`,
      rows.length > 0 ? rows : [muted("统计窗口内没有记录")]
    );
  }

  function eventsSection(s: Snapshot): ComposeNode {
    if (!s.events) return section("最近事件", "", [muted("未读取到，见底部数据源")]);
    const rows = s.events.rows.slice(0, 15).map((row) =>
      UI.Column({ fillMaxWidth: true, spacing: 1, paddingVertical: 3 }, [
        UI.Text({
          text: `${row.time}${row.count > 1 ? ` ×${row.count}` : ""}  ${row.type}  [${row.src}]`,
          style: "labelLarge",
          color: colors.onSurface,
        }),
        ...(row.summary ? [muted(row.summary)] : []),
      ])
    );
    const extra = s.events.unparsable > 0 ? [muted(`另有 ${s.events.unparsable} 行无法解析`)] : [];
    return section(
      "最近事件",
      `${s.events.date} · 文件共 ${s.events.totalLines} 行 · 新的在前。缺少的字段不会补写。`,
      [...(rows.length > 0 ? rows : [muted("没有事件")]), ...extra]
    );
  }

  function rulesSection(s: Snapshot): ComposeNode {
    const children: ComposeNode[] = [];
    children.push(UI.Text({ text: "行动规则表", style: "titleSmall", color: colors.onSurface }));
    if (s.execRules && s.execRules.length > 0) {
      for (const cells of s.execRules) children.push(body(cells.join(" · ")));
    } else {
      children.push(muted(s.execRules ? "没有规则" : "未读取到"));
    }
    children.push(UI.Text({ text: "生效规则标记", style: "titleSmall", color: colors.onSurface }));
    if (s.activeRules) {
      const counts = Object.entries(s.activeRules.tagCounts)
        .map(([tag, count]) => `${tag} ${count}`)
        .join(" · ");
      children.push(body(counts));
      for (const line of s.activeRules.lines) children.push(muted(line));
    } else {
      children.push(muted("未读取到"));
    }
    return section("规则", "规则存在只表示允许，不表示已经执行。过期规则需要以文件内说明为准。", children);
  }

  function actionsSection(s: Snapshot): ComposeNode {
    if (!s.actions) return section("动作审计", "", [muted("未读取到，见底部数据源")]);
    const rows = s.actions.rows.map((cells) => muted(cells.join(" · ")));
    return section(
      "动作审计",
      `ACTION_LOG.tsv 最后 ${s.actions.rows.length} 条 · 新的在前。历史记录，不代表当前状态。`,
      rows.length > 0 ? rows : [muted("没有记录")]
    );
  }

  function sourcesSection(s: Snapshot): ComposeNode {
    const rows = s.sources.map((source) =>
      UI.Column({ fillMaxWidth: true, spacing: 1 }, [
        UI.Text({
          text: `${source.label}：${SOURCE_LABEL[source.status]}`,
          style: "bodyMedium",
          color: source.status === "OK" ? colors.onSurface : colors.error,
        }),
        ...(source.status === "OK" ? [] : [muted(source.detail)]),
      ])
    );
    return section("数据源", `读取于 ${formatDateTime(s.generatedAt)}（${formatAgo(s.generatedAt, Date.now())}）`, rows);
  }

  function board(): ComposeNode {
    const items: ComposeNode[] = [];
    if (loadError) {
      items.push(section("读取失败", "", [UI.Text({ text: loadError, style: "bodyMedium", color: colors.error })]));
    }
    if (!snap) {
      items.push(
        UI.Row({ fillMaxWidth: true, padding: 24, horizontalArrangement: "center" }, [
          UI.CircularProgressIndicator({}),
        ])
      );
    } else {
      items.push(
        taskSection(snap),
        workflowSection(snap),
        usageSection(snap),
        eventsSection(snap),
        rulesSection(snap),
        actionsSection(snap),
        sourcesSection(snap)
      );
    }
    return UI.LazyColumn(
      { weight: 1, fillMaxWidth: true, padding: { horizontal: 12, vertical: 8 }, spacing: 10 },
      items
    );
  }

  function chat(): ComposeNode {
    if (chatPhase === "ready") {
      return UI.Column({ weight: 1, fillMaxWidth: true, spacing: 4 }, [
        UI.Text({
          text: `固定对话「${FIXED_CHAT_TITLE}」· 想让 AI 看看板时，让它调用 focus_hub_data 的 get_dashboard_snapshot`,
          style: "bodySmall",
          color: colors.onSurfaceVariant,
          paddingHorizontal: 16,
        }),
        UI.AiChat({ weight: 1, fillMaxWidth: true }),
      ]);
    }

    let content: ComposeNode[];
    if (chatPhase === "missing") {
      content = [
        body(`还没有标题为「${FIXED_CHAT_TITLE}」的对话。`),
        muted("点下面的按钮会新建一个并命名为「主控台」，以后每次都进入这一个，不会再新建。也可以把已有对话改名为「主控台」后回来点重试。"),
        UI.Button({ text: `创建「${FIXED_CHAT_TITLE}」对话`, onClick: createFixedChat }),
        UI.OutlinedButton({ onClick: bindChat }, UI.Text({ text: "重试" })),
      ];
    } else if (chatPhase === "multiple") {
      content = [
        body(`有 ${candidates.length} 个对话都叫「${FIXED_CHAT_TITLE}」。先选一个用，建议把其余的改名，下次就不会再问。`),
        ...candidates.map((candidate) =>
          UI.OutlinedButton(
            { fillMaxWidth: true, onClick: () => enterChat(candidate.id) },
            UI.Text({ text: `${candidate.messageCount} 条消息 · 更新于 ${candidate.updatedAt}` })
          )
        ),
      ];
    } else if (chatPhase === "error") {
      content = [
        UI.Text({ text: `进入固定对话失败：${chatError}`, style: "bodyMedium", color: colors.error }),
        UI.Button({ text: "重试", onClick: bindChat }),
      ];
    } else {
      content = [
        UI.Row({ fillMaxWidth: true, horizontalArrangement: "center" }, [UI.CircularProgressIndicator({})]),
        muted("正在进入固定对话…"),
      ];
    }
    return UI.Column({ weight: 1, fillMaxWidth: true, padding: 16, spacing: 10 }, content);
  }

  return UI.Column(
    {
      fillMaxSize: true,
      spacing: 8,
      onLoad: async () => {
        if (autoLoadedRef.current) return;
        autoLoadedRef.current = true;
        await refresh();
      },
    },
    [header(), tabs(), tab === "board" ? board() : chat()]
  );
}
