import 'dart:async';

import 'package:flutter/material.dart';

import '../../runtime/runtime_bridge.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';

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
                      '娱乐剩余',
                      ledger.funOut ? '用完' : '${ledger.funLeft}m',
                      '已用 ${ledger.funUsed}/${ledger.funQuota}m',
                      ledger.funOut ? colors.danger : scheme.primary,
                    ),
                    stat(
                      '挣来池',
                      '${ledger.earnedNet}m',
                      ledger.penalty > 0 ? '挣+${ledger.earned} · 扣-${ledger.penalty}' : '局+${ledger.earnedBout} · 题+${ledger.earnedBank}',
                      colors.success,
                    ),
                    stat(
                      '游戏剩余',
                      ledger.gameOut ? '用完' : '${ledger.gameLeft}m',
                      ledger.converted > 0 ? '已兑 ${ledger.converted * 2}m→娱乐' : '已用 ${ledger.gameUsed}/${ledger.gameQuota}m',
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
            subtitle: '无障碍已连 · 测试模式已关 · 被锁出不来时下拉通知栏点「紧急解除」',
            subtitleMaxLines: 2,
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
