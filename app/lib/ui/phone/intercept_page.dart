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
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'action_log_page.dart' show formatLogTime;

class InterceptPage extends StatefulWidget {
  const InterceptPage({super.key, this.channel});

  final InterceptChannel? channel;

  /// The daily-cap ladder, in blocks per day; 0 turns the cap off.
  static const dailyMaxSteps = [0, 2, 4, 6, 10];

  /// The cooldown ladder, in minutes; 0 allows back-to-back interceptions.
  static const cooldownSteps = [0, 2, 5, 15, 30];

  /// The pass length ladder, in minutes.
  static const releaseSteps = [1, 5, 10, 30, 60];

  /// The drill length ladder, in seconds. It is a target, not a limit: a wrong press restarts it.
  static const trainingSteps = [30, 60, 120, 300];

  @override
  State<InterceptPage> createState() => _InterceptPageState();
}

class _InterceptPageState extends State<InterceptPage> {
  late final InterceptChannel _channel = widget.channel ?? InterceptChannel();
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

  Future<void> _setEnabled(bool on) => _patch({'enabled': on});

  Future<void> _cycle(List<int> steps, int current, String key) {
    final i = steps.indexOf(current);
    return _patch({key: steps[(i + 1) % steps.length]});
  }

  Future<void> _addTarget() async {
    final pkg = await showDialog<String>(
      context: context,
      builder: (_) => const _AddTargetDialog(),
    );
    if (pkg == null || !mounted) return;
    final config = _status?.config ?? InterceptConfig.empty;
    if (config.targets.any((t) => t.packageName == pkg)) return;
    final next = [...config.targets, InterceptTarget(packageName: pkg, label: pkg)];
    await _patch({'targets': next.map((t) => t.toWire()).toList()});
  }

  Future<void> _removeTarget(String pkg) async {
    final config = _status?.config ?? InterceptConfig.empty;
    final next = config.targets.where((t) => t.packageName != pkg).toList();
    await _patch({'targets': next.map((t) => t.toWire()).toList()});
  }

  Future<void> _pause() async {
    try {
      await _channel.pause();
      await _refresh();
    } on InterceptFailed catch (e) {
      if (mounted) setState(() => _error = e.cause);
    }
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
        ]),
        const SizedBox(height: 24),
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
        const SizedBox(height: 24),
        ListPageCard(
          title: l10n.interceptMode,
          children: [
            ListPageRow(
              icon: AppIcons.commandTimeout,
              title: l10n.interceptRelease,
              value: '${config.releaseMs ~/ 60000}',
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
              title: l10n.interceptTraining,
              value: '${config.trainingMs ~/ 1000}',
              onTap: status == null
                  ? null
                  : () => unawaited(_cycleTraining(config)),
            ),
            ListPageRow(
              icon: AppIcons.commandTimeout,
              title: l10n.interceptCooldown,
              value: '${config.cooldownMs ~/ 60000}',
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
              value: config.dailyMax <= 0 ? l10n.interceptDailyMaxOff : '${config.dailyMax}',
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

/// Adds one package name. The kernel re-validates it; this only refuses what cannot be a package.
class _AddTargetDialog extends StatefulWidget {
  const _AddTargetDialog();

  @override
  State<_AddTargetDialog> createState() => _AddTargetDialogState();
}

class _AddTargetDialogState extends State<_AddTargetDialog> {
  final _controller = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final v = _controller.text.trim();
    if (v.isEmpty || v.contains(' ')) {
      setState(() => _error = context.l10n.interceptAddTargetInvalid);
      return;
    }
    Navigator.of(context).pop(v);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return AlertDialog(
      title: Text(l10n.interceptAddTarget),
      content: TextField(
        controller: _controller,
        autofocus: true,
        decoration: InputDecoration(
          hintText: l10n.interceptAddTargetHint,
          errorText: _error,
        ),
        onSubmitted: (_) => _submit(),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(l10n.commonCancel)),
        TextButton(onPressed: _submit, child: Text(l10n.commonSave)),
      ],
    );
  }
}
