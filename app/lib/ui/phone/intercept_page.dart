// 娱乐拦截设置页 (round 4 hardening): the page the person actually uses to run the feature.
//
// Everything here is a thin edit over the kernel: every toggle is a [InterceptChannel.patch] on one
// key, and every value shown comes back from `status`. There is no local copy of the config, so the
// page can never show a setting the kernel does not have.
//
// Three things it says out loud, because a silent absence would look like "the feature is off":
//  * when this build has no `dsh/intercept` channel at all ([InterceptUnavailable]);
//  * when the kernel could not write its own state ([InterceptStatus.storageError]);
//  * when the kernel found unreadable state at start-up ([InterceptStatus.recoveryIssue]).

import 'dart:async';

import 'package:flutter/material.dart';

import '../../intercept/intercept_channel.dart';
import '../../l10n/l10n.dart';
import '../../loop/loop_channel.dart';
import '../common/list_page_row.dart';
import '../common/overview_card.dart';
import '../common/segmented_pill.dart';
import '../common/status_chip.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'action_log_page.dart' show formatLogTime;
import 'hold_to_confirm.dart';

class InterceptPage extends StatefulWidget {
  const InterceptPage({super.key, this.channel, this.loopChannel});

  final InterceptChannel? channel;
  final LoopChannel? loopChannel;

  /// The daily-cap ladder, in blocks per day; 0 turns the cap off.
  static const dailyMaxSteps = [0, 2, 4, 6, 10];

  /// The cooldown ladder, in minutes; 0 allows back-to-back interceptions.
  static const cooldownSteps = [0, 2, 5, 15, 30];

  /// The pass length ladder, in minutes.
  static const releaseSteps = [1, 5, 10, 30, 60];

  /// The calm reflection length ladder, in seconds (15s minimum calm wait on screen).
  static const trainingSteps = [15, 30, 60, 120, 300];

  @override
  State<InterceptPage> createState() => _InterceptPageState();
}

class _InterceptPageState extends State<InterceptPage> {
  late final InterceptChannel _channel = widget.channel ?? InterceptChannel();
  late final LoopChannel _loop = widget.loopChannel ?? LoopChannel();
  InterceptStatus? _status;
  String? _error;
  bool? _available;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    _poll = Timer.periodic(const Duration(seconds: 4), (_) => unawaited(_refresh()));
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final ok = await _channel.available();
      if (!mounted) return;
      if (!ok) {
        setState(() {
          _available = false;
          _error = null;
        });
        return;
      }
      final s = await _channel.status();
      if (!mounted) return;
      setState(() {
        _available = true;
        _status = s;
        _error = null;
      });
    } on InterceptUnavailable {
      if (!mounted) return;
      setState(() => _available = false);
    } on InterceptFailed catch (e) {
      if (!mounted) return;
      setState(() => _error = e.cause);
    }
  }

  /// One key, one write, one refresh. Nothing is optimistically applied: the kernel is the truth.
  Future<void> _patch(Map<String, Object?> changes) async {
    try {
      final s = await _channel.patch(changes);
      if (!mounted) return;
      setState(() {
        _status = s;
        _error = null;
      });
    } on InterceptFailed catch (e) {
      if (!mounted) return;
      setState(() => _error = e.cause);
    }
  }

  Future<void> _setEnabled(bool on) async {
    final config = _status?.config ?? InterceptConfig.empty;
    if (config.enabled && !on) {
      await _confirmRelax(
        title: '确认关闭娱乐锁机',
        message: '关闭后清单内的娱乐 APP 将不再触发锁屏。',
        confirmLabel: '按住 1.5 秒关闭锁机',
        onConfirmed: () => _patch({'enabled': false}),
      );
      return;
    }
    await _patch({'enabled': on});
  }

  Future<void> _setLockMode(String nextMode) async {
    final current = _status?.config.lockMode ?? InterceptLockMode.training;
    if (current == nextMode) return;
    final relaxing = (current == InterceptLockMode.strict && nextMode != InterceptLockMode.strict) ||
        (current == InterceptLockMode.linked && nextMode == InterceptLockMode.training);
    if (relaxing) {
      await _confirmRelax(
        title: '放宽锁机模式',
        message: nextMode == InterceptLockMode.training
            ? '切换为「冷静训练」后，锁屏页面将允许在冷静倒计时与数字训练后临时放行。'
            : '切换为「专注联动」后，仅在专注监督运行期间禁用数字放行。',
        confirmLabel: '按住 1.5 秒切换模式',
        onConfirmed: () => _patch({'lockMode': nextMode}),
      );
      return;
    }
    await _patch({'lockMode': nextMode});
  }

  Future<void> _cycle(List<int> steps, int current, String key) {
    final i = steps.indexOf(current);
    return _patch({key: steps[(i + 1) % steps.length]});
  }

  Future<void> _confirmRelax({
    required String title,
    required String message,
    required String confirmLabel,
    required Future<void> Function() onConfirmed,
  }) async {
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(message),
            const SizedBox(height: 16),
            HoldToConfirmButton(
              label: confirmLabel,
              hint: '按住约 1.5 秒确认放宽，防止一时冲动误触',
              onConfirmed: () {
                Navigator.of(dialogContext).pop();
                unawaited(onConfirmed());
              },
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: Text(context.l10n.commonCancel),
          ),
        ],
      ),
    );
  }

  Future<void> _addTarget() async {
    final config = _status?.config ?? InterceptConfig.empty;
    final existing = {for (final t in config.targets) t.packageName};
    final added = await showDialog<List<InterceptTarget>>(
      context: context,
      builder: (_) => _AddTargetDialog(
        loop: _loop,
        existingPackages: existing,
      ),
    );
    if (added == null || added.isEmpty || !mounted) return;
    final freshConfig = _status?.config ?? InterceptConfig.empty;
    final seen = {for (final t in freshConfig.targets) t.packageName};
    final merged = [
      ...freshConfig.targets,
      for (final item in added)
        if (seen.add(item.packageName)) item,
    ];
    if (merged.length == freshConfig.targets.length) return;
    await _patch({'targets': merged.map((t) => t.toWire()).toList()});
  }

  Future<void> _removeTarget(String pkg) async {
    final config = _status?.config ?? InterceptConfig.empty;
    final next = config.targets.where((t) => t.packageName != pkg).toList();
    await _patch({'targets': next.map((t) => t.toWire()).toList()});
  }

  Future<void> _removeWhitelist(String pkg) async {
    try {
      final s = await _channel.removeWhitelist(pkg);
      if (mounted) setState(() { _status = s; _error = null; });
    } on InterceptFailed catch (e) {
      if (mounted) setState(() => _error = e.cause);
    }
  }

  Future<void> _clearWhitelistAndRelock() async {
    try {
      await _channel.clearWhitelist();
      final s = await _channel.restoreLockNow();
      if (mounted) setState(() { _status = s; _error = null; });
    } on InterceptFailed catch (e) {
      if (mounted) setState(() => _error = e.cause);
    }
  }

  Future<void> _restoreLockNow() async {
    try {
      final s = await _channel.restoreLockNow();
      if (mounted) setState(() { _status = s; _error = null; });
    } on InterceptFailed catch (e) {
      if (mounted) setState(() => _error = e.cause);
    }
  }

  Future<void> _pause() async {
    await _confirmRelax(
      title: '确认暂停娱乐锁机',
      message: '暂停 30 分钟内所有目标娱乐应用都不再拦截，请确认确有必要。',
      confirmLabel: '按住 1.5 秒暂停 30 分钟',
      onConfirmed: () async {
        try {
          await _channel.pause();
          await _refresh();
        } on InterceptFailed catch (e) {
          if (mounted) setState(() => _error = e.cause);
        }
      },
    );
  }

  Future<void> _resume() async {
    try {
      final s = await _channel.resume();
      if (mounted) setState(() => _status = s);
    } on InterceptFailed catch (e) {
      if (mounted) setState(() => _error = e.cause);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final colors = DshColors.of(context);

    if (_available == false) {
      return AppPage.list(
        title: l10n.interceptTitle,
        subtitle: Text(l10n.interceptSubtitle),
        children: [
          ListPageCard(children: [
            ListPageRow(
              icon: AppIcons.stopped,
              iconColor: colors.warning,
              title: l10n.interceptUnavailable,
              subtitle: l10n.interceptUnavailableHint,
              subtitleMaxLines: 4,
            ),
          ]),
        ],
      );
    }

    final status = _status;
    final config = status?.config ?? InterceptConfig.empty;
    final counts = status?.counts ?? const InterceptCounts();
    final paused = status?.paused ?? false;
    final effectiveMode = status?.effectiveLockMode ?? config.lockMode;
    final strictActive = config.enabled && !paused && effectiveMode == InterceptLockMode.strict;
    final activeReleases = status?.activeReleasesCount ?? 0;

    final overviewTone = !config.enabled
        ? StatusTone.idle
        : paused
        ? StatusTone.warn
        : strictActive
        ? StatusTone.ok
        : StatusTone.busy;
    final chipLabel = !config.enabled
        ? '未开启'
        : paused
        ? '暂停中'
        : strictActive
        ? (config.lockMode == InterceptLockMode.linked ? '联动严格' : '严格锁机')
        : '冷静训练';

    return AppPage.list(
      title: l10n.interceptTitle,
      subtitle: Text(l10n.interceptSubtitle),
      actions: [
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: l10n.interceptRefresh,
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        OverviewCard(
          leading: OverviewGlyph(AppIcons.blocked, tint: strictActive ? 3 : 6),
          title: Row(
            children: [
              Expanded(child: Text(config.enabled ? '娱乐锁机护栏运行中' : '娱乐锁机尚未开启', maxLines: 1, overflow: TextOverflow.ellipsis)),
              const SizedBox(width: 8),
              StatusChip(tone: overviewTone, label: chipLabel),
            ],
          ),
          subtitle: Text(
            '守护 ${config.targets.length} 个娱乐应用 · 累计触发 ${counts.triggerCount} 次 · 实拦 ${counts.blockedCount} 次',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          footer: OverviewFooter(
            tone: config.whitelist.isNotEmpty || paused || activeReleases > 0 ? StatusTone.warn : overviewTone,
            text: config.whitelist.isNotEmpty
                ? '提醒：白名单中有 ${config.whitelist.length} 个应用跳过锁机（可在下方一键恢复）'
                : (paused || activeReleases > 0
                    ? '当前存在暂停或临时放行窗口 · 可点击下方立即恢复锁定'
                    : '锁屏页已移除白名单/规则直通：严格模式禁用数字放行'),
          ),
          onTap: () => unawaited(_refresh()),
        ),
        const SizedBox(height: 12),
        if (_error != null) ...[
          ListPageCard(children: [
            ListPageRow(
              icon: AppIcons.stopped,
              iconColor: colors.danger,
              title: _error!,
              titleMaxLines: 4,
            ),
          ]),
          const SizedBox(height: 16),
        ],
        if (status?.storageError != null) ...[
          ListPageCard(children: [
            ListPageRow(
              icon: AppIcons.stopped,
              iconColor: colors.danger,
              title: l10n.interceptStorageError(status!.storageError!),
              titleMaxLines: 4,
            ),
          ]),
          const SizedBox(height: 16),
        ],
        if (status?.recoveryIssue != null) ...[
          ListPageCard(children: [
            ListPageRow(
              icon: AppIcons.stopped,
              iconColor: colors.warning,
              title: l10n.interceptRecoveryIssue(status!.recoveryIssue!),
              titleMaxLines: 4,
            ),
          ]),
          const SizedBox(height: 16),
        ],
        ListPageCard(children: [
          MergeSemantics(
            child: ListPageRow(
              icon: AppIcons.notificationsOff,
              title: l10n.interceptEnabled,
              subtitle: l10n.interceptEnabledHint,
              subtitleMaxLines: 3,
              onTap: status == null ? null : () => unawaited(_setEnabled(!config.enabled)),
              trailing: Switch(
                value: config.enabled,
                onChanged: status == null ? null : (v) => unawaited(_setEnabled(v)),
              ),
            ),
          ),
          if (status?.active != null)
            ListPageRow(
              icon: AppIcons.stopped,
              iconColor: colors.warning,
              title: l10n.interceptActive(status!.active!),
            ),
          if (paused || activeReleases > 0)
            ListPageRow(
              icon: AppIcons.verified,
              iconColor: colors.warning,
              title: '立即恢复全部锁定',
              subtitle: paused ? '提前结束当前暂停并清除全部临时放行窗口' : '立即收回 $activeReleases 个应用的临时放行额度',
              subtitleMaxLines: 2,
              badge: const StatusChip(tone: StatusTone.warn, label: '立即重锁'),
              onTap: status == null ? null : () => unawaited(_restoreLockNow()),
            ),
        ]),
        const SizedBox(height: 20),
        ListPageCard(
          title: '锁机模式',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: SegmentedPill<String>(
                segments: const [
                  PillSegment(InterceptLockMode.training, '冷静训练'),
                  PillSegment(InterceptLockMode.linked, '专注联动'),
                  PillSegment(InterceptLockMode.strict, '严格锁机'),
                ],
                selected: config.lockMode,
                onChanged: (mode) {
                  if (status != null) unawaited(_setLockMode(mode));
                },
              ),
            ),
            ListPageRow(
              icon: AppIcons.info,
              iconColor: strictActive ? colors.success : colors.info,
              title: switch (config.lockMode) {
                InterceptLockMode.strict => '严格锁机：禁用数字放行与白名单放行，仅可回任务 / 破窗 / 急事 3 分钟',
                InterceptLockMode.linked => (status?.focusActive ?? false)
                    ? '专注联动（当前严格生效中）：专注监督开启期间自动禁用数字放行'
                    : '专注联动：日常使用冷静训练；开启专注监督后自动切换为严格锁机',
                _ => '冷静训练：需先经历冷静倒计时，再按序完成数字训练方可临时放行',
              },
              titleMaxLines: 3,
            ),
          ],
        ),
        const SizedBox(height: 20),
        ListPageCard(
          title: '白名单与防失效自救',
          children: [
            if (config.whitelist.isEmpty)
              ListPageRow(
                icon: AppIcons.allowed,
                iconColor: colors.success,
                title: l10n.interceptWhitelistEmpty,
                subtitle: '当前无任何目标应用被永久跳过；锁屏页也已禁止直加白名单。',
                subtitleMaxLines: 2,
              )
            else ...[
              for (final pkg in config.whitelist)
                ListPageRow(
                  icon: AppIcons.stopped,
                  iconColor: colors.warning,
                  title: pkg,
                  subtitle: '当前在永久跳过名单中，点击右侧立即移出并恢复锁定',
                  subtitleMaxLines: 2,
                  trailing: TextButton(
                    onPressed: status == null ? null : () => unawaited(_removeWhitelist(pkg)),
                    child: const Text('移出'),
                  ),
                ),
              ListPageRow(
                icon: AppIcons.verified,
                iconColor: colors.danger,
                title: '清空全部白名单并立即恢复锁定',
                subtitle: '解决此前误放行导致「再也锁不上」的问题',
                subtitleMaxLines: 2,
                onTap: status == null ? null : () => unawaited(_clearWhitelistAndRelock()),
              ),
            ],
          ],
        ),
        const SizedBox(height: 20),
        ListPageCard(
          title: l10n.interceptTargets,
          children: [
            if (config.targets.isEmpty)
              ListPageRow(
                icon: AppIcons.pending,
                title: l10n.interceptNoTargets,
                subtitle: l10n.interceptTargetsHint,
                subtitleMaxLines: 3,
              )
            else
              for (final t in config.targets)
                ListPageRow(
                  icon: AppIcons.stopped,
                  title: t.label,
                  subtitle: t.packageName,
                  trailing: IconButton(
                    // AppIcons.remove, not Icons.*: widget code names icons only through the
                    // role table (test/icons/app_icons_test.dart enforces it).
                    icon: const Icon(AppIcons.remove, size: 20),
                    tooltip: l10n.interceptRemoveTarget,
                    onPressed: status == null ? null : () => unawaited(_removeTarget(t.packageName)),
                  ),
                ),
            ListPageRow(
              icon: AppIcons.queued,
              title: l10n.interceptAddTarget,
              subtitle: l10n.interceptTargetsHint,
              subtitleMaxLines: 3,
              onTap: status == null ? null : () => unawaited(_addTarget()),
            ),
          ],
        ),
        const SizedBox(height: 20),
        ListPageCard(
          title: l10n.interceptMode,
          children: [
            ListPageRow(
              icon: AppIcons.commandTimeout,
              title: l10n.interceptRelease,
              value: '${config.releaseMs ~/ 60000} 分钟',
              onTap: status == null
                  ? null
                  : () => unawaited(_cycle(
                        InterceptPage.releaseSteps,
                        config.releaseMs ~/ 60000,
                        'releaseMs',
                      )),
            ),
            ListPageRow(
              icon: AppIcons.commandTimeout,
              title: '冷静训练等待时长（秒）',
              value: '${config.trainingMs ~/ 1000} 秒',
              onTap: status == null
                  ? null
                  : () => unawaited(_cycleTraining(config)),
            ),
            ListPageRow(
              icon: AppIcons.commandTimeout,
              title: l10n.interceptCooldown,
              value: '${config.cooldownMs ~/ 60000} 分钟',
              onTap: status == null
                  ? null
                  : () => unawaited(_cycle(
                        InterceptPage.cooldownSteps,
                        config.cooldownMs ~/ 60000,
                        'cooldownMs',
                      )),
            ),
            ListPageRow(
              icon: AppIcons.today,
              title: l10n.interceptDailyMax,
              value: config.dailyMax <= 0 ? l10n.interceptDailyMaxOff : '${config.dailyMax} 次',
              onTap: status == null
                  ? null
                  : () => unawaited(_cycle(InterceptPage.dailyMaxSteps, config.dailyMax, 'dailyMax')),
            ),
          ],
        ),
        const SizedBox(height: 24),
        ListPageCard(
          title: l10n.interceptPause,
          children: [
            if (paused)
              ListPageRow(
                icon: AppIcons.allowed,
                iconColor: colors.success,
                title: l10n.interceptPausedUntil(formatLogTime(DateTime.fromMillisecondsSinceEpoch(config.pauseUntilWall))),
                subtitle: l10n.interceptPauseHint,
                subtitleMaxLines: 3,
                onTap: () => unawaited(_resume()),
                trailing: TextButton(
                  onPressed: () => unawaited(_resume()),
                  child: Text(l10n.interceptResume),
                ),
              )
            else
              ListPageRow(
                icon: AppIcons.stopped,
                title: l10n.interceptPauseNow,
                subtitle: l10n.interceptPauseHint,
                subtitleMaxLines: 3,
                onTap: status == null ? null : () => unawaited(_pause()),
              ),
          ],
        ),
        const SizedBox(height: 24),
        ListPageCard(
          title: l10n.interceptLedger,
          children: [
            _counter(l10n.interceptLedgerTriggers, counts.triggerCount),
            _counter(l10n.interceptLedgerBlocked, counts.blockedCount),
            _counter(l10n.interceptLedgerReleased, counts.userReleasedCount),
            _counter(l10n.interceptLedgerExited, counts.userExitedCount),
            _counter(l10n.interceptLedgerPaused, counts.pausedCount),
          ],
        ),
        const SizedBox(height: 24),
        ListPageCard(
          title: l10n.interceptReceipts,
          children: [
            if ((status?.receipts ?? const []).isEmpty)
              ListPageRow(icon: AppIcons.pending, title: l10n.interceptReceiptsEmpty)
            else
              for (final r in status!.receipts.take(20))
                ListPageRow(
                  icon: AppIcons.chat,
                  title: interceptOutcomeLabel(l10n, r.outcome),
                  subtitle: r.packageName,
                  value: formatLogTime(DateTime.fromMillisecondsSinceEpoch(r.endedAt)),
                ),
          ],
        ),
      ],
    );
  }

  Future<void> _cycleTraining(InterceptConfig config) {
    final steps = InterceptPage.trainingSteps;
    final current = config.trainingMs ~/ 1000;
    final i = steps.indexOf(current);
    return _patch({'trainingMs': steps[(i + 1) % steps.length] * 1000});
  }

  Widget _counter(String label, int value) =>
      ListPageRow(icon: AppIcons.chat, title: label, value: '$value');
}

/// The kernel's outcome codes, in the person's words. Unknown codes are shown verbatim: a code this
/// build does not know is still a real receipt, and hiding it would be worse than showing it.
String interceptOutcomeLabel(L10n l10n, String? outcome) => switch (outcome) {
      'BLOCKED' => l10n.interceptOutcomeBlocked,
      'RELEASED' => l10n.interceptOutcomeReleased,
      'EXITED' => l10n.interceptOutcomeExited,
      'PAUSED' => l10n.interceptOutcomePaused,
      'NOT_SHOWN' => l10n.interceptOutcomeNotShown,
      _ => outcome ?? '',
    };

/// Adds one or more entertainment apps from 1-tap presets, installed launcher apps, or a custom package name.
class _AddTargetDialog extends StatefulWidget {
  const _AddTargetDialog({
    required this.loop,
    required this.existingPackages,
  });

  final LoopChannel loop;
  final Set<String> existingPackages;

  static const presets = <InterceptTarget>[
    InterceptTarget(packageName: 'tv.danmaku.bili', label: '哔哩哔哩'),
    InterceptTarget(packageName: 'com.ss.android.ugc.aweme', label: '抖音'),
    InterceptTarget(packageName: 'com.smile.gifmaker', label: '快手'),
    InterceptTarget(packageName: 'com.xingin.xhs', label: '小红书'),
    InterceptTarget(packageName: 'com.sina.weibo', label: '微博'),
    InterceptTarget(packageName: 'com.zhihu.android', label: '知乎'),
    InterceptTarget(packageName: 'com.baidu.tieba', label: '百度贴吧'),
    InterceptTarget(packageName: 'com.tencent.tmgp.sgame', label: '王者荣耀'),
    InterceptTarget(packageName: 'com.tencent.tmgp.pubgmhd', label: '和平精英'),
    InterceptTarget(packageName: 'com.miHoYo.Yuanshen', label: '原神'),
    InterceptTarget(packageName: 'com.miHoYo.hkrpg', label: '崩坏：星穹铁道'),
    InterceptTarget(packageName: 'com.tencent.jkchess', label: '金铲铲之战'),
    InterceptTarget(packageName: 'com.taptap', label: 'TapTap'),
    InterceptTarget(packageName: 'com.google.android.youtube', label: 'YouTube'),
    InterceptTarget(packageName: 'com.twitter.android', label: 'X'),
  ];

  @override
  State<_AddTargetDialog> createState() => _AddTargetDialogState();
}

class _AddTargetDialogState extends State<_AddTargetDialog> {
  final _controller = TextEditingController();
  final Map<String, String> _selected = <String, String>{};
  List<LoopApp> _installed = const [];
  bool _loadingApps = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_loadInstalled());
  }

  Future<void> _loadInstalled() async {
    final apps = await widget.loop.apps();
    if (!mounted) return;
    setState(() {
      _installed = apps
          .where((a) => a.package.isNotEmpty && !widget.existingPackages.contains(a.package))
          .toList()
        ..sort((a, b) => a.label.compareTo(b.label));
      _loadingApps = false;
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _toggle(String pkg, String label) {
    setState(() {
      _error = null;
      if (_selected.containsKey(pkg)) {
        _selected.remove(pkg);
      } else {
        _selected[pkg] = label;
      }
    });
  }

  void _submit() {
    final typed = _controller.text.trim();
    final items = <InterceptTarget>[
      for (final e in _selected.entries) InterceptTarget(packageName: e.key, label: e.value),
    ];
    if (items.isEmpty) {
      if (typed.isEmpty || typed.contains(' ') || !typed.contains('.')) {
        setState(() => _error = context.l10n.interceptAddTargetInvalid);
        return;
      }
      if (!widget.existingPackages.contains(typed)) {
        items.add(InterceptTarget(packageName: typed, label: typed));
      }
    } else if (typed.contains('.') && !typed.contains(' ') && !_selected.containsKey(typed) && !widget.existingPackages.contains(typed)) {
      final matched = _installed.where((a) => a.package == typed).firstOrNull;
      items.add(InterceptTarget(packageName: typed, label: matched?.label ?? typed));
    }
    Navigator.of(context).pop(items);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final query = _controller.text.trim().toLowerCase();
    final availablePresets = _AddTargetDialog.presets
        .where((p) => !widget.existingPackages.contains(p.packageName))
        .toList();
    final filteredInstalled = _installed.where((a) {
      if (query.isEmpty) return true;
      return a.label.toLowerCase().contains(query) || a.package.toLowerCase().contains(query);
    }).toList();

    return AlertDialog(
      title: Text(l10n.interceptAddTarget),
      content: SizedBox(
        width: 400,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _controller,
                decoration: InputDecoration(
                  prefixIcon: const Icon(AppIcons.search, size: 18),
                  hintText: '搜索已安装应用，或直接输入包名',
                  errorText: _error,
                ),
                onChanged: (_) => setState(() => _error = null),
                onSubmitted: (_) => _submit(),
              ),
              if (availablePresets.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text('常用娱乐 / 游戏一键勾选', style: Theme.of(context).textTheme.labelMedium),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final preset in availablePresets)
                      FilterChip(
                        label: Text(preset.label),
                        selected: _selected.containsKey(preset.packageName),
                        onSelected: (_) => _toggle(preset.packageName, preset.label),
                      ),
                  ],
                ),
              ],
              const SizedBox(height: 12),
              Text(
                _loadingApps
                    ? '正在读取本机已安装应用…'
                    : (filteredInstalled.isEmpty ? '可在上方输入自定义包名（如 com.example.app）' : '本机已安装应用（${filteredInstalled.length}）'),
                style: Theme.of(context).textTheme.labelMedium,
              ),
              if (filteredInstalled.isNotEmpty) ...[
                const SizedBox(height: 6),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 220),
                  child: SingleChildScrollView(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (final app in filteredInstalled)
                          Builder(
                            builder: (context) {
                              final checked = _selected.containsKey(app.package);
                              return InkWell(
                                borderRadius: BorderRadius.circular(10),
                                onTap: () => _toggle(app.package, app.label),
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 4),
                                  child: Row(
                                    children: [
                                      Icon(
                                        checked ? AppIcons.checkboxOn : AppIcons.checkboxOff,
                                        size: 20,
                                        color: checked ? Theme.of(context).colorScheme.primary : null,
                                      ),
                                      const SizedBox(width: 10),
                                      Expanded(
                                        child: Column(
                                          crossAxisAlignment: CrossAxisAlignment.start,
                                          children: [
                                            Text(app.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                                            Text(
                                              app.package,
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                              style: Theme.of(context).textTheme.bodySmall,
                                            ),
                                          ],
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(l10n.commonCancel)),
        TextButton(
          onPressed: _submit,
          child: Text(_selected.isEmpty ? l10n.commonSave : '添加 (${_selected.length})'),
        ),
      ],
    );
  }
}
