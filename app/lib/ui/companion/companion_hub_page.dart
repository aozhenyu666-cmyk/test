// 大肥鱼认知自治中枢：把散落在设置深处的计划、四部门、姐妹、墙、护栏与桌宠收拢到一个
// 遵循 DSH Go 雾蓝玻璃语言（OverviewCard + ListPageCard + StatusChip）的总控面板。

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../plugins/native/native_model.dart' show NativePanel;
import '../../plugins/native/native_state.dart' show nativeContributionsProvider, rereadNativeContributions;
import '../../proactive/proactive_state.dart';
import '../../state/sessions_state.dart' show currentSessionProvider;
import '../common/list_page_row.dart';
import '../common/overview_card.dart';
import '../common/status_chip.dart';
import '../pets/pet_settings_page.dart';
import '../phone/deposit_page.dart';
import '../phone/intercept_page.dart';
import '../phone/plan_page.dart';
import '../phone/proactive_page.dart';
import '../phone/sisters_page.dart';
import '../phone/wall_page.dart';
import '../plugins/native/native_panel.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'bout_voice_page.dart';

class CompanionHubPage extends ConsumerStatefulWidget {
  const CompanionHubPage({super.key, this.channel});

  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) => Navigator.of(context).push(
    MaterialPageRoute<void>(builder: (_) => CompanionHubPage(channel: channel)),
  );

  @override
  ConsumerState<CompanionHubPage> createState() => _CompanionHubPageState();
}

class _CompanionHubPageState extends ConsumerState<CompanionHubPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();

  bool _loading = true;
  bool _actionBusy = false;
  Map<String, Object?>? _wallStatus;
  ProactiveStatus? _proactive;
  DepositView? _deposit;
  SistersView? _sisters;
  Map<String, Object?>? _planDay;
  GuardianPairingStatus? _guardian;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    if (mounted && _wallStatus == null && _proactive == null) {
      setState(() => _loading = true);
    }
    final results = await Future.wait<Object?>([
      _channel.wallStatus().catchError((_) => null),
      _channel.status().catchError((_) => null),
      _channel.deposit().catchError((_) => null),
      _channel.sisters().catchError((_) => null),
      _channel.planDay().catchError((_) => null),
      _channel.guardianStatus().catchError((_) => null),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      _wallStatus = results[0] is Map<String, Object?> ? results[0] as Map<String, Object?> : null;
      _proactive = results[1] is ProactiveStatus ? results[1] as ProactiveStatus : null;
      _deposit = results[2] is DepositView ? results[2] as DepositView : null;
      _sisters = results[3] is SistersView ? results[3] as SistersView : null;
      _planDay = results[4] is Map<String, Object?> ? results[4] as Map<String, Object?> : null;
      _guardian = results[5] is GuardianPairingStatus ? results[5] as GuardianPairingStatus : null;
    });
  }

  Future<void> _openGuardianPairDialog() async {
    var status = _guardian ?? await _channel.guardianStatus();
    if (!mounted) return;
    if (status == null) {
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        const SnackBar(content: Text('当前平台暂不支持大肥鱼哨兵互信配对')),
      );
      return;
    }
    setState(() => _guardian = status);
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) {
          final current = status!;
          final hasCode = current.pairCode.isNotEmpty;
          return AlertDialog(
            title: const Text('大肥鱼哨兵互信配对'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  current.sameCert
                      ? '检测到同证书安装：哨兵已免码自动建立互信绑定。'
                      : current.paired
                          ? '跨证书互信已绑定成功，哨兵可实时读取引擎健康状态。'
                          : '若哨兵与大肥鱼使用不同证书签名（如本地/CI 混装），请在下方生成 6 位配对码并填入「大肥鱼哨兵」App。',
                ),
                const SizedBox(height: 12),
                Text(
                  '安装状态：${current.installed ? "已安装哨兵" : "未检测到哨兵 App"} · 互信：${current.paired ? "已绑定" : "待配对"}',
                  style: Theme.of(dialogContext).textTheme.bodySmall,
                ),
                if (hasCode) ...[
                  const SizedBox(height: 14),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    decoration: BoxDecoration(
                      color: Theme.of(dialogContext).colorScheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('6 位一次性配对码（10 分钟有效）', style: Theme.of(dialogContext).textTheme.labelSmall),
                              const SizedBox(height: 4),
                              SelectableText(
                                current.pairCode,
                                style: Theme.of(dialogContext).textTheme.headlineSmall?.copyWith(
                                      fontWeight: FontWeight.w700,
                                      letterSpacing: 4,
                                    ),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          icon: const Icon(AppIcons.copy, size: 20),
                          tooltip: '复制配对码',
                          onPressed: () {
                            unawaited(Clipboard.setData(ClipboardData(text: current.pairCode)));
                            ScaffoldMessenger.maybeOf(context)?.showSnackBar(
                              const SnackBar(content: Text('已复制 6 位配对码')),
                            );
                          },
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
            actions: [
              TextButton(
                onPressed: () async {
                  final next = await _channel.generateGuardianPairCode();
                  if (next == null) return;
                  status = next;
                  if (mounted) setState(() => _guardian = next);
                  if (dialogContext.mounted) setDialogState(() {});
                },
                child: Text(hasCode ? '刷新配对码' : '生成 6 位配对码'),
              ),
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(),
                child: const Text('完成'),
              ),
            ],
          );
        },
      ),
    );
  }

  void _openPage(Widget page) {
    Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => page))
        .then((_) {
          if (mounted) unawaited(_refresh());
        });
  }

  Future<void> _openFocus() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    try {
      final ok = await _channel.openFocusControl();
      if (mounted && !ok) {
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(
          const SnackBar(content: Text('当前无法打开原生专注监督页面')),
        );
      }
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  Future<void> _triggerTick() async {
    if (_actionBusy) return;
    setState(() => _actionBusy = true);
    try {
      final ok = await _channel.tickNow();
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(content: Text(ok ? '已唤醒大肥鱼检查当前状态' : '当前无法触发检查，请确认引擎运行中')),
      );
      if (ok) unawaited(_refresh());
    } finally {
      if (mounted) setState(() => _actionBusy = false);
    }
  }

  (StatusTone, String, String, String) _overviewMeta() {
    final w = _wallStatus;
    final p = _proactive;
    if (_loading && w == null && p == null) {
      return (StatusTone.busy, '正在读取大肥鱼状态…', '同步计划、门禁与四部门进度', '正在连接伴侣引擎…');
    }
    if (w == null && p == null) {
      return (StatusTone.idle, '伴侣引擎暂未应答', '启动引擎后可查看今日计划、娱乐锁与四部门审计', '点击刷新重连伴侣内核');
    }
    final locked = w?['locked'] == true;
    final until = w?['openUntil'] is num ? (w!['openUntil'] as num).toInt() : 0;
    final why = w?['why'] is String ? (w!['why'] as String).trim() : '';
    final planItems = (_planDay?['plan'] is Map ? (_planDay!['plan'] as Map)['items'] : null);
    final boutCount = planItems is List ? planItems.length : 0;
    final doneDept = _deposit?.items.where((i) => i.done).length ?? 0;
    final deptText = _deposit == null ? '四部门未同步' : '四部门 $doneDept/4';
    final planText = boutCount > 0 ? '今日 $boutCount 局' : '今日尚未排局';
    final subtitle = '$planText · $deptText';
    final tone = locked
        ? StatusTone.warn
        : (p?.config.enabled ?? false)
        ? StatusTone.ok
        : StatusTone.idle;
    final title = locked ? '专注守护 · 娱乐锁开启中' : '专注守护 · 放风/待命';
    final footer = locked
        ? (until > 0 ? '放开时间 ${_hhmm(until)}${why.isEmpty ? "" : " · $why"}' : (why.isEmpty ? '娱乐应用处于门禁状态' : why))
        : (p != null
            ? (p.holdReason != null
                ? '内核暂缓主动发球：${p.holdReason}'
                : (p.config.enabled ? '主动内核运行中 · 每日上限 ${p.config.dailyMax} 次' : '门禁已放开 · 主动提醒未开'))
            : '门禁已放开');
    return (tone, title, subtitle, footer);
  }

  NativePanel? _boutsPanel() {
    final plugin = ref.watch(nativeContributionsProvider).plugin('dsh-go-companion');
    for (final panel in plugin?.panels ?? const <NativePanel>[]) {
      if (panel.id == 'bouts') return panel;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final bouts = _boutsPanel();
    final currentSession = ref.watch(currentSessionProvider);
    final (tone, title, subtitle, footer) = _overviewMeta();
    final depositDone = _deposit?.items.where((e) => e.done).length ?? 0;
    final depositTotal = _deposit?.items.length ?? 0;
    final appealLeft = _sisters?.audit.done;
    final planNeedsGate = _sisters?.planGate ?? false;
    final locked = _wallStatus?['locked'] == true;
    final planItems = (_planDay?['plan'] is Map ? (_planDay!['plan'] as Map)['items'] : null);
    final boutCount = planItems is List ? planItems.length : 0;

    return AppPage.list(
      title: '大肥鱼中枢',
      subtitle: const Text('今日计划、每日四部门、姐妹记忆与系统护栏的一站入口'),
      actions: [
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新状态',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        OverviewCard(
          leading: OverviewGlyph(AppIcons.bot, tint: locked ? 3 : 6),
          title: Row(
            children: [
              Expanded(child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis)),
              const SizedBox(width: 8),
              StatusChip(tone: tone, label: locked ? '锁定中' : '就绪'),
            ],
          ),
          subtitle: Text(subtitle, maxLines: 2, overflow: TextOverflow.ellipsis),
          footer: OverviewFooter(tone: tone, text: footer),
          onTap: () => unawaited(_refresh()),
          onFooterTap: () => _openPage(const WallPage()),
        ),
        ListPageCard(
          title: '快捷动作',
          children: [
            ListPageRow(
              icon: AppIcons.today,
              iconColor: planNeedsGate ? colors.warning : Theme.of(context).colorScheme.primary,
              title: '今日的局',
              subtitle: bouts != null
                  ? '直接进入实时「局」原生面板，开始本拍推局与验收'
                  : (boutCount > 0
                      ? '伴侣面板加载中 · 点击同步或进入今日 $boutCount 局计划'
                      : '先排定今日几局与必需/允许应用，再开「局」更顺畅'),
              subtitleMaxLines: 2,
              value: bouts != null ? '进局' : (boutCount > 0 ? '$boutCount 局' : '去排局'),
              onTap: () {
                if (bouts != null) {
                  _openPage(NativePanelPage(panel: bouts, sessionId: currentSession));
                } else {
                  rereadNativeContributions(ref);
                  _openPage(const PlanPage());
                }
              },
            ),
            ListPageRow(
              icon: AppIcons.voice,
              iconColor: Theme.of(context).colorScheme.primary,
              title: '局官语音交互',
              subtitle: '当前正在进行的局，由局官念题并语音作答',
              subtitleMaxLines: 2,
              onTap: () => _openPage(const BoutVoicePage()),
            ),
            ListPageRow(
              icon: AppIcons.agentLoop,
              title: _actionBusy ? '正在唤醒大肥鱼…' : '立即触发一次状态巡检',
              subtitle: '手动让主动内核与伴侣插件对齐当前屏幕与节奏',
              subtitleMaxLines: 2,
              onTap: _actionBusy ? null : () => unawaited(_triggerTick()),
            ),
          ],
        ),
        const SizedBox(height: 16),
        ListPageCard(
          title: '每日自治',
          children: [
            ListPageRow(
              icon: AppIcons.calendar,
              iconColor: planNeedsGate ? colors.warning : null,
              title: '今日计划',
              subtitle: planNeedsGate
                  ? '今日尚未排局 · 请先设置必需应用与允许应用'
                  : '一局一行：时间预算、工作包与晨间 10 分钟接单窗口',
              subtitleMaxLines: 2,
              badge: planNeedsGate ? const StatusChip(tone: StatusTone.warn, label: '待排局') : null,
              onTap: () => _openPage(const PlanPage()),
            ),
            ListPageRow(
              icon: AppIcons.jobs,
              iconColor: depositTotal > 0 && depositDone == depositTotal ? colors.success : null,
              title: '每日四部门',
              subtitle: '信息收集 · 谋划思考 · 行为管理 · 统筹规划（含白天碎片记事）',
              subtitleMaxLines: 2,
              value: depositTotal > 0 ? '$depositDone/$depositTotal 段' : null,
              onTap: () => _openPage(const DepositPage()),
            ),
            ListPageRow(
              icon: AppIcons.verified,
              iconColor: locked ? colors.warning : colors.success,
              title: '墙与申诉',
              subtitle: '控制塔冷酷审计记录、规则阈值与物理证据申诉',
              subtitleMaxLines: 2,
              value: locked ? '门禁中' : '查看',
              onTap: () => _openPage(const WallPage()),
            ),
            ListPageRow(
              icon: AppIcons.team,
              title: '姐妹与记忆',
              subtitle: '鱼丸与鲸澜双人格卡、记住的事实与基础操作通过率',
              subtitleMaxLines: 2,
              value: appealLeft != null ? '今日完成 $appealLeft' : null,
              onTap: () => _openPage(const SistersPage()),
            ),
          ],
        ),
        const SizedBox(height: 16),
        ListPageCard(
          title: '护栏与环境',
          children: [
            ListPageRow(
              icon: AppIcons.blocked,
              title: '娱乐锁机',
              subtitle: '严格/联动/训练锁机模式、黑名单清单、误放行白名单一键恢复',
              subtitleMaxLines: 2,
              onTap: () => _openPage(const InterceptPage()),
            ),
            ListPageRow(
              icon: AppIcons.time,
              title: '专注监督',
              subtitle: '原生 5 秒离开拉回与独立守护界面（引擎断开仍生效）',
              subtitleMaxLines: 2,
              onTap: () => unawaited(_openFocus()),
            ),
            ListPageRow(
              icon: AppIcons.notificationsOff,
              title: '主动搭话与诊断',
              subtitle: '主动提醒间隔、免打扰时段、PC 联动账本与日志导出',
              subtitleMaxLines: 2,
              value: _proactive == null ? null : (_proactive!.config.enabled ? '已开启' : '未开启'),
              onTap: () => _openPage(const ProactivePage()),
            ),
            ListPageRow(
              icon: AppIcons.permissions,
              iconColor: (_guardian?.paired ?? false) ? colors.success : null,
              title: '大肥鱼哨兵互信配对',
              subtitle: '查看同签名互信状态，或生成 6 位配对码绑定独立「大肥鱼哨兵」App',
              subtitleMaxLines: 2,
              value: _guardian == null
                  ? null
                  : (_guardian!.paired
                      ? (_guardian!.sameCert ? '免码互信' : '已配对')
                      : (_guardian!.installed ? '待配对' : '未安装')),
              onTap: () => unawaited(_openGuardianPairDialog()),
            ),
            ListPageRow(
              icon: AppIcons.pet,
              title: '桌面宠物',
              subtitle: '桌面悬浮形象、气泡提问与双姐妹互动样式',
              subtitleMaxLines: 2,
              onTap: () => _openPage(const PetSettingsPage()),
            ),
          ],
        ),
      ],
    );
  }

  static String _hhmm(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }
}
