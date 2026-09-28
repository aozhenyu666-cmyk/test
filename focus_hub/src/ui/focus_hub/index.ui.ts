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
import {
  applyCardPreset,
  BUILTIN_TOOL_TOKENS,
  collectSlimReport,
  describeAccess,
  describeSummary,
  formatTokens,
  scanRedundancy,
  setPackageEnabled,
  summaryCeiling,
  type CardCost,
  type CardPreset,
  type ChatCost,
  type PackageCost,
  type RedundancyReport,
  type SlimReport,
} from "../../shared/slim.js";
import {
  applyRenames,
  CLASS_LABEL,
  deleteShortChats,
  lastRenameBatch,
  scanChats,
  undoLastRenames,
  writeDistillPrompt,
  type TidyChat,
  type TidyReport,
} from "../../shared/tidy.js";
import {
  clearAssignment,
  collectWarden,
  recordDelivery,
  setAssignment,
  stageLabel,
  type StandardType,
  type WardenReport,
} from "../../shared/warden.js";

type Page = "today" | "chats" | "warden" | "slim" | "sys";

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
const COLLAPSED_PACKAGES = 10;
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

  const [slim, setSlim] = ctx.useState<SlimReport | null>("slim", null);
  const [slimError, setSlimError] = ctx.useState("slimError", "");
  const [slimStatus, setSlimStatus] = ctx.useState<{ ok: boolean; text: string } | null>("slimStatus", null);
  const [confirmKey, setConfirmKey] = ctx.useState("confirmKey", "");
  const [busyKey, setBusyKey] = ctx.useState("busyKey", "");
  const [scan, setScan] = ctx.useState<RedundancyReport | null>("scan", null);
  const [showAllPackages, setShowAllPackages] = ctx.useState("showAllPackages", false);

  const [tidy, setTidy] = ctx.useState<TidyReport | null>("tidy", null);
  const [tidyPicked, setTidyPicked] = ctx.useState<string[]>("tidyPicked", []);
  const [tidyNote, setTidyNote] = ctx.useState<{ ok: boolean; text: string } | null>("tidyNote", null);
  const [renameBatch, setRenameBatch] = ctx.useState<{ batch: string; count: number } | null>("renameBatch", null);
  const [showAllRenames, setShowAllRenames] = ctx.useState("showAllRenames", false);

  const [warden, setWarden] = ctx.useState<WardenReport | null>("warden", null);
  const [wardenError, setWardenError] = ctx.useState("wardenError", "");
  const [wardenStatus, setWardenStatus] = ctx.useState<{ ok: boolean; text: string } | null>("wardenStatus", null);
  const [assignTask, setAssignTask] = ctx.useState("assignTask", "");
  const [assignStd, setAssignStd] = ctx.useState<StandardType>("assignStd", "product");
  const [assignFile, setAssignFile] = ctx.useState("assignFile", "");
  const [deliverQuote, setDeliverQuote] = ctx.useState("deliverQuote", "");
  const [deliverFile, setDeliverFile] = ctx.useState("deliverFile", "");
  const [wardenBusy, setWardenBusy] = ctx.useState("wardenBusy", false);
  const [showContract, setShowContract] = ctx.useState("showContract", false);

  const loadingRef = ctx.useRef("loadingRef", false);
  const slimLoadingRef = ctx.useRef("slimLoadingRef", false);
  const wardenLoadingRef = ctx.useRef("wardenLoadingRef", false);
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

  async function refreshSlim() {
    if (slimLoadingRef.current) return;
    slimLoadingRef.current = true;
    setSlimError("");
    try {
      setSlim(await collectSlimReport());
    } catch (error) {
      setSlimError(errorText(error));
    } finally {
      slimLoadingRef.current = false;
    }
  }

  async function refreshWarden() {
    if (wardenLoadingRef.current) return;
    wardenLoadingRef.current = true;
    setWardenError("");
    try {
      const report = await collectWarden();
      setWarden(report);
      if (report.view.assignment) setAssignTask(report.view.assignment.task);
    } catch (error) {
      setWardenError(errorText(error));
    } finally {
      wardenLoadingRef.current = false;
    }
  }

  async function openPage(next: Page) {
    setPage(next);
    if (next === "chats" && chats == null) await refreshChats();
    if (next === "slim" && slim == null) await refreshSlim();
    if (next === "warden" && warden == null) await refreshWarden();
  }

  async function createAssignment(deadlineMs: number) {
    if (wardenBusy) return;
    const task = assignTask.trim() || snap?.task?.task || "当前任务";
    setWardenBusy(true);
    try {
      await setAssignment({ task, deadlineMs, standard: { type: assignStd, detail: "", filePath: assignFile.trim() } });
      setWardenStatus({ ok: true, text: `派活：${task}（演练，不会真锁）` });
      await refreshWarden();
    } catch (error) {
      setWardenStatus({ ok: false, text: `没派上：${errorText(error)}` });
    } finally {
      setWardenBusy(false);
    }
  }

  async function stopAssignment() {
    if (wardenBusy) return;
    setWardenBusy(true);
    try {
      await clearAssignment();
      setWardenStatus({ ok: true, text: "撤下了这次督促" });
      await refreshWarden();
    } catch (error) {
      setWardenStatus({ ok: false, text: `没撤下：${errorText(error)}` });
    } finally {
      setWardenBusy(false);
    }
  }

  async function deliver() {
    if (wardenBusy) return;
    const quote = deliverQuote.trim();
    if (!quote && !deliverFile.trim()) {
      await ctx.showToast("写一句你交的是什么，或填个文件路径～");
      return;
    }
    setWardenBusy(true);
    try {
      const { delivery, reason } = await recordDelivery(quote || "（交了文件）", deliverFile.trim());
      if (!delivery) {
        setWardenStatus({ ok: false, text: reason ?? "没记上" });
        return;
      }
      setDeliverQuote("");
      setDeliverFile("");
      const verdict =
        delivery.autoCheck === "PRODUCT_OK" ? "产物核对通过，这次收工" : delivery.autoCheck === "PRODUCT_MISSING" ? "没找到产物或它还是派活前的" : "已收下，够不够要核对模型看（后续阶段）";
      setWardenStatus({ ok: delivery.autoCheck !== "PRODUCT_MISSING", text: verdict });
      await refreshWarden();
    } catch (error) {
      setWardenStatus({ ok: false, text: `没记上：${errorText(error)}` });
    } finally {
      setWardenBusy(false);
    }
  }

  // 会改设置的按钮都要点两次：第一次只把按钮变成"再点确认"
  async function confirmThen(key: string, run: () => Promise<string>) {
    if (busyKey) return;
    if (confirmKey !== key) {
      setConfirmKey(key);
      return;
    }
    setConfirmKey("");
    setBusyKey(key);
    try {
      setSlimStatus({ ok: true, text: await run() });
      await refreshSlim();
    } catch (error) {
      setSlimStatus({ ok: false, text: `没改成：${errorText(error)}` });
    } finally {
      setBusyKey("");
    }
  }

  async function runTidyScan() {
    if (busyKey) return;
    setBusyKey("tidy-scan");
    try {
      const report = await scanChats();
      setTidy(report);
      // 默认勾上所有没有保护的空对话
      setTidyPicked(report.chats.filter((c) => c.cls === "A" && !c.guard).map((c) => c.id));
      setRenameBatch(await lastRenameBatch().catch(() => null));
    } catch (error) {
      setTidyNote({ ok: false, text: `盘点失败：${errorText(error)}` });
    } finally {
      setBusyKey("");
    }
  }

  function togglePick(id: string) {
    setTidyPicked(tidyPicked.includes(id) ? tidyPicked.filter((x) => x !== id) : [...tidyPicked, id]);
  }

  async function tidyAction(key: string, run: () => Promise<string>) {
    if (busyKey) return;
    if (confirmKey !== key) {
      setConfirmKey(key);
      return;
    }
    setConfirmKey("");
    setBusyKey(key);
    try {
      const text = await run();
      setTidyNote({ ok: true, text });
      const report = await scanChats();
      setTidy(report);
      setTidyPicked(report.chats.filter((c) => c.cls === "A" && !c.guard).map((c) => c.id));
      setRenameBatch(await lastRenameBatch().catch(() => null));
    } catch (error) {
      setTidyNote({ ok: false, text: `没做成：${errorText(error)}` });
    } finally {
      setBusyKey("");
    }
  }

  async function runScan() {
    if (busyKey) return;
    setBusyKey("scan");
    try {
      setScan(await scanRedundancy());
    } catch (error) {
      setSlimStatus({ ok: false, text: `扫描失败：${errorText(error)}` });
    } finally {
      setBusyKey("");
    }
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
      UI.IconButton({ icon: Icons.Refresh, enabled: !loading, onClick: page === "chats" ? refreshChats : page === "slim" ? refreshSlim : page === "warden" ? refreshWarden : refresh }),
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
        text(`🎭 ${pending.iso.slice(11, 16)} ${companionName}来找过你`, "titleSmall", MOOD_INK[1]),
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
      UI.Box({ fillMaxSize: true, contentAlignment: "center" }, [text("🎭", "headlineMedium", colors.onSurface)])
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
        ? chatGroup("她（主入口）", her.map((c) => chatRow(c, "🎭")))
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
        text("动作记录与数据源", "titleMedium", colors.onSurface, { weight: 1 }),
        UI.TextButton({ onClick: () => setShowMore(!showMore) }, text(showMore ? "收起" : "展开", "labelLarge", colors.primary)),
      ]),
    ];
    if (showMore) {
      more.push(label("最近动作（历史记录，不代表当前状态）"));
      for (const cells of s.actions?.rows ?? []) more.push(muted(cells.join(" · "), 2));
      more.push(label("数据源"));
      for (const src of s.sources) {
        more.push(text(`${src.label}：${SOURCE_LABEL[src.status]}${src.status === "OK" ? "" : ` · ${src.detail}`}`, "bodySmall", src.status === "OK" ? colors.onSurfaceVariant : colors.error));
      }
      more.push(muted(`心情由采样、偏移和你的进展算出，只用来呈现，不会执行动作。读取于 ${formatDateTime(now)}。`));
    }
    items.push(card(more, 6));
    return pageColumn(items);
  }

  // ---------- 省流 ----------

  function actionButton(key: string, labelText: string, onClick: () => void | Promise<void>, danger = false): ComposeNode {
    const confirming = confirmKey === key;
    const busy = busyKey === key;
    const shown = busy ? "处理中…" : confirming ? "再点确认" : labelText;
    return UI.TextButton(
      { enabled: !busyKey || busy, onClick },
      text(shown, "labelLarge", confirming || danger ? MOOD_INK[3] : colors.primary)
    );
  }

  function flag(value: string, level: number): ComposeNode {
    return pill(value, MOOD_TINT[level], MOOD_INK[level]);
  }

  function chatCostRow(row: ChatCost, baselineAt: number | null): ComposeNode {
    const flags: ComposeNode[] = [];
    if (row.isHer) flags.push(flag("她", 0));
    if (row.backstage) flags.push(flag("后台角色", 1));
    if (row.lastSummaryAt === null && row.messages > 30) flags.push(flag("从没压缩", 3));
    const growth = row.growth != null && baselineAt != null && row.growth > 0 ? ` · 自 ${formatDateTime(baselineAt)} +${formatTokens(row.growth)}` : "";
    return UI.Column({ fillMaxWidth: true, spacing: 3, paddingVertical: 6 }, [
      UI.Row({ fillMaxWidth: true, spacing: 6, verticalAlignment: "center" }, [
        text(row.title, "bodyLarge", colors.onSurface, { weight: 1, maxLines: 1 }),
        ...flags,
      ]),
      muted(`${row.messages} 条 · 累计输入 ${formatTokens(row.input)} · 平均每条 ${formatTokens(row.perMessage)}${growth}`, 2),
      ...(row.card ? [muted(`角色卡：${row.card}`, 1)] : []),
    ]);
  }

  function cardRow(c: CardCost): ComposeNode {
    const run = (preset: CardPreset) => () => confirmThen(`card:${c.id}:${preset}`, () => applyCardPreset(c.id, preset));
    return UI.Column({ fillMaxWidth: true, spacing: 2, paddingVertical: 6 }, [
      UI.Row({ fillMaxWidth: true, spacing: 6, verticalAlignment: "center" }, [
        text(c.name, "bodyLarge", colors.onSurface, { weight: 1, maxLines: 1 }),
        text(c.access.enabled ? "已精简" : "全部工具", "labelMedium", c.access.enabled ? MOOD_INK[0] : colors.onSurfaceVariant),
      ]),
      muted(`人设约 ${formatTokens(c.promptTokens)} tokens · ${describeAccess(c.access)} · ${c.chats} 个对话`, 2),
      UI.Row({ fillMaxWidth: true, spacing: 2 }, [
        actionButton(`card:${c.id}:chat_only`, "只聊天", run("chat_only")),
        actionButton(`card:${c.id}:companion`, "陪伴", run("companion")),
        ...(c.hasBackup ? [actionButton(`card:${c.id}:restore`, "恢复", run("restore"))] : []),
      ]),
    ]);
  }

  function packageRow(p: PackageCost): ComposeNode {
    const key = `pkg:${p.name}`;
    const used = p.usedBy.length > 0;
    return UI.Row({ fillMaxWidth: true, spacing: 8, paddingVertical: 4, verticalAlignment: "center" }, [
      UI.Column({ weight: 1, spacing: 1 }, [
        text(p.displayName, "bodyMedium", colors.onSurface, { maxLines: 1 }),
        muted(`${p.name} · 约 ${p.tokens} tokens${used ? ` · 工作流在用：${p.usedBy.slice(0, 2).join("、")}${p.usedBy.length > 2 ? " 等" : ""}` : ""}`, 2),
      ]),
      actionButton(key, "停用", () => confirmThen(key, () => setPackageEnabled(p.name, false)), used),
    ]);
  }

  function tidyCard(): ComposeNode {
    const rows: ComposeNode[] = [
      UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
        text("对话整理", "titleMedium", colors.onSurface, { weight: 1 }),
        actionButton("tidy-scan", tidy ? "重新盘点" : "盘点", runTidyScan),
      ]),
      muted("只看条数、token、更新时间，不读内容，不花模型 token。删除和改名都要点两次；删除前先把内容备份，改名可以整批撤销。"),
    ];
    if (tidyNote) rows.push(text(tidyNote.text, "bodySmall", tidyNote.ok ? MOOD_INK[0] : colors.error));
    if (!tidy) return card(rows, 4);
    const t = tidy;
    rows.push(
      text(
        `共 ${t.totalCount} 个${t.complete ? "" : `（列出 ${t.listed} 个，有遗漏）`}：A ${t.counts.A} · B ${t.counts.B} · C ${t.counts.C} · D ${t.counts.D}`,
        "bodyMedium",
        colors.onSurface
      )
    );
    rows.push(muted(`A=${CLASS_LABEL.A}  B=${CLASS_LABEL.B}  C=${CLASS_LABEL.C}（>100 条或输入 >100 万）  D=${CLASS_LABEL.D}`));
    if (t.topTokens.length) {
      rows.push(label("token 主要花在"));
      for (const x of t.topTokens) rows.push(muted(`${x.title} · ${formatTokens(x.tokens)} · ${Math.round(x.share * 100)}%`, 1));
    }

    // A：空对话，勾选后删除
    const shorts = t.chats.filter((c) => c.cls === "A");
    if (shorts.length) {
      rows.push(label(`A · 空的或 1–2 句 · ${shorts.length} 个（勾选的会删除）`));
      for (const c of shorts.slice(0, 40)) {
        const picked = tidyPicked.includes(c.id);
        rows.push(
          UI.Surface(
            { fillMaxWidth: true, containerColor: colors.surface, onClick: c.guard ? undefined : () => togglePick(c.id) },
            UI.Row({ fillMaxWidth: true, spacing: 8, paddingVertical: 4, verticalAlignment: "center" }, [
              text(c.guard ? "🔒" : picked ? "☑" : "☐", "titleMedium", c.guard ? colors.onSurfaceVariant : colors.primary),
              UI.Column({ weight: 1, spacing: 0 }, [
                text(c.title, "bodyMedium", colors.onSurface, { maxLines: 1 }),
                muted(`${c.messageCount} 条${c.guard ? ` · ${c.guard}` : ""}`, 1),
              ]),
            ])
          )
        );
      }
      if (shorts.length > 40) rows.push(muted(`还有 ${shorts.length - 40} 个没显示，完整清单在盘点文件里。`));
      const picked = shorts.filter((c) => tidyPicked.includes(c.id) && !c.guard);
      rows.push(
        actionButton(`tidy-del:${picked.length}`, `删除勾选的 ${picked.length} 个`, () =>
          tidyAction(`tidy-del:${picked.length}`, async () => {
            const r = await deleteShortChats(picked);
            return `删了 ${r.deleted.length} 个${r.skipped.length ? `，跳过 ${r.skipped.length} 个（${r.skipped.slice(0, 2).map((x) => `${x.title}：${x.reason}`).join("；")}）` : ""}。内容备份在 companion/slim/deleted_chats.jsonl`;
          }),
        true
        )
      );
    }

    // B：标题重复
    if (t.duplicates.length) {
      rows.push(label(`B · 标题重复 · ${t.duplicates.length} 组`));
      for (const g of t.duplicates.slice(0, 8)) rows.push(muted(`${g.ids.length} 个：${g.titles.slice(0, 3).join(" / ")}${g.titles.length > 3 ? " …" : ""}`, 2));
      rows.push(muted("重复的先不自动处理：其中空的已经在 A 里；有内容的看一眼再决定。"));
    }

    // C：超长，生成提炼指令
    const longs = t.chats.filter((c) => c.cls === "C");
    if (longs.length) {
      rows.push(label(`C · 超长 · ${longs.length} 个`));
      for (const c of longs.slice(0, 10)) {
        const key = `distill:${c.id}`;
        rows.push(
          UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
            UI.Column({ weight: 1, spacing: 0 }, [
              text(c.title, "bodyMedium", colors.onSurface, { maxLines: 1 }),
              muted(`${c.messageCount} 条 · ${formatTokens(c.tokens)} tokens${c.guard ? ` · ${c.guard}` : ""}`, 1),
            ]),
            actionButton(key, "提炼指令", async () => {
              if (busyKey) return;
              setBusyKey(key);
              try {
                const path = await writeDistillPrompt(c);
                setTidyNote({ ok: true, text: `提炼指令写在 ${path}，打开复制给 Operit AI 就行（它只读、写说明、问你之后才动）` });
              } catch (error) {
                setTidyNote({ ok: false, text: `没写成：${errorText(error)}` });
              } finally {
                setBusyKey("");
              }
            }),
          ])
        );
      }
    }

    // 改名方案
    const renames = t.chats.filter((c) => c.proposedTitle);
    if (renames.length) {
      rows.push(label(`改名方案 · ${renames.length} 个（"[类别] 原标题"）`));
      const shown = showAllRenames ? renames : renames.slice(0, 8);
      for (const c of shown) rows.push(muted(`${c.title} → ${c.proposedTitle}`, 1));
      if (renames.length > 8) {
        rows.push(UI.TextButton({ onClick: () => setShowAllRenames(!showAllRenames) }, text(showAllRenames ? "收起" : `看全部 ${renames.length} 个`, "labelLarge", colors.primary)));
      }
      rows.push(muted("类别只按标题和用途判断（她 / 工作流 / 角色 / 测试 / 长对话 / 日常），看不出内容；不合适的可以之后手动改。"));
      rows.push(
        actionButton(`tidy-rename:${renames.length}`, `全部改名（${renames.length}）`, () =>
          tidyAction(`tidy-rename:${renames.length}`, async () => {
            const r = await applyRenames(renames);
            return `改了 ${r.renamed} 个${r.failed.length ? `，失败 ${r.failed.length} 个` : ""}。可以点「撤销上次改名」恢复`;
          })
        )
      );
    }
    if (renameBatch) {
      rows.push(
        actionButton("tidy-undo", `撤销上次改名（${renameBatch.count} 个）`, () =>
          tidyAction("tidy-undo", async () => {
            const r = await undoLastRenames();
            return `恢复了 ${r.restored} 个标题${r.failed.length ? `，失败 ${r.failed.length} 个` : ""}`;
          })
        )
      );
    }
    rows.push(muted(`完整盘点表写在 ${t.reportPath}。`));
    return card(rows, 4);
  }

  function slimPage(): ComposeNode {
    const items: ComposeNode[] = [pageHeader("省流")];
    if (slimStatus) {
      items.push(card([text(slimStatus.text, "bodyMedium", slimStatus.ok ? MOOD_INK[0] : colors.error)], 4, slimStatus.ok ? MOOD_TINT[0] : MOOD_TINT[3]));
    }
    if (slimError) items.push(card([text(`读取失败：${slimError}`, "bodyMedium", colors.error)]));
    if (!slim) {
      items.push(spinner());
      return pageColumn(items);
    }
    const r = slim;
    const ceiling = r.summary ? summaryCeiling(r.summary) : null;

    // 一句话讲清楚钱花在哪
    const head: ComposeNode[] = [
      label("每次和 AI 说一句话，发出去的是：人设 + 工具说明 + 这段对话的历史"),
      muted("历史越长，每一句越贵；后台角色被工作流每半小时喂一次，最容易越滚越大。"),
    ];
    if (r.chats) {
      head.push(
        UI.Row({ fillMaxWidth: true, spacing: 16 }, [
          UI.Column({ weight: 1, spacing: 0 }, [text(formatTokens(r.chats.totalInput), "headlineSmall", colors.onSurface), muted("所有对话累计输入")]),
          UI.Column({ weight: 1, spacing: 0 }, [
            text(r.chats.grownSince != null ? `+${formatTokens(r.chats.grownSince)}` : "—", "headlineSmall", MOOD_INK[2]),
            muted(r.chats.baselineAt != null ? `自 ${formatDateTime(r.chats.baselineAt)}` : "明天再看就有增长数字"),
          ]),
        ])
      );
    }
    if (ceiling) head.push(muted(`按现在的设置，长对话每一句最多带约 ${formatTokens(ceiling)} tokens 历史才会压缩。`));
    items.push(card(head, 8));
    items.push(tidyCard());

    if (r.chats) {
      items.push(
        card(
          [
            text("最费的对话", "titleMedium", colors.onSurface),
            ...r.chats.rows.map((row) => chatCostRow(row, r.chats?.baselineAt ?? null)),
            muted(`共 ${r.chats.totalChats} 个对话，${r.chats.idleChats} 个 7 天没动过（归档建议工作流会处理，主控台不删对话）。`),
          ],
          2
        )
      );
    }

    if (r.summary) {
      const s = r.summary;
      const advice: string[] = [];
      if (!s.enableSummary) advice.push("自动总结关着：对话会一直带着全部历史，建议打开。");
      if (s.contextLength >= 32) advice.push(`上下文 ${s.contextLength}K 对后台角色偏大，调到 16–32K 能直接砍掉大半费用。`);
      if (!s.byMessageCount) advice.push("可以再打开「按条数总结」，让后台角色的历史不再越滚越长。");
      items.push(
        card([
          text("自动压缩", "titleMedium", colors.onSurface),
          text(`「${s.configName}」：${describeSummary(s)}`, "bodyMedium", colors.onSurface),
          ...(advice.length ? advice.map((a) => muted(`· ${a}`)) : [muted("设置已经比较省。")]),
          muted("这是全局模型设置，主控台只读；在 Operit 设置 → 模型配置里改。"),
        ], 6)
      );
    }

    if (r.cards) {
      const used = r.cards.filter((c) => c.chats > 0 || c.hasBackup || c.access.enabled).slice(0, 10);
      items.push(
        card(
          [
            text("角色卡带多少工具", "titleMedium", colors.onSurface),
            muted(`「全部工具」的角色每句话都带内置工具说明（约 ${BUILTIN_TOOL_TOKENS} tokens）和整张工具包清单。`),
            muted("只聊天 = 不带任何工具，适合判断官、秘书这种只输出文字的角色；它的工作流要是需要它读文件，就别选。陪伴 = 只留主控台和语音。改之前自动备份，随时点「恢复」。"),
            ...used.map(cardRow),
          ],
          2
        )
      );
    }

    if (r.packages) {
      const pk = r.packages;
      const visible = showAllPackages ? pk.enabled : pk.enabled.slice(0, COLLAPSED_PACKAGES);
      const rows: ComposeNode[] = [
        UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
          text("每句话都带的工具包清单", "titleMedium", colors.onSurface, { weight: 1 }),
          ...(pk.enabled.length > COLLAPSED_PACKAGES
            ? [UI.TextButton({ onClick: () => setShowAllPackages(!showAllPackages) }, text(showAllPackages ? "收起" : `全部 ${pk.enabled.length} 个`, "labelLarge", colors.primary))]
            : []),
        ]),
        muted(`开着 ${pk.enabled.length} 个，清单每句约 ${formatTokens(pk.listTokens)} tokens。停用只是关掉，随时可以在这里或包管理里重新启用。标红的是工作流在用的，别停。`),
        ...visible.map(packageRow),
      ];
      if (pk.disabledByHub.length) {
        rows.push(label("主控台停用过的"));
        for (const p of pk.disabledByHub) {
          const key = `pkg-on:${p.name}`;
          rows.push(
            UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
              text(p.displayName, "bodyMedium", colors.onSurfaceVariant, { weight: 1, maxLines: 1 }),
              actionButton(key, "重新启用", () => confirmThen(key, () => setPackageEnabled(p.name, true))),
            ])
          );
        }
      }
      items.push(card(rows, 2));
    }

    const scanRows: ComposeNode[] = [
      UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
        text("外面的冗余", "titleMedium", colors.onSurface, { weight: 1 }),
        actionButton("scan", scan ? "重新扫描" : "扫描", runScan),
      ]),
      muted("只读：找 Operit 目录里没被启用中工作流用到、7 天没动过的东西，和过期的一次性/长期不用的手动工作流。不移动、不删除。"),
    ];
    if (scan) {
      scanRows.push(label(`文件与目录 · ${scan.candidates.length} 个候选（${scan.kept} 个在用或最近动过）`));
      for (const c of scan.candidates.slice(0, 15)) {
        scanRows.push(muted(`${c.isDirectory ? "📁" : "📄"} ${c.name} · ${c.newest ? `最近改动 ${formatDateTime(c.newest)}` : "时间未知"}${c.disabledRefs.length ? ` · 只被停用的「${c.disabledRefs[0]}」提到` : ""}`, 2));
      }
      if (scan.workflows.length) {
        scanRows.push(label("可以考虑停用的工作流"));
        for (const w of scan.workflows) scanRows.push(muted(`${w.name}：${w.why}`, 2));
      }
      scanRows.push(muted(`完整清单写在 ${scan.reportPath}，可以直接交给 Operit AI 或流程线核对。`));
    }
    items.push(card(scanRows, 4));

    if (r.actions.length) {
      items.push(
        card([
          label("主控台做过的改动"),
          ...r.actions.map((a) => muted(`${a.iso} · ${a.label} · ${a.kind === "card" ? `角色卡「${a.detail}」` : a.target}${a.ok ? "" : " · 未生效"}`, 2)),
          muted("全部记录在 companion/slim/actions.jsonl。"),
        ], 4)
      );
    }
    for (const e of r.errors) items.push(muted(`读取失败：${e}`));
    return pageColumn(items);
  }

  // ---------- 督促（演练） ----------

  function stdChip(type: StandardType, name: string, hint: string): ComposeNode {
    const on = assignStd === type;
    return UI.Surface(
      { weight: 1, containerColor: on ? MOOD_TINT[0] : colors.surfaceVariant, shape: { cornerRadius: 12 }, onClick: () => setAssignStd(type) },
      UI.Column({ fillMaxWidth: true, padding: 10, spacing: 1 }, [
        text(name, "labelLarge", on ? MOOD_INK[0] : colors.onSurface),
        muted(hint, 2),
      ])
    );
  }

  function deadlineButton(labelText: string, minutes: number | "tonight"): ComposeNode {
    return UI.Button({
      weight: 1,
      text: labelText,
      enabled: !wardenBusy,
      onClick: () => {
        const now = Date.now();
        let deadline = now + (typeof minutes === "number" ? minutes * 60000 : 0);
        if (minutes === "tonight") {
          const d = new Date(now);
          d.setHours(21, 0, 0, 0);
          if (d.getTime() <= now) d.setDate(d.getDate() + 1);
          deadline = d.getTime();
        }
        return createAssignment(deadline);
      },
    });
  }

  function wardenBanner(r: WardenReport): ComposeNode {
    const v = r.view;
    const level = v.stage === "TIER2" ? 3 : v.stage === "TIER1" ? 2 : v.stage === "GRACE" ? 1 : 0;
    const lines: ComposeNode[] = [
      UI.Row({ fillMaxWidth: true, spacing: 8, verticalAlignment: "center" }, [
        text(stageLabel(v.stage), "titleMedium", MOOD_INK[level], { weight: 1 }),
        ...(v.minutesToDeadline != null
          ? [text(v.minutesToDeadline >= 0 ? `还剩 ${v.minutesToDeadline} 分钟` : `超时 ${-v.minutesToDeadline} 分钟`, "labelLarge", v.minutesToDeadline >= 0 ? colors.onSurfaceVariant : MOOD_INK[2])]
          : []),
      ]),
    ];
    if (v.line) lines.push(text(`“${v.line}”`, "bodyMedium", colors.onSurface));
    if (v.wouldLock.length) {
      lines.push(text(`演练：这一拍本来会锁 ${v.wouldLock.map((a) => a.name).join("、")}`, "bodyMedium", MOOD_INK[3]));
      lines.push(muted("第一阶段不会真锁，只是记下来。等你觉得判断准了，我们再开真锁。"));
    }
    if (v.reasons.length) lines.push(muted(v.reasons.join("；")));
    return card(lines, 6, MOOD_TINT[level]);
  }

  function wardenPage(): ComposeNode {
    const items: ComposeNode[] = [pageHeader("督促")];
    items.push(card([
      text("演练模式：只记录，不锁任何 App", "titleSmall", MOOD_INK[0]),
      muted("它按你签的契约和时间，算出到点没交差时本来会锁什么，写进日志给你核对。真锁要你逐项确认契约后单独开。这手机始终是你的，系统设置里随时能停用 Operit——它做的是让放弃有代价、被记下，把你拉回来面对任务。"),
    ], 4, MOOD_TINT[0]));

    if (wardenStatus) items.push(card([text(wardenStatus.text, "bodyMedium", wardenStatus.ok ? MOOD_INK[0] : colors.error)], 4, wardenStatus.ok ? MOOD_TINT[0] : MOOD_TINT[3]));
    if (wardenError) items.push(card([text(`读取失败：${wardenError}`, "bodyMedium", colors.error)]));
    if (!warden) {
      items.push(spinner());
      return pageColumn(items);
    }
    const r = warden;

    if (r.view.assignment) {
      items.push(wardenBanner(r));
      // 交差
      items.push(card([
        text("交差", "titleMedium", colors.onSurface),
        muted("交你为这个任务做出来的东西。产物型会核对文件是不是派活之后改的；其它类型先收下，够不够以后交给核对模型。"),
        UI.TextField({ fillMaxWidth: true, value: deliverQuote, onValueChange: setDeliverQuote, placeholder: "一句话：我做了什么 / 投了哪几家" }),
        UI.TextField({ fillMaxWidth: true, value: deliverFile, onValueChange: setDeliverFile, placeholder: "产物文件路径（可选）", singleLine: true }),
        UI.Row({ fillMaxWidth: true, spacing: 8 }, [
          UI.Button({ weight: 1, text: wardenBusy ? "记录中…" : "交差", enabled: !wardenBusy, onClick: deliver }),
          UI.TextButton({ enabled: !wardenBusy, onClick: stopAssignment }, text("撤下", "labelLarge", colors.onSurfaceVariant)),
        ]),
      ], 8));
    } else {
      items.push(card([
        text("给自己派个活", "titleMedium", colors.onSurface),
        UI.TextField({ fillMaxWidth: true, value: assignTask, onValueChange: setAssignTask, placeholder: snap?.task?.task || "要推进的任务", singleLine: true }),
        muted("怎么算完成："),
        UI.Row({ fillMaxWidth: true, spacing: 6 }, [
          stdChip("product", "产物", "有文件，代码能核对"),
          stdChip("count", "计数", "投几家之类，交截图"),
          stdChip("self", "自述", "只说想清楚了，最弱"),
        ]),
        ...(assignStd === "product" ? [UI.TextField({ fillMaxWidth: true, value: assignFile, onValueChange: setAssignFile, placeholder: "产物文件路径（可选）", singleLine: true })] : []),
        muted("截止时间："),
        UI.Row({ fillMaxWidth: true, spacing: 6 }, [deadlineButton("30 分钟", 30), deadlineButton("1 小时", 60), deadlineButton("2 小时", 120), deadlineButton("今晚 21 点", "tonight")]),
      ], 8));
    }

    // 契约（你签的，可折叠）
    const c = r.contract;
    const contractHead: ComposeNode[] = [
      UI.Row({ fillMaxWidth: true, verticalAlignment: "center" }, [
        text("契约", "titleMedium", colors.onSurface, { weight: 1 }),
        UI.TextButton({ onClick: () => setShowContract(!showContract) }, text(showContract ? "收起" : "看看", "labelLarge", colors.primary)),
      ]),
      muted(`一级 ${c.tier1.length} 个 · 二级 ${c.tier2.length} 个 · 保护 ${c.protectedApps.length} 个${r.unconfirmed.length ? ` · ${r.unconfirmed.length} 项待你拍板` : " · 已全部确认"}`),
    ];
    if (showContract) {
      const appList = (title: string, apps: { name: string; verified: boolean }[], level: number) =>
        UI.Column({ fillMaxWidth: true, spacing: 1, paddingVertical: 4 }, [
          label(title),
          text(apps.map((a) => `${a.name}${a.verified ? "" : "?"}`).join("　"), "bodyMedium", MOOD_INK[level]),
        ]);
      contractHead.push(appList("一级（先锁）", c.tier1, 2));
      contractHead.push(appList("二级（升级后锁）", c.tier2, 3));
      contractHead.push(appList("观望（只提醒）", c.watch, 1));
      contractHead.push(muted(`名字后带 ? 的包名还没在真机核对过；真锁前要确认。`));
      contractHead.push(muted(`时间：超时 ${c.timing.graceMinutes} 分钟宽限后一级，再过 ${c.timing.tier2AfterMinutes} 分钟升二级，每天 ${c.timing.dailyUnlockHour} 点全部自动解开。`));
      contractHead.push(muted(`额度：每天暂停 ${c.limits.pausePerDay} 次、人工解锁 ${c.limits.unlockPerDay} 次，每周急停 ${c.limits.emergencyPerWeek} 次（冷静期 ${c.limits.coolDownMinutes} 分钟）。核对模型 ${c.judgeModel}。`));
      if (r.unconfirmed.length) contractHead.push(text(`还没拍板：${r.unconfirmed.join("、")}`, "bodySmall", MOOD_INK[2]));
      contractHead.push(muted("改契约要在 companion/warden/contract.json 里写，或交给我/流程线；这里只显示。"));
    }
    items.push(card(contractHead, 4));

    // 演练日志
    if (r.dryRun.length) {
      items.push(card([
        label(`演练记录 · ${r.dryRun.length} 条（本来会锁什么，没真锁）`),
        ...r.dryRun.slice(0, 12).map((row) =>
          UI.Column({ fillMaxWidth: true, spacing: 1 }, [
            UI.Row({ fillMaxWidth: true, spacing: 8 }, [
              text(stageLabel(row.stage as never) || row.stage, "labelLarge", MOOD_INK[3]),
              muted(`${row.iso}${row.overdueMin != null ? ` · 超时 ${row.overdueMin} 分钟` : ""}`, 1),
            ]),
            muted(`${row.task || "—"} → ${row.wouldLock.join("、") || "—"}`, 2),
          ])
        ),
        muted("写在 companion/warden/dryrun.jsonl。"),
      ], 6));
    } else {
      items.push(card([muted("还没有演练记录。派个活、把截止时间调短，等打点工作流跑一拍（或到点后打开这页），就能看到它本来会锁什么。")], 4));
    }
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

  const body = page === "today" ? todayPage() : page === "chats" ? chatsPage() : page === "warden" ? wardenPage() : page === "slim" ? slimPage() : sysPage();

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
      UI.Row({ fillMaxWidth: true }, [navItem("today", "☀", "今天"), navItem("chats", "💬", "会话"), navItem("warden", "⏱", "督促"), navItem("slim", "🍃", "省流"), navItem("sys", "⚙", "系统")]),
    ]
  );
}
