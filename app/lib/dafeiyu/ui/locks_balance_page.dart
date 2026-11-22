import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'usage_analytics_page.dart';

/// 锁与余额总控页：
/// 1) 当前生效锁与验收锁强度（P0–P6 裁决 + 逾期严锁警示）；
/// 2) 手机可用娱乐余额大盘（结转 / 刷题 / 工作 + 三档分时花销规则 + 花余额 / 3m 急事应急通道）；
/// 3) 全系统 8 道锁全景清单（P0–P6）；
/// 4) 最近系统 & 双鱼设局轨迹（只读）。
class LocksBalancePage extends StatefulWidget {
  const LocksBalancePage({super.key, this.channel});

  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) => Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => LocksBalancePage(channel: channel)),
      );

  @override
  State<LocksBalancePage> createState() => _LocksBalancePageState();
}

class _LocksBalancePageState extends State<LocksBalancePage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();

  bool _loading = true;
  bool _busy = false;
  String? _banner;

  Map<String, dynamic> _today = const {};
  Map<String, dynamic>? _seals;
  Map<String, Object?>? _wall;
  bool? _lockShadow;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    if (mounted && _today.isEmpty && _seals == null) {
      setState(() => _loading = true);
    }
    final primary = <Future<void>>[];

    final sealsFuture = _channel.sealsOp(const {'action': 'locks_overview'}).then((r) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (r != null) _seals = _asMap(r);
      });
    }).catchError((_) {
      if (mounted && _loading) setState(() => _loading = false);
    });
    primary.add(sealsFuture);

    final todayFuture = _channel.todayStatus().then((r) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (r != null) _today = _asMap(r);
      });
    }).catchError((_) {
      if (mounted && _loading) setState(() => _loading = false);
    });
    primary.add(todayFuture);

    unawaited(
      _channel.wallStatus().then((r) {
        if (!mounted || r == null) return;
        setState(() => _wall = r);
      }).catchError((_) {}),
    );
    unawaited(
      _channel.lockShadow().then((r) {
        if (!mounted || r == null) return;
        setState(() => _lockShadow = r);
      }).catchError((_) {}),
    );

    await Future.any(primary);
  }

  static Map<String, dynamic> _asMap(Object? raw) {
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return {
        for (final e in raw.entries)
          if (e.key is String) e.key as String: e.value,
      };
    }
    return const {};
  }

  static List<Map<String, dynamic>> _asListOfMaps(Object? raw) {
    if (raw is! List) return const [];
    return [
      for (final item in raw)
        if (item is Map) _asMap(item),
    ];
  }

  static int _asInt(Object? raw, [int fallback = 0]) => raw is num ? raw.round() : fallback;

  static String _fallbackWindowNow() {
    final now = DateTime.now();
    final min = now.hour * 60 + now.minute;
    if (min >= 12 * 60 && min < 14 * 60) return 'lunch';
    if (min >= 18 * 60 && min < 23 * 60) return 'evening';
    if (min < 7 * 60 || min >= 23 * 60) return 'night';
    return 'daytime';
  }

  static String _windowLabelOf(String win) => switch (win) {
        'lunch' => '午休自由时段（12:00–14:00 不占白天槽）',
        'evening' => '晚间自由时段（18:00 起温和封顶 120m）',
        'night' => '深夜睡眠时段（23:00–07:00 锁定）',
        _ => '白天工作时段（零散上限 30m）',
      };

  Map<String, dynamic> _resolveOverview() {
    final fromSeals = _asMap(_seals?['locksOverview']);
    if (fromSeals.isNotEmpty) return fromSeals;
    return _asMap(_today['locksOverview']);
  }

  Map<String, dynamic> _resolveActiveLock(Map<String, dynamic> overview) {
    final ov = _asMap(overview['activeLock']);
    if (ov.isNotEmpty) return ov;

    final locking = _asMap(_seals?['locking']);
    if (locking.isNotEmpty) {
      return {
        'active': true,
        'priority': 'P3',
        'source': 'CONTRACT_OVERDUE_STRICT',
        'label': '验收点逾期严锁',
        'why': '「${locking['title'] ?? '今日验收点'}」已到点未过保底',
        'unlockHow': '交付验收或过保底（不可花零散余额绕过）',
        'verifyOverdue': true,
      };
    }

    final arbiter = _asMap(_today['lockArbiter']);
    if (arbiter.isNotEmpty) {
      final prio = '${arbiter['priority'] ?? 'P6'}';
      final src = '${arbiter['source'] ?? 'AUTONOMY_RELAXED'}';
      return {
        ...arbiter,
        'verifyOverdue': arbiter['verifyOverdue'] == true ||
            prio == 'P3' ||
            src == 'CONTRACT_OVERDUE_STRICT',
      };
    }

    final wallLocked = _wall?['locked'] == true;
    return {
      'active': wallLocked,
      'priority': wallLocked ? 'P4' : 'P6',
      'source': wallLocked ? 'BOUT_STRICT' : 'AUTONOMY_RELAXED',
      'label': wallLocked ? '专心局 / 门禁护航中' : '自主自治 · 正常放行',
      'why': '${_wall?['why'] ?? (wallLocked ? '当前处于专注保护时段' : '暂无高优先级强锁生效')}',
      'unlockHow': wallLocked ? '完成本局验收 / 花挣来余额 / 锁屏刷题临放' : '保持节奏即可',
      'verifyOverdue': false,
    };
  }

  Map<String, dynamic> _resolveWallet(Map<String, dynamic> overview, bool verifyOverdue) {
    final w = _asMap(overview['wallet']);
    if (w.isNotEmpty) return w;

    final ledger = TimeLedgerView.fromWire(_today['timeLedger']);
    final availableMin = ledger?.funLeft ?? 0;
    final eveningSpentMin = ledger?.funUsed ?? 0;
    final win = _fallbackWindowNow();
    final hasLedger = ledger != null;
    final canSpendNow = !verifyOverdue && (!hasLedger || availableMin >= 5);
    final spendBlockReason = verifyOverdue
        ? '当前存在逾期验收点，须先交付过保底，不可用零散余额绕过。'
        : (hasLedger && availableMin < 5 ? '当前可用娱乐余额不足 5 分钟，先刷题或完成一局挣取额度。' : '');

    return {
      'availableMin': availableMin,
      'carryInMin': ledger?.carry ?? 0,
      'earnedBankMin': ledger?.earnedBank ?? 0,
      'earnedBankCap': 60,
      'earnedWorkMin': ledger?.earnedBout ?? 0,
      'earnedTotalMin': ledger?.earned ?? 0,
      'penaltyMin': ledger?.penalty ?? 0,
      'daytimeSpentMin': 0,
      'daytimeCapMin': 30,
      'daytimeRemainingMin': 30,
      'eveningSpentMin': eveningSpentMin,
      'eveningCapMin': 120,
      'eveningRemainingMin': (120 - eveningSpentMin).clamp(0, 120),
      'currentWindow': win,
      'currentWindowLabel': _windowLabelOf(win),
      'floorPassed': false,
      'canSpendNow': canSpendNow,
      'spendBlockReason': spendBlockReason,
      'nextFreeWindowText': '12:00–14:00 午休或 18:00 后',
      'activeSpendPass': null,
      'lockShadow': _lockShadow ?? false,
      '_synthesized': true,
    };
  }

  List<Map<String, dynamic>> _resolveAllLocks(Map<String, dynamic> overview, Map<String, dynamic> activeLock) {
    final list = _asListOfMaps(overview['allLocks']);
    if (list.isNotEmpty) return list;

    final activePrio = '${activeLock['priority'] ?? ''}';
    final isActive = activeLock['active'] == true;
    final defaults = <Map<String, dynamic>>[
      {
        'id': 'EMERGENCY_SUSPEND',
        'priority': 'P0',
        'name': '急事 3 分钟应急通道',
        'summary': '最高优先级：短暂挂起严格锁（3–10 分钟），电话/短信/支付/导航永不锁',
        'unlockHow': '到时自动回位或主动撤销',
      },
      {
        'id': 'NIGHT_CURFEW',
        'priority': 'P1',
        'name': '深夜睡眠 & 晚间上限锁',
        'summary': '23:00–07:00 睡眠守护，或 18:00 后娱乐用满 120m 温和封顶',
        'unlockHow': '次日 07:00 自动解除',
      },
      {
        'id': 'GEMINI_LIVE_CELL',
        'priority': 'P2',
        'name': 'Gemini 连麦舱',
        'summary': '进入 Gemini 语音/深度共脑舱，仅放行共脑必需应用',
        'unlockHow': '完成复盘讨论或舱时结束',
      },
      {
        'id': 'CONTRACT_OVERDUE_STRICT',
        'priority': 'P3',
        'name': '契约 / 封印逾期严锁',
        'summary': '验收点或契约到点未过保底，禁止花零散余额绕过',
        'unlockHow': '交付证据过保底或写理由豁免',
      },
      {
        'id': 'TAKEOVER_STRICT',
        'priority': 'P3.5',
        'name': '强制接管回位锁',
        'summary': '偏航或连续告警触发的强接管，拉回主线',
        'unlockHow': '完成规定回位动作或刷题解锁',
      },
      {
        'id': 'BOUT_STRICT',
        'priority': 'P4',
        'name': '专心局 / 默认工作窗验收锁',
        'summary': '专注局进行中或处于工作时段默认验收，只放常用白名单 App',
        'unlockHow': '收工验收过关、花挣来余额或刷题临放',
      },
      {
        'id': 'CONTRACT_FLEX',
        'priority': 'P5',
        'name': '契约柔性护航 / 外挂租约',
        'summary': '契约进行中或合法放风窗口期内柔性观察',
        'unlockHow': '到期自动结算或通过检查点',
      },
      {
        'id': 'AUTONOMY_RELAXED',
        'priority': 'P6',
        'name': '余额门槛 & 行为雷达 / 双鱼临时封禁',
        'summary': '娱乐余额归零、行为雷达预警或鱼丸/鲸澜临时限制特定娱乐 App',
        'unlockHow': '刷题/过局挣取余额或等待双鱼解封',
      },
    ];
    return [
      for (final d in defaults)
        {
          ...d,
          'active': isActive && d['priority'] == activePrio,
        },
    ];
  }

  StatusTone _toneForPriority(String priority, bool active) {
    if (!active) return StatusTone.idle;
    return switch (priority) {
      'P0' => StatusTone.busy,
      'P1' || 'P5' => StatusTone.ok,
      'P2' || 'P3' || 'P3.5' || 'P4' => StatusTone.error,
      _ => StatusTone.warn,
    };
  }

  Future<void> _handleSpendTap(Map<String, dynamic> wallet, bool verifyOverdue) async {
    if (_busy) return;
    final synthesized = wallet['_synthesized'] == true;
    final canSpendNow = wallet['canSpendNow'] != false;
    final blockReason = '${wallet['spendBlockReason'] ?? ''}'.trim();
    if (verifyOverdue || (!synthesized && !canSpendNow)) {
      final msg = blockReason.isNotEmpty
          ? blockReason
          : '逾期严锁：当前不可花零散余额绕过，须先交付验收或保底。';
      setState(() => _banner = msg);
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
      return;
    }

    final out = await showDialog<(int, String)>(
      context: context,
      builder: (_) => const _SpendBalanceDialog(),
    );
    if (out == null || !mounted) return;
    setState(() => _busy = true);
    final res = await _channel.sealsOp({
      'action': 'spend',
      'minutes': out.$1,
      'reason': out.$2,
    });
    await _refresh();
    if (!mounted) return;
    final ok = res?['ok'] == true;
    final msg = '${res?['text'] ?? (res == null ? '暂未连上伴侣引擎，无法花费余额' : (ok ? '已扣减 ${out.$1} 分钟娱乐余额' : '未通过：${res['error'] ?? '不符合花费条件'}'))}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _handleEmergencyPass() async {
    if (_busy) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const ValueKey('locks-emergency-dialog'),
        title: const Text('启用 3 分钟急事应急通道？'),
        content: const Text(
          '仅用于接电话、取件、突发回复等真实急事：将记录 1 次每日应急解除额度，并暂时挂起严格锁 3 分钟，3 分钟后自动恢复守护。',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('算了')),
          FilledButton(
            key: const ValueKey('locks-emergency-confirm'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('确认开启 3 分钟'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _busy = true);
    final ok = await _channel.emergencySuspendStrict(minutes: 3);
    await _refresh();
    if (!mounted) return;
    final msg = ok ? '⚡ 已启用 3 分钟急事应急通道，请处理完后尽快回位' : '未能启用应急通道（请检查引擎是否在线）';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final scheme = theme.colorScheme;

    final overview = _resolveOverview();
    final activeLock = _resolveActiveLock(overview);
    final verifyOverdue = activeLock['verifyOverdue'] == true ||
        '${activeLock['priority'] ?? ''}' == 'P3' ||
        '${activeLock['source'] ?? ''}' == 'CONTRACT_OVERDUE_STRICT';
    final wallet = _resolveWallet(overview, verifyOverdue);
    final allLocks = _resolveAllLocks(overview, activeLock);
    final recentActions = _asListOfMaps(overview['recentActions']);

    final active = activeLock['active'] == true;
    final priority = '${activeLock['priority'] ?? 'P6'}';
    final source = '${activeLock['source'] ?? 'AUTONOMY_RELAXED'}';
    final label = '${activeLock['label'] ?? (active ? '严格锁护航中' : '自主自治')}';
    final why = '${activeLock['why'] ?? '由统一锁裁决器实时计算当前优先级'}';
    final unlockHow = '${activeLock['unlockHow'] ?? '完成当前验收或等待放行窗口'}';

    final availableMin = _asInt(wallet['availableMin']);
    final carryInMin = _asInt(wallet['carryInMin']);
    final earnedBankMin = _asInt(wallet['earnedBankMin']);
    final earnedBankCap = _asInt(wallet['earnedBankCap'], 60).clamp(1, 600);
    final earnedWorkMin = _asInt(wallet['earnedWorkMin']);
    final penaltyMin = _asInt(wallet['penaltyMin']);
    final daytimeSpentMin = _asInt(wallet['daytimeSpentMin']);
    final daytimeCapMin = _asInt(wallet['daytimeCapMin'], 30).clamp(1, 300);
    final daytimeRemainingMin = _asInt(wallet['daytimeRemainingMin'], (daytimeCapMin - daytimeSpentMin).clamp(0, daytimeCapMin));
    final eveningSpentMin = _asInt(wallet['eveningSpentMin']);
    final eveningCapMin = _asInt(wallet['eveningCapMin'], 120).clamp(1, 600);
    final eveningRemainingMin = _asInt(wallet['eveningRemainingMin'], (eveningCapMin - eveningSpentMin).clamp(0, eveningCapMin));
    final currentWindow = '${wallet['currentWindow'] ?? 'daytime'}';
    final currentWindowLabel = '${wallet['currentWindowLabel'] ?? _windowLabelOf(currentWindow)}';
    final floorPassed = wallet['floorPassed'] == true;
    final nextFreeWindowText = '${wallet['nextFreeWindowText'] ?? '12:00–14:00 午休或 18:00 后'}';
    final lockShadow = wallet['lockShadow'] == true || _lockShadow == true;

    final activeTone = _toneForPriority(priority, active);

    return AppPage.list(
      title: '锁与余额总控',
      subtitle: const Text('8 道锁统一裁决 · 分时余额大盘 · 双鱼设局轨迹'),
      actions: [
        GlassIconButton(
          icon: AppIcons.chart,
          tooltip: 'App 使用时长统计（独立页）',
          onPressed: () => unawaited(UsageAnalyticsPage.open(context, channel: _channel)),
        ),
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新锁与余额',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        if (_banner != null) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: GlassSurface(
              tier: GlassTier.pill,
              borderRadius: AppShape.radiusMd,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                child: Row(
                  children: [
                    const AnimatedStatusDot(tone: StatusTone.ok, size: 7),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(_banner!, style: theme.textTheme.bodySmall?.copyWith(color: colors.info)),
                    ),
                    GestureDetector(
                      onTap: () => setState(() => _banner = null),
                      child: Icon(AppIcons.close, size: 16, color: colors.textTertiary),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],

        if (_loading && _today.isEmpty && _seals == null)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 48),
            child: Center(child: CircularProgressIndicator()),
          )
        else ...[
          // 板块 1 · 当前生效锁与验收锁强度
          OverviewCard(
            key: const ValueKey('locks-active-card'),
            leading: OverviewGlyph(active ? AppIcons.lockActive : AppIcons.unlock, tint: active ? 3 : 6),
            title: Row(
              children: [
                Expanded(child: Text('$priority · $label', maxLines: 1, overflow: TextOverflow.ellipsis)),
                const SizedBox(width: 8),
                StatusChip(tone: activeTone, label: source),
              ],
            ),
            subtitle: Text(why, maxLines: 2, overflow: TextOverflow.ellipsis),
            footer: OverviewFooter(
              tone: activeTone,
              text: '解锁路径：$unlockHow',
            ),
          ),

          if (verifyOverdue)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Container(
                key: const ValueKey('locks-overdue-warning'),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: colors.dangerContainer,
                  borderRadius: AppShape.radiusLg,
                  border: Border.all(color: colors.danger.withValues(alpha: 0.35)),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(AppIcons.warning, size: 20, color: colors.danger),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '逾期严锁：不可花零散余额绕过，须先交付',
                            style: theme.textTheme.titleSmall?.copyWith(
                              color: colors.onDangerContainer,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '到点未过保底已提高验收锁强度。请先提交验收过保底；确有突发急事请走下方 3 分钟应急通道。',
                            style: theme.textTheme.bodySmall?.copyWith(color: colors.onDangerContainer),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),

          if (lockShadow)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: GlassSurface(
                tier: GlassTier.pill,
                borderRadius: AppShape.radiusMd,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  child: Row(
                    children: [
                      Icon(AppIcons.info, size: 16, color: colors.info),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '当前处于测试模式（影子锁开启）：只提示拦截原因，不落真锁',
                          style: theme.textTheme.bodySmall?.copyWith(color: colors.info),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),

          // 板块 2 · 手机可用娱乐余额大盘
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            child: GlassSurface(
              key: const ValueKey('locks-wallet-card'),
              tier: GlassTier.card,
              borderRadius: AppShape.radiusLg,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        const OverviewGlyph(AppIcons.balance, tint: 4, size: 42),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('手机可用娱乐余额', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                              const SizedBox(height: 2),
                              Text(
                                '$availableMin 分钟',
                                key: const ValueKey('locks-available-min'),
                                style: AppType.tabular(
                                  theme.textTheme.headlineMedium!.copyWith(
                                    fontWeight: FontWeight.w700,
                                    color: availableMin > 0 ? scheme.primary : colors.danger,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        StatusChip(
                          tone: currentWindow == 'night'
                              ? StatusTone.error
                              : (currentWindow == 'daytime' ? StatusTone.warn : StatusTone.ok),
                          label: switch (currentWindow) {
                            'lunch' => '午休自由窗',
                            'evening' => '晚间自由窗',
                            'night' => '深夜锁',
                            _ => '白天零散窗',
                          },
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '$currentWindowLabel · 自由花销：$nextFreeWindowText',
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        _MiniBalanceMetric(label: '昨日结转', value: '${carryInMin}m', accent: colors.info),
                        const SizedBox(width: 8),
                        _MiniBalanceMetric(label: '今日刷题', value: '$earnedBankMin/${earnedBankCap}m', accent: colors.success),
                        const SizedBox(width: 8),
                        _MiniBalanceMetric(
                          label: penaltyMin > 0 ? '工作/扣罚' : '今日工作',
                          value: penaltyMin > 0 ? '+$earnedWorkMin/-${penaltyMin}m' : '${earnedWorkMin}m',
                          accent: penaltyMin > 0 ? colors.warning : scheme.primary,
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    Text('分时花销规则（防白天碎片刷完 + 晚间温和封顶）', style: theme.textTheme.labelLarge),
                    const SizedBox(height: 10),
                    _WindowRuleMeter(
                      title: '① 白天工作零散槽位',
                      badgeText: floorPassed ? '已过保底·不限' : '$daytimeSpentMin / ${daytimeCapMin}m',
                      progress: floorPassed ? 0.0 : (daytimeSpentMin / daytimeCapMin).clamp(0.0, 1.0),
                      accent: floorPassed ? colors.success : (daytimeRemainingMin <= 5 ? colors.warning : scheme.primary),
                      subtitle: floorPassed
                          ? '今日保底已过，白天零散 30m 限制已自动解除'
                          : '白天零散上限 30m（剩 ${daytimeRemainingMin}m），过了今日保底自动解除',
                    ),
                    const SizedBox(height: 10),
                    _WindowRuleMeter(
                      title: '② 午休自由时段（12:00–14:00）',
                      badgeText: currentWindow == 'lunch' ? '此刻自由' : '不占白天 30m',
                      progress: currentWindow == 'lunch' ? 1.0 : 0.0,
                      accent: colors.success,
                      subtitle: '同晚间同等自由花余额（不占白天工作 30m 槽位）',
                    ),
                    const SizedBox(height: 10),
                    _WindowRuleMeter(
                      title: '③ 晚间自由时段（18:00 起）',
                      badgeText: '$eveningSpentMin / ${eveningCapMin}m',
                      progress: (eveningSpentMin / eveningCapMin).clamp(0.0, 1.0),
                      accent: eveningRemainingMin <= 15 ? colors.warning : colors.info,
                      subtitle: '18:00 后温和封顶 ${eveningCapMin}m（还剩 ${eveningRemainingMin}m），用完防熬夜刷到凌晨',
                    ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton.icon(
                            key: const ValueKey('locks-spend-btn'),
                            style: FilledButton.styleFrom(shape: AppShape.card),
                            onPressed: _busy ? null : () => unawaited(_handleSpendTap(wallet, verifyOverdue)),
                            icon: const Icon(AppIcons.play, size: 17),
                            label: const Text('花余额放松'),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: OutlinedButton.icon(
                            key: const ValueKey('locks-emergency-btn'),
                            style: OutlinedButton.styleFrom(shape: AppShape.card),
                            onPressed: _busy ? null : () => unawaited(_handleEmergencyPass()),
                            icon: const Icon(AppIcons.unlock, size: 17),
                            label: const Text('急事 3 分钟通道'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),

          // 板块 3 · 全系统 8 道锁全景清单（P0–P6）
          ListPageCard(
            key: const ValueKey('locks-all-card'),
            title: '全系统 8 道锁全景清单（P0–P6）',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              for (final item in allLocks)
                () {
                  final isOn = item['active'] == true;
                  final prio = '${item['priority'] ?? 'P6'}';
                  return ListPageRow(
                    key: ValueKey('lock-item-$prio'),
                    icon: isOn ? AppIcons.lockActive : AppIcons.lock,
                    iconColor: isOn ? colors.danger : colors.textTertiary,
                    title: '$prio · ${item['name'] ?? item['id'] ?? ''}',
                    subtitle: '${item['summary'] ?? ''} · 解锁：${item['unlockHow'] ?? '按规则解除'}',
                    subtitleMaxLines: 3,
                    badge: StatusChip(
                      tone: isOn ? StatusTone.error : StatusTone.idle,
                      label: isOn ? '生效中' : '未触发',
                    ),
                  );
                }(),
            ],
          ),

          // 板块 4 · 最近系统 & 双鱼设局轨迹（只读）
          ListPageCard(
            key: const ValueKey('locks-recent-card'),
            title: '最近系统 & 双鱼设局轨迹（只读）',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: recentActions.isEmpty
                ? const [
                    ListPageRow(
                      icon: AppIcons.history,
                      title: '今日暂无设局、封禁或花余额轨迹',
                      subtitle: '鱼丸 / 鲸澜 / 行为雷达限制特定 App 或开启专心局后，会透明记录于此',
                      subtitleMaxLines: 2,
                    ),
                  ]
                : [
                    for (final act in recentActions.take(20))
                      ListPageRow(
                        icon: AppIcons.history,
                        iconColor: scheme.primary,
                        title: '${act['actor'] ?? '系统'} · ${act['action'] ?? '动作'}${'${act['target'] ?? ''}'.isNotEmpty ? '（${act['target']}）' : ''}',
                        subtitle: '${act['reason'] ?? ''}',
                        subtitleMaxLines: 2,
                        meta: '${act['hm'] ?? ''}',
                      ),
                  ],
          ),

          ListPageCard(
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            children: [
              ListPageRow(
                key: const ValueKey('locks-open-analytics-row'),
                icon: AppIcons.chart,
                iconColor: colors.info,
                title: 'App 使用时长统计（独立页）',
                subtitle: '查看 24h 时间切片、App 用时排行、90 天趋势与晚间总报',
                subtitleMaxLines: 2,
                chevron: true,
                onTap: () => unawaited(UsageAnalyticsPage.open(context, channel: _channel)),
              ),
            ],
          ),
        ],
      ],
    );
  }
}

class _MiniBalanceMetric extends StatelessWidget {
  const _MiniBalanceMetric({
    required this.label,
    required this.value,
    required this.accent,
  });

  final String label;
  final String value;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
            const SizedBox(height: 2),
            Text(
              value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppType.tabular(theme.textTheme.titleSmall!.copyWith(fontWeight: FontWeight.w700, color: accent)),
            ),
          ],
        ),
      ),
    );
  }
}

class _WindowRuleMeter extends StatelessWidget {
  const _WindowRuleMeter({
    required this.title,
    required this.badgeText,
    required this.progress,
    required this.accent,
    required this.subtitle,
  });

  final String title;
  final String badgeText;
  final double progress;
  final Color accent;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(title, style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w600)),
              ),
              Text(
                badgeText,
                style: AppType.tabular(theme.textTheme.labelMedium!.copyWith(color: accent, fontWeight: FontWeight.w700)),
              ),
            ],
          ),
          const SizedBox(height: 6),
          ClipRRect(
            borderRadius: BorderRadius.circular(999),
            child: LinearProgressIndicator(
              value: progress.clamp(0.0, 1.0),
              minHeight: 6,
              backgroundColor: theme.colorScheme.outlineVariant.withValues(alpha: 0.25),
              valueColor: AlwaysStoppedAnimation<Color>(accent),
            ),
          ),
          const SizedBox(height: 5),
          Text(subtitle, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
        ],
      ),
    );
  }
}

class _SpendBalanceDialog extends StatefulWidget {
  const _SpendBalanceDialog();

  @override
  State<_SpendBalanceDialog> createState() => _SpendBalanceDialogState();
}

class _SpendBalanceDialogState extends State<_SpendBalanceDialog> {
  final _minCtrl = TextEditingController(text: '15');
  final _whyCtrl = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _minCtrl.dispose();
    _whyCtrl.dispose();
    super.dispose();
  }

  void _submit() {
    final mins = int.tryParse(_minCtrl.text.trim()) ?? 0;
    final why = _whyCtrl.text.trim();
    if (mins < 5 || mins > 60) {
      setState(() => _error = '每次花余额须在 5–60 分钟之间');
      return;
    }
    if (why.length < 4) {
      setState(() => _error = '请写一句理由（至少 4 个字）');
      return;
    }
    Navigator.of(context).pop((mins, why));
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    return AlertDialog(
      key: const ValueKey('locks-spend-dialog'),
      title: const Text('花娱乐余额放松'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            key: const ValueKey('locks-spend-minutes'),
            controller: _minCtrl,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            decoration: const InputDecoration(labelText: '时长（5–60 分钟）'),
          ),
          const SizedBox(height: 10),
          TextField(
            key: const ValueKey('locks-spend-reason'),
            controller: _whyCtrl,
            autofocus: true,
            maxLength: 120,
            decoration: const InputDecoration(
              labelText: '理由（花在什么上、为什么现在）',
              hintText: '例如：刚做完一套行测，刷会 B 站歇 15 分钟',
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 6),
            Text(_error!, style: TextStyle(color: colors.danger, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('算了')),
        FilledButton(
          key: const ValueKey('locks-spend-submit'),
          onPressed: _submit,
          child: const Text('确认花费'),
        ),
      ],
    );
  }
}
