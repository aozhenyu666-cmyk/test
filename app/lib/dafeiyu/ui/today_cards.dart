import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../runtime/runtime_bridge.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'health_page.dart';
import 'seal_page.dart' show kSealKinds, sealSpan;

/// 今日使用情况：硬日判 + 时间账 + 时间投向合成一张卡（局页和中枢共用这一张，不各画一份）。
/// 点任意位置进完整的统计页（完整时间账、24h 切片、App 排行、晚间总报）。
class TodayUsageCard extends StatelessWidget {
  const TodayUsageCard({super.key, required this.today, this.verdict, this.ledger, this.onTap});

  /// `/today` 的原样回包（charts.buckets / brief / autonomy / geminiMinutesToday）。
  final Map<String, dynamic> today;
  final DailyVerdictView? verdict;
  final TimeLedgerView? ledger;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final verdict = this.verdict;
    final ledger = this.ledger;
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final charts = today['charts'] is Map ? today['charts'] as Map : const {};
    final buckets = charts['buckets'] is Map ? charts['buckets'] as Map : const {};
    final brief = today['brief'] is Map ? today['brief'] as Map : const {};
    int m(Object? v) => v is num ? v.round() : 0;
    final mainlineMin = buckets['mainlineMin'] is num ? m(buckets['mainlineMin']) : m(brief['focusMin']);
    final geminiMin = buckets['geminiMin'] is num ? m(buckets['geminiMin']) : m(today['geminiMinutesToday']);
    final videoMin = m(buckets['videoStudyMin']);
    final tinkerMin = m(buckets['toolTinkerMin']);
    final funMin = buckets['funMin'] is num ? m(buckets['funMin']) : m(brief['funMin']);
    final segments = <(String, int, Color)>[
      ('主线攻坚', mainlineMin, colors.success),
      ('Gemini 沟通', geminiMin, colors.info),
      ('视频学习', videoMin, scheme.primary),
      ('折腾工具', tinkerMin, colors.warning),
      ('纯娱乐', funMin, colors.danger),
    ];
    final total = segments.fold<int>(0, (s, e) => s + e.$2);
    final verdictTone = verdict == null
        ? StatusTone.idle
        : (verdict.isDone ? StatusTone.ok : (verdict.isIdle ? StatusTone.error : StatusTone.warn));
    final verdictColor = verdict == null
        ? colors.textTertiary
        : (verdict.isDone ? colors.success : (verdict.isIdle ? colors.danger : colors.warning));
    final briefLine = brief['line'] is String ? (brief['line'] as String).trim() : '';
    final footLine = verdict != null && verdict.line.isNotEmpty ? verdict.line : briefLine;
    final autonomy = today['autonomy'] is Map ? today['autonomy'] as Map : const {};
    final relaxHint = '${autonomy['autoRewardLabel'] ?? ''}'.trim();
    final autonomyLine = autonomy.isEmpty
        ? ''
        : '自律 ${autonomy['level'] ?? 'L1'} ${autonomy['name'] ?? '契约自治'}${relaxHint.isNotEmpty ? ' · $relaxHint' : ''}';

    Widget stat(String label, String value, String detail, Color accent) => Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
              const SizedBox(height: 2),
              Text(
                value,
                style: AppType.tabular(theme.textTheme.titleLarge!.copyWith(fontWeight: FontWeight.w700, color: accent)),
              ),
              Text(
                detail,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary, fontSize: 10.5),
              ),
            ],
          ),
        );

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: GlassSurface(
        key: const ValueKey('today-usage-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const OverviewGlyph(AppIcons.chart, tint: 2),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('今日使用情况', style: theme.textTheme.titleMedium),
                        const SizedBox(height: 2),
                        Text(
                          verdict != null ? '硬日判：${verdict.tag}' : '今天还没出日判',
                          style: theme.textTheme.bodySmall?.copyWith(color: verdictColor, fontWeight: FontWeight.w600),
                        ),
                      ],
                    ),
                  ),
                  if (verdict != null) StatusChip(tone: verdictTone, label: '${verdict.scoreLabel} ${verdict.cappedScore}'),
                ],
              ),
              if (ledger != null) ...[
                const SizedBox(height: 14),
                Row(
                  children: [
                    stat(
                      '娱乐余额',
                      ledger.funOut ? '0m' : '${ledger.funLeft}m',
                      ledger.carry > 0 ? '含昨天结转 ${ledger.carry}m' : '没花完的明天接着用',
                      ledger.funOut ? colors.danger : scheme.primary,
                    ),
                    stat(
                      '今天挣来',
                      '+${ledger.earned}m',
                      ledger.penalty > 0 ? '扣罚 -${ledger.penalty}m' : '局+${ledger.earnedBout} · 题+${ledger.earnedBank}',
                      colors.success,
                    ),
                    stat(
                      '游戏还能玩',
                      '${ledger.gameLeft}m',
                      '每天上限 ${ledger.gameQuota}m',
                      ledger.gameOut ? colors.warning : colors.info,
                    ),
                  ],
                ),
              ],
              const SizedBox(height: 14),
              ClipRRect(
                borderRadius: BorderRadius.circular(999),
                child: SizedBox(
                  height: 8,
                  child: Row(
                    children: [
                      for (final (_, minutes, color) in segments)
                        if (minutes > 0) Expanded(flex: minutes, child: ColoredBox(color: color)),
                      if (total == 0)
                        Expanded(child: ColoredBox(color: scheme.outlineVariant.withValues(alpha: 0.4))),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 8),
              if (total == 0)
                Text('今天还没记到使用时长', style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary))
              else
                Wrap(
                  spacing: 12,
                  runSpacing: 2,
                  children: [
                    for (final (label, minutes, color) in segments)
                      if (minutes > 0)
                        Text('● $label ${minutes}m', style: theme.textTheme.bodySmall?.copyWith(color: color)),
                  ],
                ),
              if (footLine.isNotEmpty) ...[
                const SizedBox(height: 10),
                Text(
                  footLine,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                ),
              ],
              if (autonomyLine.isNotEmpty) ...[
                const SizedBox(height: 4),
                Text(
                  autonomyLine,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: autonomy['unlockedRelaxation'] == true ? colors.success : colors.textTertiary,
                  ),
                ),
              ],
              const SizedBox(height: 6),
              Divider(height: 12, thickness: 0.5, color: scheme.outlineVariant.withValues(alpha: 0.45)),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '查看完整使用情况',
                      style: theme.textTheme.labelLarge?.copyWith(color: scheme.primary, fontWeight: FontWeight.w600),
                    ),
                  ),
                  Text('时间账 · 24h · App 排行 · 晚报', style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
                  const SizedBox(width: 4),
                  Icon(AppIcons.forward, size: 16, color: colors.textTertiary),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 锁机体检：锁落不下来的几个原因（无障碍没开、测试模式开着、引擎没应答），有一个就顶在局页最上面，
/// 每一行点一下就去修。全部正常时只留一行「锁机就绪」。
class LockReadinessCard extends StatefulWidget {
  const LockReadinessCard({super.key, required this.channel, this.bridge});

  final ProactiveChannel channel;

  /// 测试用；默认走真的 RuntimeBridge 打开厂商权限页。
  final RuntimeBridge? bridge;

  @override
  State<LockReadinessCard> createState() => _LockReadinessCardState();
}

class _LockReadinessCardState extends State<LockReadinessCard> with WidgetsBindingObserver {
  bool _loaded = false;
  bool? _a11y;
  bool? _shadow;
  bool _busy = false;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_load());
    // 引擎刚启动时常常还没应答：隔 30 秒再看一次，别让「引擎没应答」一直挂着。
    _poll = Timer.periodic(const Duration(seconds: 30), (_) {
      if (mounted) unawaited(_load());
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 从系统设置页回来时重读一遍（他刚去开了无障碍）。
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_load());
  }

  Future<void> _load() async {
    final results = await Future.wait<Object?>([widget.channel.status(), widget.channel.lockShadow()]);
    if (!mounted) return;
    final status = results[0] as ProactiveStatus?;
    setState(() {
      _a11y = status?.capabilities['a11y'];
      _shadow = results[1] as bool?;
      _loaded = true;
    });
  }

  Future<void> _setShadow(bool on) async {
    setState(() => _busy = true);
    final ok = await widget.channel.setCompanionConfig({'lockShadow': on});
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (ok) _shadow = on;
    });
    if (!ok) {
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(const SnackBar(content: Text('引擎没应答，测试模式没改成功')));
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded) return const SizedBox.shrink();
    final colors = DshColors.of(context);
    final a11yOff = _a11y == false;
    final engineOff = _shadow == null;
    final shadowOn = _shadow == true;
    final ready = !a11yOff && !engineOff && !shadowOn;
    if (ready) {
      return ListPageCard(
        key: const ValueKey('lock-ready'),
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 14),
        children: [
          ListPageRow(
            icon: AppIcons.lock,
            iconColor: colors.success,
            title: '锁机就绪',
            subtitle: '无障碍已连 · 测试模式已关 · 被锁出不来时下拉通知栏点「紧急解除」 · 点这一行看体检',
            subtitleMaxLines: 2,
            onTap: () => unawaited(HealthPage.open(context, channel: widget.channel)),
            trailing: Switch.adaptive(value: false, onChanged: _busy ? null : (v) => unawaited(_setShadow(v))),
          ),
        ],
      );
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
      child: GlassSurface(
        key: const ValueKey('lock-not-ready'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 4),
              child: Row(
                children: [
                  Icon(AppIcons.warning, size: 20, color: colors.warning),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      '锁机现在落不下来',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(color: colors.warning, fontWeight: FontWeight.w700),
                    ),
                  ),
                  const StatusChip(tone: StatusTone.warn, label: '点下面修'),
                ],
              ),
            ),
            if (a11yOff)
              ListPageRow(
                key: const ValueKey('lock-fix-a11y'),
                icon: AppIcons.warning,
                iconColor: colors.danger,
                title: '无障碍没开',
                subtitle: '锁要靠它看前台是哪个 App。iQOO 更新或重装后常被系统关掉，去「已安装的服务」里打开大肥鱼',
                subtitleMaxLines: 3,
                value: '去开启',
                chevron: true,
                onTap: () => unawaited(widget.channel.openCapability('a11y')),
              ),
            if (engineOff)
              ListPageRow(
                key: const ValueKey('lock-fix-engine'),
                icon: AppIcons.warning,
                iconColor: colors.warning,
                title: '引擎没应答',
                subtitle: '新开的局、接管、超额封禁都要引擎判；现在只按上次缓存的规则锁。点这里重查',
                subtitleMaxLines: 3,
                onTap: () => unawaited(_load()),
              ),
            if (shadowOn)
              ListPageRow(
                key: const ValueKey('lock-fix-shadow'),
                icon: AppIcons.info,
                iconColor: colors.info,
                title: '测试模式开着',
                subtitle: '锁只弹「这里本来会锁」，不真锁。关掉就真锁',
                subtitleMaxLines: 2,
                trailing: Switch.adaptive(value: true, onChanged: _busy ? null : (v) => unawaited(_setShadow(v))),
              ),
            ListPageRow(
              icon: AppIcons.info,
              title: '都开了还是不锁？',
              subtitle: 'iQOO：设置 → 应用 → 大肥鱼 → 权限 → 「后台弹出界面」「锁屏显示」打开（锁页要从后台弹出来）',
              subtitleMaxLines: 3,
              chevron: true,
              onTap: () => unawaited((widget.bridge ?? RuntimeBridge()).openVendorSettings('popup')),
            ),
            const SizedBox(height: 4),
          ],
        ),
      ),
    );
  }
}

/// 4.9 早上「点头卡」：如果今天还没落验收点，把当日模板整理成轻量菜单，
/// 允许微调拉伸/保底数字后一键「点头锁定今日菜单」。
class NodMenuCard extends StatefulWidget {
  const NodMenuCard({
    super.key,
    required this.channel,
    this.refreshTick = 0,
    this.onLocked,
  });

  final ProactiveChannel channel;
  final int refreshTick;
  final VoidCallback? onLocked;

  @override
  State<NodMenuCard> createState() => _NodMenuCardState();
}

class _NodMenuCardState extends State<NodMenuCard> {
  bool _needed = false;
  bool _submitting = false;
  List<Map<String, dynamic>> _items = const [];

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void didUpdateWidget(covariant NodMenuCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.refreshTick != widget.refreshTick) {
      unawaited(_load());
    }
  }

  Future<void> _load() async {
    final res = await widget.channel.sealsOp(const {'action': 'nod_card_get'});
    if (!mounted) return;
    if (res == null || res['ok'] != true || res['needed'] != true) {
      setState(() {
        _needed = false;
        _items = const [];
      });
      return;
    }
    final raw = res['items'];
    final items = raw is List
        ? [for (final x in raw) if (x is Map) Map<String, dynamic>.from(x)]
        : <Map<String, dynamic>>[];
    setState(() {
      _needed = items.isNotEmpty;
      _items = items;
    });
  }

  void _adjust(int index, {int? stretch, int? floor}) {
    final next = [for (final item in _items) Map<String, dynamic>.from(item)];
    final cur = next[index];
    final curStretch = ((cur['stretch'] as num?)?.toInt() ?? 1).clamp(1, 99999);
    final curFloor = ((cur['floor'] as num?)?.toInt() ?? 1).clamp(1, curStretch);
    final newStretch = (stretch ?? curStretch).clamp(1, 99999);
    final newFloor = (floor ?? curFloor).clamp(1, newStretch);
    cur['stretch'] = newStretch;
    cur['floor'] = newFloor;
    setState(() => _items = next);
  }

  Future<void> _editNumber(int index, {required bool isStretch}) async {
    final item = _items[index];
    final kind = '${item['kind'] ?? 'quiz'}';
    final unit = kSealKinds[kind]?.$2 ?? '';
    final curStretch = ((item['stretch'] as num?)?.toInt() ?? 1).clamp(1, 99999);
    final curFloor = ((item['floor'] as num?)?.toInt() ?? 1).clamp(1, curStretch);
    final initial = isStretch ? curStretch : curFloor;
    final typed = await showDialog<int>(
      context: context,
      builder: (_) => _NodNumberDialog(
        title: '${item['title'] ?? '验收点'} · ${isStretch ? '拉伸目标' : '保底目标'}',
        label: '${isStretch ? '拉伸' : '保底'}${unit.isEmpty ? '' : '（$unit）'}',
        helper: isStretch ? '至少 1' : '1 到拉伸（$curStretch）之间',
        initial: initial,
      ),
    );
    if (typed == null || !mounted) return;
    if (isStretch) {
      _adjust(index, stretch: typed);
    } else {
      _adjust(index, floor: typed);
    }
  }

  Future<void> _confirm() async {
    if (_submitting || _items.isEmpty) return;
    setState(() => _submitting = true);
    final editedItems = [
      for (final item in _items)
        <String, Object?>{
          for (final e in item.entries) e.key: e.value,
        },
    ];
    final res = await widget.channel.sealsOp({
      'action': 'nod_card_confirm',
      'items': editedItems,
    });
    if (!mounted) return;
    final ok = res?['ok'] == true;
    setState(() {
      _submitting = false;
      if (ok) {
        _needed = false;
        _items = const [];
      }
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(
        content: Text(
          ok
              ? '${res?['text'] ?? '今日菜单已点头锁定'}'
              : '${res?['text'] ?? res?['error'] ?? '锁定失败，请检查引擎连接'}',
        ),
      ),
    );
    if (ok) widget.onLocked?.call();
  }

  @override
  Widget build(BuildContext context) {
    if (!_needed || _items.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final scheme = theme.colorScheme;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: GlassSurface(
        key: const ValueKey('nod-menu-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Material(
          type: MaterialType.transparency,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    const OverviewGlyph(AppIcons.planMode, tint: 4, size: 40),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('今日菜单点头卡', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                          const SizedBox(height: 2),
                          Text(
                            '微调拉伸与保底数字后点头锁定，今天按这张菜单走',
                            style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                          ),
                        ],
                      ),
                    ),
                    const StatusChip(tone: StatusTone.warn, label: '待点头'),
                  ],
                ),
                const SizedBox(height: 12),
                for (var i = 0; i < _items.length; i++) ...[
                  if (i > 0) Divider(height: 16, thickness: 0.5, color: scheme.outlineVariant.withValues(alpha: 0.45)),
                  _NodItemRow(
                    index: i,
                    item: _items[i],
                    busy: _submitting,
                    onAdjustStretch: (v) => _adjust(i, stretch: v),
                    onAdjustFloor: (v) => _adjust(i, floor: v),
                    onTapStretch: () => unawaited(_editNumber(i, isStretch: true)),
                    onTapFloor: () => unawaited(_editNumber(i, isStretch: false)),
                  ),
                ],
                const SizedBox(height: 14),
                FilledButton.icon(
                  key: const ValueKey('nod-card-confirm'),
                  onPressed: _submitting ? null : () => unawaited(_confirm()),
                  icon: const Icon(AppIcons.verified, size: 18),
                  label: Text(_submitting ? '锁定中…' : '点头锁定今日菜单'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _NodNumberDialog extends StatefulWidget {
  const _NodNumberDialog({
    required this.title,
    required this.label,
    required this.helper,
    required this.initial,
  });

  final String title;
  final String label;
  final String helper;
  final int initial;

  @override
  State<_NodNumberDialog> createState() => _NodNumberDialogState();
}

class _NodNumberDialogState extends State<_NodNumberDialog> {
  late final TextEditingController _ctrl = TextEditingController(text: '${widget.initial}');

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      key: const ValueKey('nod-number-dialog'),
      title: Text(widget.title),
      content: TextField(
        key: const ValueKey('nod-number-input'),
        controller: _ctrl,
        autofocus: true,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        decoration: InputDecoration(
          labelText: widget.label,
          helperText: widget.helper,
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('取消')),
        FilledButton(
          key: const ValueKey('nod-number-save'),
          onPressed: () => Navigator.of(context).pop(int.tryParse(_ctrl.text.trim())),
          child: const Text('确定'),
        ),
      ],
    );
  }
}

class _NodItemRow extends StatelessWidget {
  const _NodItemRow({
    required this.index,
    required this.item,
    required this.busy,
    required this.onAdjustStretch,
    required this.onAdjustFloor,
    required this.onTapStretch,
    required this.onTapFloor,
  });

  final int index;
  final Map<String, dynamic> item;
  final bool busy;
  final ValueChanged<int> onAdjustStretch;
  final ValueChanged<int> onAdjustFloor;
  final VoidCallback onTapStretch;
  final VoidCallback onTapFloor;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final kind = '${item['kind'] ?? 'quiz'}';
    final kindMeta = kSealKinds[kind];
    final kindLabel = kindMeta?.$1 ?? kind;
    final unit = kindMeta?.$2 ?? '';
    final stretch = ((item['stretch'] as num?)?.toInt() ?? 1).clamp(1, 99999);
    final floor = ((item['floor'] as num?)?.toInt() ?? 1).clamp(1, stretch);
    final due = (item['dueMinute'] as num?)?.toInt() ?? 0;
    final span = due > 0 ? sealSpan(item) : '今日';

    Widget stepper({
      required String label,
      required int value,
      required Key decKey,
      required Key valKey,
      required Key incKey,
      required bool canDec,
      required bool canInc,
      required VoidCallback onDec,
      required VoidCallback onTapVal,
      required VoidCallback onInc,
    }) {
      return Expanded(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
          decoration: BoxDecoration(
            color: theme.colorScheme.surfaceContainerLow,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            children: [
              Text(label, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
              const SizedBox(width: 2),
              IconButton(
                key: decKey,
                visualDensity: VisualDensity.compact,
                constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
                padding: EdgeInsets.zero,
                onPressed: busy || !canDec ? null : onDec,
                icon: const Icon(AppIcons.decrement, size: 14),
              ),
              Expanded(
                child: InkWell(
                  key: valKey,
                  borderRadius: BorderRadius.circular(6),
                  onTap: busy ? null : onTapVal,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 2),
                    child: Center(
                      child: FittedBox(
                        fit: BoxFit.scaleDown,
                        child: Text(
                          '$value$unit',
                          style: AppType.tabular(theme.textTheme.labelLarge!.copyWith(fontWeight: FontWeight.w700)),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              IconButton(
                key: incKey,
                visualDensity: VisualDensity.compact,
                constraints: const BoxConstraints(minWidth: 24, minHeight: 24),
                padding: EdgeInsets.zero,
                onPressed: busy || !canInc ? null : onInc,
                icon: const Icon(AppIcons.increment, size: 14),
              ),
            ],
          ),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(kindMeta?.$3 ?? AppIcons.verified, size: 16, color: theme.colorScheme.primary),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                '${item['title'] ?? '验收点'}',
                style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
              ),
            ),
            Text(
              '$kindLabel · $span',
              style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            stepper(
              label: '拉伸',
              value: stretch,
              decKey: ValueKey('nod-stretch-dec-$index'),
              valKey: ValueKey('nod-stretch-val-$index'),
              incKey: ValueKey('nod-stretch-inc-$index'),
              canDec: stretch > 1,
              canInc: true,
              onDec: () => onAdjustStretch(stretch - 1),
              onTapVal: onTapStretch,
              onInc: () => onAdjustStretch(stretch + 1),
            ),
            const SizedBox(width: 8),
            stepper(
              label: '保底',
              value: floor,
              decKey: ValueKey('nod-floor-dec-$index'),
              valKey: ValueKey('nod-floor-val-$index'),
              incKey: ValueKey('nod-floor-inc-$index'),
              canDec: floor > 1,
              canInc: floor < stretch,
              onDec: () => onAdjustFloor(floor - 1),
              onTapVal: onTapFloor,
              onInc: () => onAdjustFloor(floor + 1),
            ),
          ],
        ),
      ],
    );
  }
}

/// 4.10 四部门速记收件箱（信息收集）+ 打包交 Gemini（谋划思考）。
class InboxCaptureCard extends StatefulWidget {
  const InboxCaptureCard({super.key, required this.channel, this.refreshTick = 0});

  final ProactiveChannel channel;
  final int refreshTick;

  @override
  State<InboxCaptureCard> createState() => _InboxCaptureCardState();
}

class _InboxCaptureCardState extends State<InboxCaptureCard> {
  static const _tags = ['随记', '卡壳', '灵感'];

  final _ctrl = TextEditingController();
  String _tag = '随记';
  bool _saving = false;
  bool _bundling = false;
  bool _expanded = false;
  List<Map<String, dynamic>> _entries = const [];

  @override
  void initState() {
    super.initState();
    unawaited(_loadEntries());
  }

  @override
  void didUpdateWidget(covariant InboxCaptureCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.refreshTick != widget.refreshTick) {
      unawaited(_loadEntries());
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  List<Map<String, dynamic>> _parseEntries(Object? raw) {
    if (raw is! List) return const [];
    final out = <Map<String, dynamic>>[];
    for (final item in raw) {
      if (item is Map) {
        out.add(Map<String, dynamic>.from(item));
      } else if (item is String && item.trim().isNotEmpty) {
        out.add({'tag': '随记', 'text': item.trim()});
      }
    }
    return out;
  }

  Future<void> _loadEntries() async {
    final res = await widget.channel.sealsOp(const {'action': 'inbox_list', 'limit': 10});
    if (!mounted || res == null || res['ok'] != true) return;
    setState(() {
      _entries = _parseEntries(res['entries'] ?? res['items']);
    });
  }

  Future<void> _submit() async {
    final text = _ctrl.text.trim();
    if (text.isEmpty) {
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(const SnackBar(content: Text('先写一句想法或卡壳点再提交')));
      return;
    }
    if (_saving) return;
    setState(() => _saving = true);
    final res = await widget.channel.sealsOp({'action': 'inbox_add', 'text': text, 'tag': _tag});
    if (!mounted) return;
    final ok = res?['ok'] == true;
    if (ok) {
      _ctrl.clear();
      final inlineEntries = _parseEntries(res?['entries'] ?? res?['items']);
      if (inlineEntries.isNotEmpty) {
        setState(() => _entries = inlineEntries);
      }
      unawaited(_loadEntries());
    }
    setState(() => _saving = false);
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(
        content: Text(ok ? '已记入收件箱（$_tag）' : '${res?['error'] ?? res?['text'] ?? '记入失败，请检查引擎连接'}'),
      ),
    );
  }

  Future<void> _bundleToGemini() async {
    if (_bundling) return;
    setState(() => _bundling = true);
    final res = await widget.channel.sealsOp(const {'action': 'inbox_bundle'});
    if (!mounted) return;
    setState(() => _bundling = false);
    if (res == null || res['ok'] != true) {
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(content: Text('${res?['error'] ?? res?['text'] ?? '打包失败，请检查引擎连接'}')),
      );
      return;
    }
    final markdown = '${res['markdown'] ?? ''}';
    final inboxCount = (res['inboxCount'] as num?)?.toInt() ?? 0;
    final stuckCount = (res['stuckCount'] as num?)?.toInt() ?? 0;
    if (markdown.isNotEmpty) {
      await Clipboard.setData(ClipboardData(text: markdown));
    }
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) {
        final theme = Theme.of(ctx);
        final colors = DshColors.of(ctx);
        return AlertDialog(
          key: const ValueKey('inbox-bundle-dialog'),
          title: Text('谋划包已复制（$inboxCount 条速记 · $stuckCount 条卡壳）'),
          content: SizedBox(
            width: 400,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    '已自动复制到剪贴板，可直接粘贴给 Gemini 谋划思考；若已连上电脑端 Gemini 桥，也可直接让 Gemini 调 dafeiyu_read_inbox 读取。',
                    style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                  ),
                  const SizedBox(height: 10),
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: colors.codeWellBg,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(color: colors.codeWellBorder),
                    ),
                    child: SelectableText(
                      markdown.isEmpty ? '（暂无收件箱内容）' : markdown,
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.codeWellFg),
                    ),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              key: const ValueKey('inbox-bundle-close'),
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('关闭'),
            ),
            OutlinedButton.icon(
              key: const ValueKey('inbox-bundle-copy'),
              onPressed: markdown.isEmpty
                  ? null
                  : () async {
                      await Clipboard.setData(ClipboardData(text: markdown));
                      if (ctx.mounted) {
                        ScaffoldMessenger.maybeOf(ctx)?.showSnackBar(const SnackBar(content: Text('已重新复制到剪贴板')));
                      }
                    },
              icon: const Icon(AppIcons.copy, size: 16),
              label: const Text('复制'),
            ),
            FilledButton.icon(
              key: const ValueKey('inbox-bundle-open-gemini'),
              onPressed: () async {
                Navigator.of(ctx).pop();
                final ok = await widget.channel.launchGeminiProactive(
                  topic: '谋划思考：整理收件箱与卡壳点',
                  brief: markdown,
                  mode: 'countdown_3s',
                  preferVoice: false,
                );
                if (!mounted) return;
                ScaffoldMessenger.maybeOf(context)?.showSnackBar(
                  SnackBar(
                    content: Text(ok ? '已唤起 Gemini，谋划包已在剪贴板中' : '未拉起 Gemini（内容已复制，请手动切过去粘贴）'),
                  ),
                );
              },
              icon: const Icon(AppIcons.askAi, size: 16),
              label: const Text('打开 Gemini'),
            ),
          ],
        );
      },
    );
  }

  Color _tagColor(String tag, DshColors colors, ColorScheme scheme) => switch (tag) {
        '卡壳' => colors.warning,
        '灵感' => colors.info,
        _ => scheme.primary,
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final scheme = theme.colorScheme;
    final shown = _expanded ? _entries : _entries.take(5).toList();

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: GlassSurface(
        key: const ValueKey('inbox-capture-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Material(
          type: MaterialType.transparency,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const OverviewGlyph(AppIcons.write, tint: 1, size: 40),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('速记收件箱 · 四部门', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 2),
                        Text(
                          '10 秒记下信息或卡壳点，随时打包交 Gemini 谋划思考',
                          style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 6,
                children: [
                  for (final tag in _tags)
                    ChoiceChip(
                      key: ValueKey('inbox-tag-$tag'),
                      label: Text(tag),
                      selected: _tag == tag,
                      onSelected: _saving ? null : (_) => setState(() => _tag = tag),
                    ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: TextField(
                      key: const ValueKey('inbox-input'),
                      controller: _ctrl,
                      minLines: 1,
                      maxLines: 3,
                      textInputAction: TextInputAction.send,
                      onSubmitted: (_) => unawaited(_submit()),
                      decoration: InputDecoration(
                        hintText: '记一条「$_tag」…',
                        isDense: true,
                        border: const OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  FilledButton.icon(
                    key: const ValueKey('inbox-submit'),
                    onPressed: _saving ? null : () => unawaited(_submit()),
                    icon: const Icon(AppIcons.send, size: 16),
                    label: Text(_saving ? '记入…' : '记入'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              if (_entries.isEmpty)
                Text(
                  '近期暂无速记；想到要点或卡壳时随手记一条',
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                )
              else ...[
                for (final e in shown) ...[
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                          decoration: BoxDecoration(
                            color: _tagColor('${e['tag'] ?? '随记'}', colors, scheme).withValues(alpha: 0.14),
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Text(
                            '${e['tag'] ?? '随记'}',
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: _tagColor('${e['tag'] ?? '随记'}', colors, scheme),
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            '${e['text'] ?? e['raw'] ?? ''}',
                            style: theme.textTheme.bodySmall,
                          ),
                        ),
                        if ('${e['time'] ?? ''}'.isNotEmpty) ...[
                          const SizedBox(width: 6),
                          Text(
                            '${e['time']}',
                            style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
                if (_entries.length > 5)
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                      key: const ValueKey('inbox-expand-btn'),
                      onPressed: () => setState(() => _expanded = !_expanded),
                      icon: Icon(_expanded ? AppIcons.collapse : AppIcons.expand, size: 16),
                      label: Text(_expanded ? '收起' : '展开全部（${_entries.length} 条）'),
                    ),
                  ),
              ],
              const SizedBox(height: 10),
              OutlinedButton.icon(
                key: const ValueKey('inbox-bundle-btn'),
                onPressed: _bundling ? null : () => unawaited(_bundleToGemini()),
                icon: const Icon(AppIcons.shareBundle, size: 18),
                label: Text(_bundling ? '正在打包…' : '打包交 Gemini（谋划思考）'),
              ),
            ],
          ),
          ),
        ),
      ),
    );
  }
}
