import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../l10n/l10n.dart';
import '../../loop/loop_channel.dart';
import '../common/app_sheet.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'action_log_page.dart' show formatLogTime;

/// 设置 → 手机操控 → 触发测试: pick a test app, switch the first closed loop on,
/// read its receipts. Everything is stored on the native side (loop/LoopEngine.kt).
class LoopTestPage extends StatefulWidget {
  const LoopTestPage({super.key, this.channel});

  final LoopChannel? channel;

  static const cooldownSteps = [0, 10, 60, 300];

  /// T2: how long the page or notification waits for an answer.
  static const timeoutSteps = [15, 30, 60, 120];

  /// 不限 → 3 → 5 → 10 → 不限.
  static const dailyCapSteps = [0, 3, 5, 10];

  /// The full history is 200 receipts; export all of them.
  static const exportLimit = 200;

  @override
  State<LoopTestPage> createState() => _LoopTestPageState();
}

class _LoopTestPageState extends State<LoopTestPage> {
  late final LoopChannel _channel = widget.channel ?? LoopChannel();
  LoopStatus? _status;
  List<LoopEvent> _events = const [];
  Map<String, String> _labels = const {};
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    // Receipts land while the person is in the test app; pick them up on return.
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => unawaited(_refresh()));
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final status = await _channel.status();
    final events = await _channel.events(limit: 100);
    if (!mounted) return;
    setState(() {
      _status = status;
      _events = events;
    });
  }

  Future<void> _setEnabled(bool on) async {
    await _channel.setConfig(enabled: on);
    await _refresh();
  }

  Future<void> _cycleCooldown(int currentMs) async {
    const steps = LoopTestPage.cooldownSteps;
    final i = steps.indexOf(currentMs ~/ 1000);
    await _channel.setConfig(cooldownMs: steps[(i + 1) % steps.length] * 1000);
    await _refresh();
  }

  /// Cycles 关 → 23:00–08:00 → 12:00–14:00 → 关. Two presets keep this to one tap, like cooldown.
  Future<void> _cycleQuietHours(LoopConfig config) async {
    const presets = [(23 * 60, 8 * 60), (12 * 60, 14 * 60)];
    final start = config.quietStartMinute;
    final end = config.quietEndMinute;
    var i = -1;
    if (config.quietHoursSet && start != null && end != null) {
      i = presets.indexWhere((p) => p.$1 == start && p.$2 == end);
    }
    // Off → first preset, preset → next preset, last preset (or an unknown pair) → off again.
    if (i < 0) {
      await _channel.setConfig(quietStartMinute: presets.first.$1, quietEndMinute: presets.first.$2);
    } else if (i >= presets.length - 1) {
      await _channel.setConfig(clearQuietHours: true);
    } else {
      final next = presets[i + 1];
      await _channel.setConfig(quietStartMinute: next.$1, quietEndMinute: next.$2);
    }
    await _refresh();
  }

  Future<void> _cycleDailyCap(int current) async {
    const steps = LoopTestPage.dailyCapSteps;
    final i = steps.indexOf(current);
    await _channel.setConfig(dailyCap: steps[(i + 1) % steps.length]);
    await _refresh();
  }

  Future<void> _clearDiagnostics() async {
    await _channel.clearDiagnostics();
    await _refresh();
  }

  static bool _hasDiagnostics(LoopStatus s) =>
      s.diagnostics.faults.isNotEmpty || s.diagnostics.recent.isNotEmpty || s.diagnostics.lastReadAt != null;

  Future<void> _pickApp() async {
    final apps = await _channel.apps();
    if (!mounted) return;
    _labels = {for (final a in apps) a.package: a.label};
    final picked = await showAppSheet<String>(
      context,
      heightFactor: 0.75,
      builder: (sheet) => _AppList(apps: apps, current: _status?.config.targetPackage),
    );
    if (picked == null) return;
    await _channel.setConfig(targetPackage: picked);
    await _refresh();
  }

  Future<void> _clear() async {
    await _channel.clearEvents();
    await _refresh();
  }

  Future<void> _cycleTimeout(int currentMs) async {
    const steps = LoopTestPage.timeoutSteps;
    final i = steps.indexOf(currentMs ~/ 1000);
    await _channel.setConfig(timeoutMs: steps[(i + 1) % steps.length] * 1000);
    await _refresh();
  }

  /// T2: the raw receipts as JSON on the clipboard, for a bug report.
  Future<void> _exportReceipts() async {
    final events = await _channel.events(limit: LoopTestPage.exportLimit);
    final status = await _channel.status();
    final payload = const JsonEncoder.withIndent('  ').convert({
      'exportedAt': DateTime.now().toIso8601String(),
      'config': status?.config.toMap(),
      'recoveredFrom': status?.recoveredFrom,
      'storageError': status?.storageError,
      'triggersToday': status?.triggersToday,
      'triggerDay': status?.triggerDay,
      'diagnostics': status?.diagnostics.toMap(),
      'events': [for (final e in events) e.toMap()],
    });
    await Clipboard.setData(ClipboardData(text: payload));
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(context.l10n.loopExportDone(events.length))));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final colors = DshColors.of(context);
    final status = _status;
    final config = status?.config ?? const LoopConfig();
    final target = config.targetPackage;
    final a11y = status == null
        ? l10n.phoneControlLoading
        : !status.a11yEnabled
        ? l10n.loopA11yNeeded
        : !status.a11yConnected
        ? l10n.loopA11yWaiting
        : l10n.loopA11yReady;
    return AppPage.list(
      title: l10n.loopEntryTitle,
      subtitle: Text(l10n.loopPageSubtitle),
      actions: [
        GlassIconButton(icon: AppIcons.refresh, tooltip: l10n.loopRefresh, onPressed: () => unawaited(_refresh())),
      ],
      children: [
        ListPageCard(
          children: [
            MergeSemantics(
              child: ListPageRow(
                icon: AppIcons.play,
                title: l10n.loopEnabled,
                subtitle: l10n.loopEnabledHint,
                subtitleMaxLines: 2,
                onTap: status == null ? null : () => unawaited(_setEnabled(!config.enabled)),
                trailing: Switch(
                  value: config.enabled,
                  onChanged: status == null ? null : (v) => unawaited(_setEnabled(v)),
                ),
              ),
            ),
            ListPageRow(
              icon: AppIcons.appRules,
              title: l10n.loopTargetApp,
              subtitle: target == null ? l10n.loopTargetNone : '${_labels[target] ?? target}\n$target',
              subtitleMaxLines: 2,
              onTap: status == null ? null : () => unawaited(_pickApp()),
            ),
            ListPageRow(
              icon: AppIcons.queued,
              title: l10n.loopCooldown,
              subtitle: l10n.loopCooldownHint,
              value: l10n.loopSeconds(config.cooldownMs ~/ 1000),
              onTap: status == null ? null : () => unawaited(_cycleCooldown(config.cooldownMs)),
            ),
            ListPageRow(
              icon: AppIcons.commandTimeout,
              title: l10n.loopTimeout,
              subtitle: l10n.loopTimeoutHint,
              value: l10n.loopSeconds(config.timeoutMs ~/ 1000),
              onTap: status == null ? null : () => unawaited(_cycleTimeout(config.timeoutMs)),
            ),
            ListPageRow(
              icon: AppIcons.notificationsOff,
              title: l10n.loopQuietHours,
              subtitle: l10n.loopQuietHoursHint,
              value: config.quietHoursSet
                  ? l10n.loopQuietHoursValue(loopClock(config.quietStartMinute!), loopClock(config.quietEndMinute!))
                  : l10n.loopQuietHoursOff,
              onTap: status == null ? null : () => unawaited(_cycleQuietHours(config)),
            ),
            ListPageRow(
              icon: AppIcons.today,
              title: l10n.loopDailyCap,
              subtitle: l10n.loopDailyCapHint,
              value: config.dailyCap <= 0
                  ? l10n.loopDailyCapOff
                  : l10n.loopDailyCapValue(status?.triggersToday ?? 0, config.dailyCap),
              onTap: status == null ? null : () => unawaited(_cycleDailyCap(config.dailyCap)),
            ),
            ListPageRow(
              icon: status?.a11yConnected ?? false ? AppIcons.allowed : AppIcons.error,
              iconColor: status?.a11yConnected ?? false ? colors.success : colors.warning,
              title: a11y,
              titleMaxLines: 2,
              subtitle: status == null
                  ? null
                  : l10n.loopCounters(status.eventsSeen, status.readings, status.lastForeground ?? '-'),
              subtitleMaxLines: 2,
            ),
            if (status?.storageError != null)
              ListPageRow(
                icon: AppIcons.error,
                iconColor: colors.danger,
                title: l10n.loopStorageError(status!.storageError!),
                titleMaxLines: 3,
              ),
            if (status?.recoveredFrom != null)
              ListPageRow(
                icon: AppIcons.warning,
                iconColor: colors.warning,
                title: l10n.loopRecoveredFromBackup(status!.recoveredFrom!),
                titleMaxLines: 3,
              ),
          ],
        ),
        const SizedBox(height: 24),
        // Accessibility failures used to be invisible (Log.w only). Now they are on the page.
        if (status != null && _hasDiagnostics(status))
          ListPageCard(
            title: l10n.loopDiagnostics,
            children: [
              ListPageRow(
                icon: AppIcons.info,
                title: l10n.loopDiagnosticsSummary(
                  status.eventsSeen,
                  status.readings,
                  status.diagnostics.faults.values.fold<int>(0, (a, b) => a + b),
                ),
                titleMaxLines: 3,
                subtitle: status.diagnostics.lastReadAt == null ? null : l10n.loopDiagnosticsLastRead(formatLogTime(status.diagnostics.lastReadAt!)),
                subtitleMaxLines: 2,
              ),
              for (final f in status.diagnostics.recent.take(5))
                ListPageRow(
                  icon: AppIcons.error,
                  iconColor: colors.danger,
                  title: loopFaultKindLabel(l10n, f.kind),
                  subtitle: f.detail,
                  subtitleMaxLines: 2,
                  meta: formatLogTime(f.at),
                ),
              ListPageRow(icon: AppIcons.clearAll, title: l10n.loopDiagnosticsClear, onTap: () => unawaited(_clearDiagnostics())),
            ],
          ),
        const SizedBox(height: 24),
        ListPageCard(
          title: l10n.loopReceipts,
          children: [
            if (_events.isEmpty)
              ListPageRow(icon: AppIcons.history, iconColor: colors.textTertiary, title: l10n.loopReceiptsEmpty)
            else ...[
              for (final e in _events) _ReceiptRow(e, label: _labels[e.packageName]),
              ListPageRow(icon: AppIcons.clearAll, title: l10n.loopClear, onTap: () => unawaited(_clear())),
            ],
            ListPageRow(icon: AppIcons.copy, title: l10n.loopExport, onTap: () => unawaited(_exportReceipts())),
          ],
        ),
        const SizedBox(height: 24),
      ],
    );
  }
}

String loopResultLabel(L10n l10n, String? result) => switch (result) {
  'RETURNED' => l10n.loopResultReturned,
  'BACK' => l10n.loopResultBack,
  'DISMISSED' => l10n.loopResultDismissed,
  'TIMEOUT' => l10n.loopResultTimeout,
  'ABANDONED' => l10n.loopResultAbandoned,
  'UNAVAILABLE' => l10n.loopResultUnavailable,
  'SUPPRESSED' => l10n.loopResultSuppressed,
  'ERROR' => l10n.loopResultError,
  null => l10n.loopResultOpen,
  _ => result,
};

/// "23:00" from minutes after midnight.
String loopClock(int minuteOfDay) {
  final m = minuteOfDay.clamp(0, 1439);
  final h = (m ~/ 60).toString().padLeft(2, '0');
  final mm = (m % 60).toString().padLeft(2, '0');
  return '$h:$mm';
}

/// The diagnostics card's per-fault title.
String loopFaultKindLabel(L10n l10n, String kind) => switch (kind) {
  'event' => l10n.loopFaultEvent,
  'read_foreground' => l10n.loopFaultRead,
  'verify' => l10n.loopFaultVerify,
  'attach' => l10n.loopFaultAttach,
  'surface' => l10n.loopFaultSurface,
  _ => kind,
};

/// Receipt detail: where it was asked, then the visit, the reason and the error.
String loopReceiptDetail(L10n l10n, LoopEvent e, String? label) {
  final surface = switch (e.surface) {
    'notification' => l10n.loopSurfaceNotification,
    'activity' => null,
    _ => e.surface,
  };
  // T2: the visit id, shortened — enough to tell two visits apart in a bug report.
  final visit = e.visitId == null || e.visitId!.isEmpty
      ? null
      : l10n.loopVisitShort(e.visitId!.length > 8 ? e.visitId!.substring(0, 8) : e.visitId!);
  return [?label ?? e.packageName, ?surface, ?visit, ?e.reason, ?e.error].join(' · ');
}

class _ReceiptRow extends StatelessWidget {
  const _ReceiptRow(this.event, {this.label});

  final LoopEvent event;
  final String? label;

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final (icon, color) = switch (event.result) {
      'RETURNED' || 'BACK' => (AppIcons.allowed, colors.success),
      'DISMISSED' || 'TIMEOUT' || 'SUPPRESSED' => (AppIcons.stopped, colors.warning),
      'ABANDONED' || 'UNAVAILABLE' || 'ERROR' => (AppIcons.error, colors.danger),
      _ => (AppIcons.pending, colors.textTertiary),
    };
    final detail = loopReceiptDetail(context.l10n, event, label);
    return ListPageRow(
      icon: icon,
      iconColor: color,
      title: loopResultLabel(context.l10n, event.result),
      subtitle: detail.isEmpty ? null : detail,
      subtitleMaxLines: 2,
      meta: formatLogTime(event.updatedAt ?? event.timestamp),
    );
  }
}

class _AppList extends StatelessWidget {
  const _AppList({required this.apps, this.current});

  final List<LoopApp> apps;
  final String? current;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
          child: Text(l10n.loopPickApp, style: Theme.of(context).textTheme.titleMedium),
        ),
        Expanded(
          child: apps.isEmpty
              ? Center(child: Text(l10n.loopNoApps))
              : ListView.builder(
                  itemCount: apps.length,
                  itemBuilder: (context, i) {
                    final a = apps[i];
                    return ListTile(
                      title: Text(a.label),
                      subtitle: Text(a.package),
                      trailing: a.package == current ? const Icon(AppIcons.selected) : null,
                      onTap: () => Navigator.pop(context, a.package),
                    );
                  },
                ),
        ),
      ],
    );
  }
}
