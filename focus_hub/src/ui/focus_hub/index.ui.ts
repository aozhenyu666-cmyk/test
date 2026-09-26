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
  type HourBucket,
  type Snapshot,
  type WorkflowHealth,
  type WorkflowRow,
} from "../../shared/snapshot.js";
import { HEALTH_LABEL, moodOf, SOURCE_LABEL } from "../../shared/format.js";
import {
  KIND_LABEL,
  readRecentProgress,
  recordProgress,
  type ProgressKind,
} from "../../shared/progress.js";
import {
  isPinned,
  listChats,
  LONG_CHAT_MESSAGES,
  NATIVE_CHAT_ROUTE,
  setMainChat,
  startVoiceWith,
  type ChatEntry,
} from "../../shared/nav.js";
import { findCompanion, setCompanionChat } from "../../shared/companion.js";
import { speak } from "../../shared/speech.js";
import type { HealthCheck } from "../../shared/health.js";

type Page = "today" | "chats" | "sys";

// 心情四档固定配色：安心 / 在意 / 担心 / 要谈谈
const MOOD_COLOR = ["#7FAE8E", "#E2B25A", "#E0876B", "#C75A6B"];
const MOOD_TINT = ["#E4F0E8", "#FBF1DC", "#FBE6DE", "#F8E0E4"];
const MOOD_INK = ["#3F7A52", "#A2741D", "#B5553A", "#A23A4C"];
const ZONE_WEIGHTS = [20, 25, 25, 30];

const ACTIONS: { kind: ProgressKind; icon: string }[] = [
  { kind: "progress", icon: "▶" },
  { kind: "stuck", icon: "◼" },
  { kind: "done", icon: "✓" },
  { kind: "pause", icon: "Ⅱ" },
];

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

  const [page, setPage] = ctx.useState<Page>("page", "today");
  const [snap, setSnap] = ctx.useState<Snapshot | null>("snap", null);
  const [loading, setLoading] = ctx.useState("loading", false);
  const [loadError, setLoadError] = ctx.useState("loadError", "");

  const [progressKind, setProgressKind] = ctx.useState<ProgressKind>("progressKind", "progress");
  const [progressText, setProgressText] = ctx.useState("progressText", "");
  const [savingProgress, setSavingProgress] = ctx.useState("savingProgress", false);

  const [voiceBusy, setVoiceBusy] = ctx.useState("voiceBusy", false);
  const [speaking, setSpeaking] = ctx.useState("speaking", false);
  // 按钮结果留在卡片上，不只靠一闪而过的提示
  const [status, setStatus] = ctx.useState<{ ok: boolean; text: string } | null>("status", null);

  const [chats, setChats] = ctx.useState<ChatEntry[] | null>("chats", null);
  const [chatsError, setChatsError] = ctx.useState("chatsError", "");
  const [query, setQuery] = ctx.useState("query", "");
  const [openingId, setOpeningId] = ctx.useState("openingId", "");

  const [showAllWorkflows, setShowAllWorkflows] = ctx.useState("showAllWorkflows", false);
  const [showMore, setShowMore] = ctx.useState("showMore", false);

  const loadingRef = ctx.useRef("loadingRef", false);
  const chatsLoadingRef = ctx.useRef("chatsLoadingRef", false);
  const autoLoadedRef = ctx.useRef("autoLoadedRef", false);

  const mood = snap ? moodOf(snap) : null;
  const companionName = snap?.companion?.name ?? "小妹";

  // ---------- 动作 ----------

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

  async function openPage(next: Page) {
    setPage(next);
    if (next === "chats" && chats == null) await refreshChats();
  }

  async function openChat(chatId: string) {
    if (openingId) return;
    setOpeningId(chatId);
    try {
      await setMainChat(chatId);
      await ctx.navigate(NATIVE_CHAT_ROUTE);
      setStatus({ ok: true, text: `已请求打开对话（${formatClock(Date.now())}）` });
    } catch (error) {
      setStatus({ ok: false, text: `打开对话失败：${errorText(error)}` });
      await ctx.showToast(`打开失败：${errorText(error)}`);
    } finally {
      setOpeningId("");
    }
  }

  async function talkToHer() {
    const chat = snap?.companion?.chat;
    if (!chat) {
      setStatus({ ok: false, text: "还没认出她是哪个对话：去「会话」页点一个对话的「设为她」" });
      return;
    }
    await openChat(chat.id);
  }

  async function voice() {
    if (voiceBusy) return;
    setVoiceBusy(true);
    try {
      await startVoiceWith(snap?.companion?.chat?.id ?? null);
      setStatus({ ok: true, text: `已请求打开语音球并切到她（${formatClock(Date.now())}）` });
    } catch (error) {
      setStatus({ ok: false, text: `语音没打开：${errorText(error)}` });
    } finally {
      setVoiceBusy(false);
    }
  }

  async function sayLine() {
    if (!mood || speaking) return;
    setSpeaking(true);
    setStatus({ ok: true, text: "她在开口…" });
    try {
      const result = await speak(mood.line, `主控台 ${formatClock(Date.now())}`);
      setStatus(
        result.status === "ACCEPTED"
          ? { ok: true, text: result.via === "voice_bar" ? "念完了（用你的 voice_bar）" : "念完了（系统 TTS）" }
          : { ok: false, text: `没念出来：${result.error}` }
      );
    } catch (error) {
      setStatus({ ok: false, text: `没念出来：${errorText(error)}` });
    } finally {
      setSpeaking(false);
    }
  }

  async function chooseCompanion(entry: ChatEntry) {
    try {
      await setCompanionChat(entry.id);
      const companion = await findCompanion(chats ?? undefined);
      if (snap) setSnap({ ...snap, companion });
      setStatus({ ok: true, text: `以后「她」就是「${entry.title}」` });
      await ctx.showToast(`已设为她：${entry.title}`);
    } catch (error) {
      await ctx.showToast(`没设上：${errorText(error)}`);
    }
  }

  async function saveProgress() {
    if (savingProgress) return;
    const text = progressText.trim();
    if (progressKind === "pause" && !text) {
      await ctx.showToast("暂停要写一句为什么～");
      return;
    }
    setSavingProgress(true);
    try {
      const pending = mood?.pendingCheckin;
      const result = await recordProgress({
        kind: progressKind,
        userQuote: text || `（点了「${KIND_LABEL[progressKind]}」）`,
        note: pending ? `回应 ${pending.iso.slice(11, 16)} 的打卡` : "",
        via: "dashboard",
      });
      if (result.status === "REJECTED") {
        await ctx.showToast(result.reason);
        return;
      }
      setProgressText("");
      await ctx.showToast(result.status === "DUPLICATE" ? "刚刚已经记过这一句啦" : `记下了：${KIND_LABEL[result.record.kind]}`);
      const records = await readRecentProgress(20);
      if (snap) setSnap({ ...snap, progress: records });
    } catch (error) {
      await ctx.showToast(`没记上：${errorText(error)}`);
    } finally {
      setSavingProgress(false);
    }
  }

  // ---------- 基础组件 ----------

  function text(value: string, style: string, color: ComposeColor, extra: Record<string, unknown> = {}): ComposeNode {
    return UI.Text({ text: value, style: style as never, color, ...extra });
  }

  function muted(value: string, maxLines?: number): ComposeNode {
    return text(value, "bodySmall", colors.onSurfaceVariant, maxLines ? { maxLines } : {});
  }

  function card(children: ComposeNode[], spacing = 10, containerColor: ComposeColor = colors.surface): ComposeNode {
    return UI.Card(
      { fillMaxWidth: true, containerColor, elevation: 0, shape: { cornerRadius: 20 } },
      UI.Column({ fillMaxWidth: true, padding: 18, spacing }, children)
    );
  }

  function label(value: string): ComposeNode {
    return text(value, "labelMedium", colors.onSurfaceVariant);
  }

  function block(color: ComposeColor, props: Record<string, unknown>): ComposeNode {
    return UI.Box({ background: color, backgroundShape: { cornerRadius: 5 }, ...props });
  }

  function pill(value: string, bg: ComposeColor, ink: ComposeColor, onClick?: () => void | Promise<void>): ComposeNode {
    const inner = text(value, "labelMedium", ink, { paddingHorizontal: 12, paddingVertical: 5 });
    return UI.Surface({ containerColor: bg, shape: { type: "pill" }, ...(onClick ? { onClick } : {}) }, inner);
  }

  function pageColumn(items: ComposeNode[]): ComposeNode {
    return UI.LazyColumn({ weight: 1, fillMaxWidth: true, padding: { horizontal: 16, vertical: 8 }, spacing: 12 }, items);
  }

  function pageHeader(title: string, trailing: ComposeNode[] = []): ComposeNode {
    return UI.Row({ fillMaxWidth: true, paddingStart: 4, paddingTop: 8, paddingBottom: 4, verticalAlignment: "center", spacing: 8 }, [
      text(title, "headlineSmall", colors.onSurface, { weight: 1 }),
      ...trailing,
      UI.IconButton({ icon: Icons.Refresh, enabled: !loading, onClick: page === "chats" ? refreshChats : refresh }),
    ]);
  }

  function spinner(): ComposeNode {
    return UI.Row({ fillMaxWidth: true, padding: 32, horizontalArrangement: "center" }, [UI.CircularProgressIndicator({})]);
  }

  // ---------- 今天 ----------

  function systemIssues(s: Snapshot): number {
    const health = s.health.filter((h) => h.status === "STALE" || h.status === "MISSING").length;
    const flows = s.workflows.filter((w) => w.health === "FAILED" || w.health === "STALE").length;
    return health + flows;
  }

  function pendingBanner(): ComposeNode | null {
    const pending = mood?.pendingCheckin;
    if (!pending) return null;
    return card(
      [
        text(`🌸 ${pending.iso.slice(11, 16)} ${companionName}来找过你`, "titleSmall", MOOD_INK[1]),
        text(`“${pending.line}”`, "bodyMedium", colors.onSurface),
        muted("点下面任意一个动作回应她，她就知道你看到了。"),
      ],
      6,
      MOOD_TINT[1]
    );
  }

  function avatar(level: number): ComposeNode {
    return UI.Card(
      {
        width: 64,
        height: 64,
        containerColor: "#FBE4DC",
        shape: { type: "circle" },
        border: { width: 3, color: MOOD_COLOR[level] },
        elevation: 0,
      },
      UI.Box({ fillMaxSize: true, contentAlignment: "center" }, [text("🌸", "headlineMedium", colors.onSurface)])
    );
  }

  function meter(score: number, level: number): ComposeNode {
    const left = Math.max(score, 0.5);
    const right = Math.max(100 - score, 0.5);
    return UI.Column({ fillMaxWidth: true, spacing: 4 }, [
      UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
        UI.Box({ weight: left, height: 14 }),
        UI.Box({ width: 14, height: 14, background: MOOD_COLOR[level], backgroundShape: { type: "circle" } }),
        UI.Box({ weight: right, height: 14 }),
      ]),
      UI.Row(
        { fillMaxWidth: true, spacing: 3 },
        ZONE_WEIGHTS.map((w, i) => block(MOOD_COLOR[i], { weight: w, height: 10, backgroundShape: { type: "pill" } }))
      ),
      UI.Row(
        { fillMaxWidth: true },
        ["安心", "在意", "担心", "要谈谈"].map((name, i) =>
          text(name, "labelSmall", i === level ? MOOD_INK[i] : colors.onSurfaceVariant, { weight: ZONE_WEIGHTS[i] })
        )
      ),
    ]);
  }

  function herCard(s: Snapshot): ComposeNode {
    const m = moodOf(s);
    const hasChat = Boolean(s.companion?.chat);
    return card([
      UI.Row({ fillMaxWidth: true, spacing: 14, verticalAlignment: "center" }, [
        avatar(m.level),
        UI.Column({ weight: 1, spacing: 6 }, [
          text(companionName, "titleLarge", colors.onSurface),
          UI.Row({ spacing: 6 }, [pill(m.name, MOOD_TINT[m.level], MOOD_INK[m.level])]),
        ]),
      ]),
      text(`“${m.line}”`, "bodyLarge", colors.onSurface, { paddingTop: 4 }),
      muted(`为什么是「${m.name}」：${m.reasons.join(" · ")}`, 3),
      meter(m.score, m.level),
      UI.Surface(
        { fillMaxWidth: true, containerColor: colors.surfaceVariant, shape: { cornerRadius: 12 } },
        text(m.next, "bodyMedium", colors.onSurface, { padding: 12 })
      ),
      UI.Row({ fillMaxWidth: true, spacing: 8 }, [
        UI.Button({ text: openingId ? "打开中…" : "找她聊", weight: 1, enabled: hasChat && !openingId, onClick: talkToHer }),
        UI.FilledTonalButton({ weight: 1, enabled: !voiceBusy, onClick: voice }, text(voiceBusy ? "打开中…" : "🎙 语音聊", "labelLarge", colors.onSurface)),
        UI.FilledTonalButton({ weight: 1, enabled: !speaking, onClick: sayLine }, text(speaking ? "念着…" : "🔈 念一句", "labelLarge", colors.onSurface)),
      ]),
      ...(status ? [text(status.text, "bodySmall", status.ok ? MOOD_INK[0] : MOOD_INK[3])] : []),
      ...(hasChat ? [muted(`她 = 「${s.companion?.chat?.title}」${s.companion?.source === "chosen" ? "（你选的）" : ""}`, 1)] : [muted("还没认出她是哪个对话，去「会话」页点「设为她」")]),
    ]);
  }

  function actionTile(kind: ProgressKind, icon: string): ComposeNode {
    const on = progressKind === kind;
    return UI.Surface(
      {
        weight: 1,
        containerColor: on ? colors.primaryContainer : colors.surfaceVariant,
        shape: { cornerRadius: 14 },
        onClick: () => setProgressKind(kind),
      },
      UI.Column({ fillMaxWidth: true, paddingVertical: 10, horizontalAlignment: "center", spacing: 2 }, [
        text(icon, "titleMedium", on ? colors.primary : colors.onSurface),
        text(KIND_LABEL[kind], "labelLarge", on ? colors.primary : colors.onSurface),
      ])
    );
  }

  function taskCard(s: Snapshot): ComposeNode {
    return card([
      label("当前任务"),
      text(s.task?.task || "还没有声明任务", "titleLarge", colors.onSurface),
      UI.Row({ fillMaxWidth: true, spacing: 8 }, ACTIONS.map((a) => actionTile(a.kind, a.icon))),
      UI.TextField({
        fillMaxWidth: true,
        value: progressText,
        onValueChange: setProgressText,
        placeholder: progressKind === "pause" ? "暂停要说为什么" : "一句话（可不写），比如：又投了一家",
      }),
      UI.Button({
        fillMaxWidth: true,
        text: savingProgress ? "记录中…" : `记下「${KIND_LABEL[progressKind]}」`,
        enabled: !savingProgress,
        onClick: saveProgress,
      }),
    ]);
  }

  function hourColor(bucket: HourBucket): ComposeColor {
    if (bucket.on === 0 && bucket.off === 0) return colors.surfaceVariant;
    if (bucket.on > bucket.off) return MOOD_COLOR[0];
    if (bucket.off > bucket.on) return MOOD_COLOR[2];
    return MOOD_COLOR[1];
  }

  function dayCard(s: Snapshot): ComposeNode {
    const focus = s.events?.focus;
    const known = focus ? focus.onTask + focus.offTask : 0;
    const today = new Date(s.generatedAt).toDateString();
    const todayProgress = (s.progress ?? []).filter((p) => new Date(p.ts * 1000).toDateString() === today);
    const stat = (value: string, key: string) =>
      UI.Surface(
        { weight: 1, containerColor: colors.surfaceVariant, shape: { cornerRadius: 12 } },
        UI.Column({ fillMaxWidth: true, padding: 10, spacing: 2 }, [
          text(value, "titleMedium", colors.onSurface, { maxLines: 1 }),
          text(key, "labelSmall", colors.onSurfaceVariant),
        ])
      );
    const children: ComposeNode[] = [label("今天的你")];
    if (focus) {
      children.push(
        UI.Row({ fillMaxWidth: true, spacing: 2 }, focus.hours.map((b) => block(hourColor(b), { weight: 1, height: 24, backgroundShape: { cornerRadius: 4 } }))),
        UI.Row({ fillMaxWidth: true }, ["0", "6", "12", "18", "24"].map((h, i) =>
          text(h, "labelSmall", colors.onSurfaceVariant, i < 4 ? { weight: 1 } : {})
        ))
      );
    } else {
      children.push(muted("今天的采样没读到，见系统页"));
    }
    children.push(
      UI.Row({ fillMaxWidth: true, spacing: 8 }, [
        stat(known > 0 ? `${Math.round(((focus?.onTask ?? 0) / known) * 100)}%` : "—", "采样在任务上"),
        stat(String(todayProgress.length), "条进展"),
        stat(focus?.offApps[0]?.name ?? "—", "偏离时最常开"),
      ])
    );
    for (const p of todayProgress.slice(0, 5)) {
      children.push(
        UI.Row({ fillMaxWidth: true, spacing: 10, verticalAlignment: "center" }, [
          text(p.iso.slice(11, 16), "labelMedium", colors.onSurfaceVariant),
          pill(KIND_LABEL[p.kind] ?? p.kind, p.kind === "stuck" ? MOOD_TINT[3] : colors.primaryContainer, p.kind === "stuck" ? MOOD_INK[3] : colors.primary),
          text(`「${p.user_quote}」`, "bodyMedium", colors.onSurface, { weight: 1, maxLines: 2 }),
        ])
      );
    }
    children.push(muted("色块是前台采样口径：绿=在任务上，橙=不在，灰=没数据或看不出"));
    return card(children);
  }

  function todayPage(): ComposeNode {
    const issues = snap ? systemIssues(snap) : 0;
    const chip = snap
      ? issues > 0
        ? pill(`系统 · ${issues} 处要看`, MOOD_TINT[3], MOOD_INK[3], () => openPage("sys"))
        : pill("系统正常", MOOD_TINT[0], MOOD_INK[0], () => openPage("sys"))
      : null;
    const items: ComposeNode[] = [pageHeader("今天", chip ? [chip] : [])];
    if (loadError) items.push(card([text(`读取失败：${loadError}`, "bodyMedium", colors.error)]));
    if (!snap) {
      items.push(spinner());
    } else {
      const banner = pendingBanner();
      if (banner) items.push(banner);
      items.push(herCard(snap), taskCard(snap), dayCard(snap));
    }
    return pageColumn(items);
  }

  // ---------- 会话 ----------

  function chatRow(entry: ChatEntry, icon: string, canChoose = false): ComposeNode {
    const updated = parseTime(entry.updatedAt);
    const meta = [
      `${entry.messageCount} 条`,
      ...(entry.characterCardName ? [entry.characterCardName] : []),
      updated ? formatAgo(updated, Date.now()) : entry.updatedAt,
    ].join(" · ");
    return UI.Surface(
      { fillMaxWidth: true, containerColor: colors.surface, onClick: () => openChat(entry.id) },
      UI.Row({ fillMaxWidth: true, paddingVertical: 10, spacing: 12, verticalAlignment: "center" }, [
        UI.Surface(
          { width: 40, height: 40, containerColor: colors.surfaceVariant, shape: { type: "circle" } },
          UI.Box({ fillMaxSize: true, contentAlignment: "center" }, [text(icon, "titleMedium", colors.onSurface)])
        ),
        UI.Column({ weight: 1, spacing: 2 }, [
          text(entry.title, "bodyLarge", colors.onSurface, { maxLines: 1 }),
          muted(meta, 1),
        ]),
        ...(openingId === entry.id
          ? [text("打开中…", "labelMedium", colors.primary)]
          : entry.messageCount >= LONG_CHAT_MESSAGES
          ? [text("太长了", "labelMedium", MOOD_INK[3])]
          : entry.isCurrent
          ? [text("当前", "labelMedium", colors.primary)]
          : []),
        ...(canChoose
          ? [UI.TextButton({ onClick: () => chooseCompanion(entry) }, text("设为她", "labelMedium", colors.primary))]
          : []),
      ])
    );
  }

  function chatGroup(title: string, rows: ComposeNode[], note?: string): ComposeNode {
    return card([label(title), ...(note ? [muted(note)] : []), ...rows], 2);
  }

  function chatsPage(): ComposeNode {
    const items: ComposeNode[] = [
      pageHeader("会话"),
      UI.TextField({ fillMaxWidth: true, value: query, onValueChange: setQuery, placeholder: "搜索对话", singleLine: true }),
    ];
    if (chatsError) items.push(card([text(`读取对话失败：${chatsError}`, "bodyMedium", colors.error)]));
    if (chats == null) {
      items.push(spinner());
      return pageColumn(items);
    }
    const herId = snap?.companion?.chat?.id ?? "";
    const keyword = query.trim();
    if (keyword) {
      const matches = chats.filter((c) => c.title.includes(keyword));
      items.push(matches.length > 0 ? chatGroup(`搜索结果 ${matches.length}`, matches.map((c) => chatRow(c, "💬", c.id !== herId))) : muted("没有匹配的对话"));
      return pageColumn(items);
    }
    const her = chats.filter((c) => c.id === herId);
    const backstage = chats.filter((c) => isPinned(c) && c.id !== herId);
    const recent = chats.filter((c) => !isPinned(c) && c.id !== herId).slice(0, RECENT_CHATS);
    items.push(
      her.length > 0
        ? chatGroup("她（主入口）", her.map((c) => chatRow(c, "🌸")))
        : chatGroup("她（主入口）", [muted("还没认出她，在下面点一个对话的「设为她」")])
    );
    if (backstage.length > 0) {
      items.push(chatGroup("后台角色", backstage.map((c) => chatRow(c, "⚙", true)), "平时不用直接找它们"));
    }
    items.push(chatGroup(`最近 ${recent.length} 个`, recent.map((c) => chatRow(c, "💬", true))));
    return pageColumn(items);
  }

  // ---------- 系统 ----------

  function healthRow(h: HealthCheck, now: number): ComposeNode {
    const statusText = { FRESH: "正常", STALE: "偏旧", MISSING: "缺失", UNKNOWN: "未知" }[h.status];
    const color = h.status === "FRESH" ? MOOD_INK[0] : h.status === "STALE" ? MOOD_INK[2] : MOOD_INK[3];
    return UI.Row({ fillMaxWidth: true, paddingVertical: 6, spacing: 10, verticalAlignment: "center" }, [
      UI.Column({ weight: 1, spacing: 1 }, [
        text(h.label, "bodyLarge", colors.onSurface),
        muted([h.cadence, h.note].filter(Boolean).join(" · "), 1),
      ]),
      UI.Column({ horizontalAlignment: "end", spacing: 1 }, [
        text(statusText, "labelLarge", color),
        muted(h.modifiedAt != null ? formatAgo(h.modifiedAt, now) : "—"),
      ]),
    ]);
  }

  function healthColor(health: WorkflowHealth): ComposeColor {
    if (health === "FAILED" || health === "STALE") return MOOD_INK[3];
    if (health === "OK" || health === "RUNNING_NOW") return MOOD_INK[0];
    return colors.onSurfaceVariant;
  }

  function workflowRow(row: WorkflowRow, now: number): ComposeNode {
    const failRate = row.total > 0 ? row.failed / row.total : 0;
    const meta = [
      row.lastExecutionTime != null ? `${formatDateTime(row.lastExecutionTime)}（${formatAgo(row.lastExecutionTime, now)}）` : "",
      row.note,
    ].filter(Boolean).join(" · ");
    return UI.Column({ fillMaxWidth: true, spacing: 2, paddingVertical: 6 }, [
      UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
        text(row.name, "bodyLarge", colors.onSurface, { weight: 1, maxLines: 1 }),
        text(HEALTH_LABEL[row.health], "labelLarge", healthColor(row.health)),
      ]),
      ...(meta ? [muted(meta, 2)] : []),
      ...(row.total > 0
        ? [
            text(
              `成功 ${row.success} · 失败 ${row.failed} · 累计 ${row.total}${failRate >= 0.2 ? ` · 失败率 ${Math.round(failRate * 100)}%` : ""}`,
              "bodySmall",
              failRate >= 0.2 ? MOOD_INK[3] : colors.onSurfaceVariant
            ),
          ]
        : []),
    ]);
  }

  function sysPage(): ComposeNode {
    const items: ComposeNode[] = [pageHeader("系统")];
    if (!snap) {
      items.push(spinner());
      return pageColumn(items);
    }
    const s = snap;
    const now = s.generatedAt;
    items.push(card([label("体检 · 看关键文件多久没更新"), ...s.health.map((h) => healthRow(h, now)), muted("更新了不代表内容一定对；没更新基本说明那条链停了。")], 2));

    const rows = s.workflows;
    const visible = showAllWorkflows ? rows : rows.slice(0, COLLAPSED_WORKFLOWS);
    items.push(
      card(
        [
          UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
            text("工作流", "titleMedium", colors.onSurface, { weight: 1 }),
            ...(rows.length > COLLAPSED_WORKFLOWS
              ? [UI.TextButton({ onClick: () => setShowAllWorkflows(!showAllWorkflows) }, text(showAllWorkflows ? "收起" : `全部 ${rows.length} 个`, "labelLarge", colors.primary))]
              : []),
          ]),
          muted("有问题的排前面。成功只代表执行层返回成功，不代表动作生效或你已看到。"),
          ...visible.map((row) => workflowRow(row, now)),
        ],
        2
      )
    );

    if (s.usage) {
      const max = Math.max(1, ...s.usage.rows.map((r) => r.foregroundMinutes));
      items.push(
        card([
          label(`App 使用 · 过去 ${s.usage.windowHours} 小时（系统记录）`),
          ...s.usage.rows.map((r) =>
            UI.Column({ fillMaxWidth: true, spacing: 4 }, [
              UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                text(r.appName, "bodyMedium", colors.onSurface, { weight: 1, maxLines: 1 }),
                text(`${r.foregroundMinutes} 分钟`, "labelLarge", colors.onSurface),
                muted(`最近 ${formatClock(r.lastTimeUsed)}`),
              ]),
              UI.LinearProgressIndicator({ fillMaxWidth: true, progress: r.foregroundMinutes / max }),
            ])
          ),
        ])
      );
    }

    if (s.events) {
      items.push(
        card([
          label(`最近事件 · ${s.events.date} · 共 ${s.events.totalLines} 行`),
          ...s.events.rows.slice(0, 10).map((r) =>
            UI.Column({ fillMaxWidth: true, spacing: 1 }, [
              UI.Row({ fillMaxWidth: true, spacing: 8 }, [
                text(r.type, "labelLarge", colors.primary),
                text(`${r.time}${r.count > 1 ? ` ×${r.count}` : ""}`, "labelMedium", colors.onSurfaceVariant, { weight: 1 }),
                muted(r.src, 1),
              ]),
              ...(r.summary ? [muted(r.summary, 2)] : []),
            ])
          ),
        ])
      );
    }

    const more: ComposeNode[] = [
      UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
        text("规则、动作审计、数据源", "titleMedium", colors.onSurface, { weight: 1 }),
        UI.TextButton({ onClick: () => setShowMore(!showMore) }, text(showMore ? "收起" : "展开", "labelLarge", colors.primary)),
      ]),
    ];
    if (showMore) {
      more.push(label("行动规则表"));
      for (const cells of s.execRules ?? []) more.push(muted(cells.join(" · ")));
      if (s.activeRules) {
        more.push(label("生效规则标记"));
        more.push(muted(Object.entries(s.activeRules.tagCounts).map(([k, v]) => `${k} ${v}`).join(" · ")));
        for (const line of s.activeRules.lines) more.push(muted(line, 2));
      }
      more.push(label("最近动作（历史记录，不代表当前状态）"));
      for (const cells of s.actions?.rows ?? []) more.push(muted(cells.join(" · "), 2));
      more.push(label("数据源"));
      for (const src of s.sources) {
        more.push(text(`${src.label}：${SOURCE_LABEL[src.status]}${src.status === "OK" ? "" : ` · ${src.detail}`}`, "bodySmall", src.status === "OK" ? colors.onSurfaceVariant : colors.error));
      }
      more.push(muted(`心情档位由看板按采样、偏移和你的进展计算，只用来呈现，不会执行动作；真正的提醒和冻结由规则链决定。读取于 ${formatDateTime(now)}。`));
    }
    items.push(card(more, 6));
    return pageColumn(items);
  }

  // ---------- 底栏 ----------

  function navItem(target: Page, icon: string, name: string): ComposeNode {
    const on = page === target;
    return UI.Surface(
      { weight: 1, containerColor: colors.surface, onClick: () => openPage(target) },
      UI.Column({ fillMaxWidth: true, paddingTop: 8, paddingBottom: 10, horizontalAlignment: "center", spacing: 2 }, [
        text(icon, "titleMedium", on ? colors.primary : colors.onSurfaceVariant),
        text(name, "labelMedium", on ? colors.primary : colors.onSurfaceVariant),
      ])
    );
  }

  const body = page === "today" ? todayPage() : page === "chats" ? chatsPage() : sysPage();

  return UI.Column(
    {
      fillMaxSize: true,
      onLoad: async () => {
        if (autoLoadedRef.current) return;
        autoLoadedRef.current = true;
        await refresh();
      },
    },
    [
      body,
      UI.HorizontalDivider({ color: colors.surfaceVariant }),
      UI.Row({ fillMaxWidth: true }, [navItem("today", "☀", "今天"), navItem("chats", "💬", "会话"), navItem("sys", "⚙", "系统")]),
    ]
  );
}
