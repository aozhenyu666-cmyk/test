import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../diagnostics/diagnostics_channel.dart';
import '../../diagnostics/diagnostics_export.dart';
import '../../l10n/l10n.dart';
import '../../proactive/proactive_state.dart';
import '../common/app_sheet.dart';
import '../common/brief_notice.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'action_log_page.dart' show formatLogTime;
import 'loop_test_page.dart' show loopClock;
import 'wall_page.dart';

/// 璁剧疆 鈫?鎵嬫満鎿嶆帶 鈫?涓诲姩鎼瘽: the proactive kernel's own page.
///
/// Everything it shows comes from `dsh/proactive` `status` and `receipts`; nothing is computed
/// here. Two things it deliberately says out loud, because both are true on a locked-down phone:
/// when the system forbids background wakeups (`wakeMode == OPPORTUNISTIC`), and when the kernel is
/// holding back because DSH is using the phone.
class ProactivePage extends StatefulWidget {
  const ProactivePage({super.key, this.channel});

  final ProactiveChannel? channel;

  /// Random gap ladder, in minutes; the kernel clamps anything outside its own range.
  /// Quiet-hours presets; the first entry turns them off.
  static const quietPresets = [
    (0, 0),
    (23 * 60, 8 * 60),
    (22 * 60, 8 * 60),
    (12 * 60, 14 * 60),
  ];

  // 20 / 40: 教练模式按乒乓的频率打（插件那边每天 40），手机侧的上限不能先把它卡在 10。
  static const dailyMaxSteps = [0, 2, 4, 6, 10, 20, 40];

  @override
  State<ProactivePage> createState() => _ProactivePageState();
}

class _ProactivePageState extends State<ProactivePage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  ProactiveStatus? _status;
  List<ProactiveReceipt> _receipts = const [];
  ProactiveAgentSessions _sessions = const ProactiveAgentSessions();
  Timer? _poll;
  bool _exporting = false;
  bool _foregroundDebug = false;
  bool _foregroundBusy = false;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    unawaited(_loadForegroundDebug());
    _poll = Timer.periodic(const Duration(seconds: 4), (_) => unawaited(_refresh()));
  }

  Future<void> _loadForegroundDebug() async {
    final enabled = await const DiagnosticsChannel().foregroundDebug();
    if (mounted) setState(() => _foregroundDebug = enabled);
  }

  Future<void> _setForegroundDebug(bool enabled) async {
    if (_foregroundBusy) return;
    setState(() => _foregroundBusy = true);
    try {
      final stored = await const DiagnosticsChannel().setForegroundDebug(enabled);
      if (mounted) setState(() => _foregroundDebug = stored);
    } finally {
      if (mounted) setState(() => _foregroundBusy = false);
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final status = await _channel.status();
    final receipts = await _channel.receipts(limit: 50);
    final sessions = await _channel.agentSessions();
    if (!mounted) return;
    setState(() {
      _status = status;
      _receipts = receipts;
      _sessions = sessions;
    });
  }

  /// D1: one zip in Downloads with what a bug report needs (engine exits, the interaction ledger,
  /// the companion config and module state, the accessibility watchdog log). No screen text.
  Future<void> _exportDiagnostics() async {
    if (_exporting) return;
    setState(() => _exporting = true);
    try {
      final exporter = ProviderScope.containerOf(context, listen: false).read(diagnosticsExporterProvider);
      final where = await exporter.saveToDownloads();
      if (mounted) showBriefNotice(context, diagnosticsSavedText(where));
    } catch (error) {
      debugPrint('Diagnostics export failed: $error');
      if (mounted) {
        ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(diagnosticsErrorText(error))));
      }
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }

  /// Picks the session proactive turns go to. Only live sessions are offered; the plugin refuses a
  /// missing, archived or busy one itself, so this page never has to guess.
  Future<void> _pickSession() async {
    final sessions = await _channel.agentSessions();
    if (!mounted) return;
    setState(() => _sessions = sessions);
    if (!sessions.ready) return;
    final picked = await showAppSheet<String>(
      context,
      heightFactor: 0.6,
      builder: (sheet) => _SessionList(sessions: sessions),
    );
    if (picked == null) return;
    // An empty id means "no target", which the plugin stores as null.
    await _channel.setAgentSession(picked.isEmpty ? null : picked);
    await _refresh();
  }

  /// 选姐姐（谋士）的会话：和选陪伴会话同一个列表，写进插件的 strategistSessionId。
  Future<void> _pickStrategist() async {
    final sessions = await _channel.agentSessions();
    if (!mounted) return;
    setState(() => _sessions = sessions);
    if (!sessions.ready) return;
    final picked = await showAppSheet<String>(
      context,
      heightFactor: 0.6,
      builder: (sheet) => _SessionList(sessions: sessions),
    );
    if (picked == null) return;
    if (picked.isNotEmpty && picked == sessions.sessionId) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('姐姐和妹妹要用两个不同的会话：先在 DSH 里新建一个对话，再回来选')),
        );
      }
      return;
    }
    await _companion({'strategistSessionId': picked.isEmpty ? null : picked});
  }

  /// 第五轮：选墙（经理）的会话。必须是第三个会话：和姐妹共用会让模型同时拿到两套人设。
  Future<void> _pickWall() async {
    final sessions = await _channel.agentSessions();
    if (!mounted) return;
    setState(() => _sessions = sessions);
    if (!sessions.ready) return;
    final picked = await showAppSheet<String>(
      context,
      heightFactor: 0.6,
      builder: (sheet) => _SessionList(sessions: sessions),
    );
    if (picked == null) return;
    final sisters = {sessions.sessionId, sessions.config['strategistSessionId']};
    if (picked.isNotEmpty && sisters.contains(picked)) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('墙要用单独的会话：先在 DSH 里新建一个对话，再回来选')),
        );
      }
      return;
    }
    await _companion({'wallSessionId': picked.isEmpty ? null : picked});
  }

  Future<void> _save(ProactiveConfig next) async {
    await _channel.setConfig(next);
    await _refresh();
  }

  Future<void> _setEnabled(bool on) async {
    final config = _status?.config ?? const ProactiveConfig();
    // 开启时如果还没选会话，自动取第一个空闲的活会话：否则插件会 409 no-session，
    // 用户看到的就是“开了但从来不说话”。
    if (on && _sessions.sessionId == null) {
      final sessions = await _channel.agentSessions();
      final pick = sessions.agents.where((a) => a.idle).firstOrNull ?? sessions.agents.firstOrNull;
      if (pick != null) {
        await _channel.setAgentSession(pick.id);
        await _refresh();
      }
    }
    await _save(config.copyWith(enabled: on));
  }

  /// 「现在试一次」：跳过间隔/安静/日上限，但仍受 pending 与 agent busy 约束。
  Future<void> _tryNow() async {
    final ok = await _channel.tickNow();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(ok ? '已触发一次主动尝试' : '触发失败：先确认引擎已连接')),
    );
    await _refresh();
  }

  /// Lets the person type any number of minutes instead of cycling presets.
  ///
  /// The kernel clamps to its own range (60 s … 7 d) and keeps min <= max, so this only has to
  /// reject nonsense before sending: a blank field cancels, a non-positive number is refused with
  /// the range in the message.
  Future<void> _editMinutes({
    required String title,
    required int currentMinutes,
    required ProactiveConfig config,
    required bool isMin,
  }) async {
    // The dialog owns its TextEditingController: disposing one from the caller right after
    // `showDialog` resolves crashes, because the exit animation still reads it.
    final value = await showDialog<int>(
      context: context,
      builder: (_) => _MinutesDialog(title: title, initial: currentMinutes),
    );
    if (value == null || !mounted) return;
    final ms = value * 60000;
    // Keep the window consistent: raising the minimum lifts the maximum, and vice versa.
    await _save(
      isMin
          ? config.copyWith(minIntervalMs: ms, maxIntervalMs: config.maxIntervalMs < ms ? ms : config.maxIntervalMs)
          : config.copyWith(maxIntervalMs: ms, minIntervalMs: config.minIntervalMs > ms ? ms : config.minIntervalMs),
    );
  }

  Future<void> _cycleQuiet(ProactiveConfig config) async {
    const presets = ProactivePage.quietPresets;
    var i = presets.indexWhere((p) => p.$1 == config.quietStartMinute && p.$2 == config.quietEndMinute);
    if (i < 0) i = -1;
    final next = presets[(i + 1) % presets.length];
    await _save(config.copyWith(quietStartMinute: next.$1, quietEndMinute: next.$2));
    // 大肥鱼自己也有安静时段（按小时）：一起改，两边说法一致。起止相同 = 不设。
    if (_sessions.ready) {
      await _channel.setCompanionConfig({'quietStart': next.$1 ~/ 60, 'quietEnd': next.$2 ~/ 60});
      await _refresh();
    }
  }

  Future<void> _cycleDaily(ProactiveConfig config) async {
    const steps = ProactivePage.dailyMaxSteps;
    final i = steps.indexOf(config.dailyMax);
    await _save(config.copyWith(dailyMax: steps[(i + 1) % steps.length]));
  }

  // ---- 大肥鱼自己的参数（插件 /config） ---------------------------------------------------

  static const _partnerGapSteps = [10, 15, 20, 30, 45, 60, 90];
  static const _coachGapSteps = [3, 5, 8, 10, 15, 20, 30];
  static const _hourlySteps = [2, 3, 4, 6, 8, 10, 12];
  static const _dailySteps = [6, 10, 20, 40, 0];

  int _companionInt(String key, int fallback) {
    final v = _sessions.config[key];
    return v is num ? v.toInt() : fallback;
  }

  bool get _coach => _sessions.config['mode'] == 'coach';

  int _nextStep(List<int> steps, int current) {
    final i = steps.indexOf(current);
    return steps[(i + 1) % steps.length];
  }

  Future<void> _companion(Map<String, Object?> patch) async {
    final ok = await _channel.setCompanionConfig(patch);
    if (!mounted) return;
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('没改成：大肥鱼没回应，稍后再试')));
    }
    await _refresh();
  }

  Future<void> _editCompanionGap() async {
    final key = _coach ? 'coachMinGapMs' : 'minGapMs';
    final current = _companionInt(key, _coach ? 8 * 60000 : 45 * 60000) ~/ 60000;
    final value = await showDialog<int>(
      context: context,
      builder: (_) => _MinutesDialog(title: _coach ? '严格模式最短间隔' : '温和模式最短间隔', initial: current),
    );
    if (value == null || !mounted) return;
    await _companion({key: value * 60000});
  }

  List<Widget> _companionRows() {
    final coach = _coach;
    final gapKey = coach ? 'coachMinGapMs' : 'minGapMs';
    final hourKey = coach ? 'coachHourlyMax' : 'hourlyMax';
    final dayKey = coach ? 'coachDailyMax' : 'dailyMax';
    final gap = _companionInt(gapKey, coach ? 8 * 60000 : 45 * 60000) ~/ 60000;
    final hourly = _companionInt(hourKey, coach ? 6 : 3);
    final daily = _companionInt(dayKey, coach ? 40 : 6);
    return [
      ListPageRow(
        icon: AppIcons.chat,
        title: '说话模式',
        subtitle: '聊天里说「严格点」「温柔点」也能切',
        value: coach ? '严格' : '温和',
        onTap: () => unawaited(_companion({'mode': coach ? 'partner' : 'coach'})),
      ),
      GestureDetector(
        onLongPress: () => unawaited(_editCompanionGap()),
        child: ListPageRow(
        icon: AppIcons.commandTimeout,
        title: coach ? '最短间隔（严格）' : '最短间隔（温和）',
        subtitle: '点一下换档，长按自己填。越陷越深会自动缩短，刷到停不下来时最快约 1.5 分钟一次',
        subtitleMaxLines: 3,
        value: '$gap 分钟',
        onTap: () {
          final steps = coach ? _coachGapSteps : _partnerGapSteps;
          unawaited(_companion({gapKey: _nextStep(steps, gap) * 60000}));
        },
        ),
      ),
      ListPageRow(
        icon: AppIcons.commandTimeout,
        title: '每小时最多',
        subtitle: '你接住之后接着聊的不算，被冷落后补的几球也不算',
        subtitleMaxLines: 2,
        value: '$hourly 次',
        onTap: () => unawaited(_companion({hourKey: _nextStep(_hourlySteps, hourly)})),
      ),
      ListPageRow(
        icon: AppIcons.today,
        title: '每天最多',
        subtitle: '只算新开的话头',
        value: daily <= 0 ? '不限' : '$daily 次',
        onTap: () => unawaited(_companion({dayKey: _nextStep(_dailySteps, daily)})),
      ),
    ];
  }

  /// P7: "能不能主动开口" — every row is one thing a new install must turn on, and its tap opens
  /// exactly that system page. Green rows are already done; nothing here decides anything.
  Widget _readinessCard(ProactiveStatus status, bool pluginOnline) {
    final caps = status.capabilities;
    final colors = DshColors.of(context);
    final items = <(String, bool, String)>[
      ('选好大肥鱼的会话', _sessions.sessionId != null, 'session'),
      ('无障碍已连接', caps['a11y'] ?? false, 'a11y'),
      ('悬浮窗权限', caps['overlay'] ?? false, 'overlay'),
      ('通知权限', caps['notifications'] ?? false, 'notifications'),
      ('精确闹钟', caps['exactAlarm'] ?? false, 'exactAlarm'),
      ('电池不限制', caps['battery'] ?? false, 'battery'),
    ];
    final missing = items.where((i) => !i.$2).length;
    return ListPageCard(
      title: '能不能主动开口',
      children: [
        ListPageRow(
          icon: missing == 0 ? AppIcons.allowed : AppIcons.warning,
          iconColor: missing == 0 ? colors.success : colors.warning,
          title: missing == 0 ? '六项都开了' : '还差 $missing 项',
          subtitle: missing == 0 ? '大肥鱼可以主动找你了' : '每项点一下就是对应的系统页，开完回来刷新',
          subtitleMaxLines: 2,
        ),
        for (final item in items)
          ListPageRow(
            icon: item.$2 ? AppIcons.allowed : AppIcons.error,
            iconColor: item.$2 ? colors.success : colors.textTertiary,
            title: item.$1,
            value: item.$2 ? '已开' : '去开启',
            onTap: item.$2
                ? null
                : () async {
                    if (item.$3 == 'session') {
                      await _pickSession();
                    } else {
                      await _channel.openCapability(item.$3);
                      await _refresh();
                    }
                  },
          ),
        if (!pluginOnline)
          ListPageRow(
            icon: AppIcons.warning,
            iconColor: colors.warning,
            title: '主动插件没连上',
            subtitle: '插件在线时由它决定什么时候开口；现在手机侧只是兜底',
            subtitleMaxLines: 2,
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final colors = DshColors.of(context);
    final status = _status;
    final config = status?.config ?? const ProactiveConfig();
    // The plugin answers /status: /plan is the only schedule while this is true (P2).
    final pluginOnline = _sessions.ready && (status?.transport ?? false);
    return AppPage.list(
      title: l10n.proactiveTitle,
      subtitle: Text(l10n.proactiveSubtitle),
      actions: [
        GlassIconButton(icon: AppIcons.refresh, tooltip: l10n.loopRefresh, onPressed: () => unawaited(_refresh())),
      ],
      children: [
        // P7: 新装什么都不能开口。把"还差哪几项"放在最上面，每项点开就是对应的系统页。
        if (status != null) _readinessCard(status, pluginOnline),
        if (status != null) const SizedBox(height: 24),
        ListPageCard(
          children: [
            MergeSemantics(
              child: ListPageRow(
                icon: AppIcons.notificationsOff,
                title: l10n.proactiveEnabled,
                subtitle: l10n.proactiveEnabledHint,
                subtitleMaxLines: 3,
                onTap: status == null ? null : () => unawaited(_setEnabled(!config.enabled)),
                trailing: Switch(
                  value: config.enabled,
                  onChanged: status == null ? null : (v) => unawaited(_setEnabled(v)),
                ),
              ),
            ),
            // 大肥鱼在线：间隔和上限都是它的参数，在这里直接改（以前这里只读，调不了）。
            if (pluginOnline) ..._companionRows()
            else ...[
            ListPageRow(
              icon: AppIcons.commandTimeout,
              title: l10n.proactiveGap,
              subtitle: pluginOnline ? '由大肥鱼决定（插件在线时手机侧只做兜底）' : l10n.proactiveGapHint,
              value: l10n.proactiveGapValue(config.minIntervalMs ~/ 60000, config.maxIntervalMs ~/ 60000),
              onTap: status == null || pluginOnline
                  ? null
                  : () => unawaited(_editMinutes(
                      title: l10n.proactiveGap,
                      currentMinutes: config.minIntervalMs ~/ 60000,
                      config: config,
                      isMin: true,
                    )),
            ),
            ListPageRow(
              icon: AppIcons.commandTimeout,
              title: l10n.proactiveGapMax,
              subtitle: pluginOnline ? '由大肥鱼决定' : l10n.proactiveGapMaxHint,
              value: l10n.proactiveMinutes(config.maxIntervalMs ~/ 60000),
              onTap: status == null || pluginOnline
                  ? null
                  : () => unawaited(_editMinutes(
                      title: l10n.proactiveGapMax,
                      currentMinutes: config.maxIntervalMs ~/ 60000,
                      config: config,
                      isMin: false,
                    )),
            ),
            ],
            ListPageRow(
              icon: AppIcons.notificationsOff,
              title: l10n.proactiveQuiet,
              subtitle: l10n.proactiveQuietHint,
              value: config.quietSet
                  ? l10n.proactiveQuietValue(loopClock(config.quietStartMinute), loopClock(config.quietEndMinute))
                  : l10n.loopQuietHoursOff,
              onTap: status == null ? null : () => unawaited(_cycleQuiet(config)),
            ),
            if (!pluginOnline)
            ListPageRow(
              icon: AppIcons.today,
              title: l10n.proactiveDaily,
              subtitle: pluginOnline ? '由大肥鱼决定（插件在线时手机侧只做兜底）' : l10n.proactiveDailyHint,
              value: config.dailyMax <= 0 ? l10n.loopDailyCapOff : l10n.proactiveDailyValue(config.dailyMax),
              onTap: status == null || pluginOnline ? null : () => unawaited(_cycleDaily(config)),
            ),
            ListPageRow(
              icon: AppIcons.chat,
              title: l10n.proactiveSession,
              subtitle: sessionsSubtitle(l10n, _sessions),
              subtitleMaxLines: 2,
              value: _sessions.sessionId == null && _sessions.ready ? l10n.proactiveSessionNone : null,
              onTap: () => unawaited(_pickSession()),
            ),
            // 姐妹双人格：上面那个会话是妹妹（陪伴，主动找你的是她），这里选姐姐（谋士，正事找她）。
            if (pluginOnline && _sessions.config['dual'] != false)
              ListPageRow(
                icon: AppIcons.chat,
                title: '谋士会话（${_sessions.config['strategistName'] ?? '大肥鱼'}）',
                subtitle: '正事找姐姐：规划、找工作、考试这些她来推演。上面那个会话是妹妹${_sessions.config['companionName'] ?? '小肥鱼'}，陪你玩、主动找你',
                subtitleMaxLines: 3,
                value: _sessions.config['strategistSessionId'] is String ? '已选' : '未选',
                onTap: () => unawaited(_pickStrategist()),
              ),
            // 第五轮：墙（经理）。不主动找你，只在你先开口时说话；开门只认证据。
            if (pluginOnline)
              ListPageRow(
                icon: AppIcons.chat,
                title: '墙的会话',
                subtitle: '墙是你的经理：定计划、对账、谈大事。要单独一个会话，不能和姐妹共用',
                subtitleMaxLines: 3,
                value: _sessions.config['wallSessionId'] is String ? '已选' : '未选',
                onTap: () => unawaited(_pickWall()),
              ),
            if (pluginOnline)
              ListPageRow(
                icon: AppIcons.plan,
                title: '面对墙',
                subtitle: '晨间计划、晚间回顾、每周总谈。墙不先开口，你先说',
                subtitleMaxLines: 2,
                onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const WallPage())),
              ),
            ListPageRow(
              icon: AppIcons.send,
              title: '现在试一次',
              subtitle: '跳过间隔/安静/日上限，立即走一次计划 → 回合；仍受忙闲与在途请求限制',
              subtitleMaxLines: 2,
              onTap: status == null ? null : () => unawaited(_tryNow()),
            ),
          ],
        ),
        const SizedBox(height: 24),
        ListPageCard(
          title: '伙伴今日',
          children: [
            ListPageRow(
              icon: AppIcons.chart,
              title: '主动链路',
              subtitle:
                  '尝试 ${_sessions.today['accepted'] ?? 0} · 生成 ${_sessions.today['generated'] ?? 0} · '
                  '回应 ${_sessions.today['replied'] ?? 0} · 实质 ${_sessions.today['substantive'] ?? 0} · '
                  '未回 ${_sessions.today['none'] ?? 0}',
              subtitleMaxLines: 2,
            ),
            ListPageRow(
              icon: AppIcons.bookmark,
              title: '最近聊过的话题',
              subtitle: _sessions.lastTopicId ?? '还没有（记忆里没有未完成话题）',
              subtitleMaxLines: 2,
            ),
            ListPageRow(
              icon: AppIcons.folder,
              title: '本地记忆库',
              subtitle: _sessions.memoryExists ? '已连接' : '未找到：先让它记住一条，再来试',
              subtitleMaxLines: 2,
            ),
          ],
        ),
        const SizedBox(height: 24),
        ListPageCard(
          title: l10n.proactiveState,
          children: [
            ListPageRow(
              icon: status?.holdReason == null ? AppIcons.allowed : AppIcons.stopped,
              iconColor: status?.holdReason == null ? colors.success : colors.warning,
              title: status?.holdReason == null ? l10n.proactiveGateFree : l10n.proactiveGateHold(proactiveHoldLabel(l10n, status!.holdReason!)),
              titleMaxLines: 3,
              subtitle: status?.activeStage == null ? null : l10n.proactiveActiveStage(status!.activeStage!),
              subtitleMaxLines: 2,
            ),
            // The honest warning: on a phone that forbids background wakeups there is no alarm at
            // all, and a message only happens while DSH is open or the screen changes.
            if (status != null && status.wakeRestricted)
              ListPageRow(
                icon: AppIcons.warning,
                iconColor: colors.warning,
                title: l10n.proactiveWakeRestricted,
                titleMaxLines: 4,
              ),
            if (status != null && status.wakeMode != 'OPPORTUNISTIC')
              ListPageRow(
                icon: AppIcons.alarmClock,
                title: l10n.proactiveWakeMode(status.wakeMode),
                subtitle: status.alarmError,
                subtitleMaxLines: 2,
              ),
            ListPageRow(
              icon: status?.transport ?? false ? AppIcons.allowed : AppIcons.error,
              iconColor: status?.transport ?? false ? colors.success : colors.warning,
              title: status?.transport ?? false ? l10n.proactiveTransportReady : l10n.proactiveTransportMissing,
              titleMaxLines: 2,
              subtitle: l10n.proactiveQueued(status?.queued ?? 0),
            ),
            if (status?.storageError != null)
              ListPageRow(
                icon: AppIcons.error,
                iconColor: colors.danger,
                title: l10n.loopStorageError(status!.storageError!),
                titleMaxLines: 3,
              ),
            if (status?.recoveryIssue != null)
              ListPageRow(
                icon: AppIcons.warning,
                iconColor: colors.warning,
                title: l10n.proactiveRecoveryIssue(status!.recoveryIssue!),
                titleMaxLines: 3,
              ),
          ],
        ),
        if (_sessions.timings.isNotEmpty || _sessions.judgeModel != null) ...[
          const SizedBox(height: 24),
          ListPageCard(
            title: '模型速度（最近 10 次中位数）',
            children: [
              ListPageRow(
                icon: AppIcons.chart,
                title: '发球',
                value: timingLabel(_sessions.timings['generate']),
              ),
              ListPageRow(
                icon: AppIcons.chat,
                title: '判球',
                value: timingLabel(_sessions.timings['judge']),
              ),
              ListPageRow(
                icon: AppIcons.send,
                title: '聊天',
                value: timingLabel(_sessions.timings['chat']),
              ),
              ListPageRow(
                icon: AppIcons.settings,
                title: '判球模型',
                subtitle: _sessions.judgeModel == null ? '没单独配，跟会话用同一个（慢的话可以在插件 /config 里换快的）' : null,
                subtitleMaxLines: 2,
                value: _sessions.judgeModel == null
                    ? null
                    : '${_sessions.judgeProvider ?? ''}/${_sessions.judgeModel}',
              ),
            ],
          ),
        ],
        const SizedBox(height: 24),
        ListPageCard(
          title: '诊断',
          children: [
            ListPageRow(
              icon: AppIcons.download,
              title: '导出诊断',
              subtitle: _exporting ? '正在打包…' : '引擎退出、互动账本、配置、各模块状态和无障碍看门狗日志，打成一个 zip 存到「下载」',
              subtitleMaxLines: 3,
              trailing: _exporting
                  ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : null,
              onTap: _exporting ? null : () => unawaited(_exportDiagnostics()),
            ),
            ListPageRow(
              icon: AppIcons.bug,
              title: '前台包名记录（调试）',
              subtitle: _foregroundDebug
                  ? '正在记录每次前台变化（包名、Activity、时间），只记录不拦截；会进「导出诊断」'
                  : '打开后记录每次前台变化（包名、Activity、时间），只记录不拦截；用来定 Gemini 互动舱白名单和微信停留',
              subtitleMaxLines: 3,
              trailing: Switch(
                value: _foregroundDebug,
                onChanged: _foregroundBusy ? null : (v) => unawaited(_setForegroundDebug(v)),
              ),
              onTap: _foregroundBusy ? null : () => unawaited(_setForegroundDebug(!_foregroundDebug)),
            ),
          ],
        ),
        const SizedBox(height: 24),
        ListPageCard(
          title: l10n.proactiveReceipts,
          children: [
            if (_receipts.isEmpty)
              ListPageRow(icon: AppIcons.history, iconColor: colors.textTertiary, title: l10n.proactiveReceiptsEmpty)
            else
              for (final r in _receipts) _ReceiptRow(r),
          ],
        ),
        const SizedBox(height: 24),
      ],
    );
  }
}

/// Why the kernel is holding back, in the person's words.
String proactiveHoldLabel(L10n l10n, String reason) => switch (reason) {
  'dsh_task' || 'dsh' => l10n.proactiveHoldDshTask,
  'dsh_prompt' => l10n.proactiveHoldDshPrompt,
  'locked' => l10n.proactiveHoldLocked,
  'screen_off' => l10n.proactiveHoldScreenOff,
  'protected' => l10n.proactiveHoldProtected,
  'a11y' => l10n.proactiveHoldA11y,
  _ => reason,
};

/// The session row's second line: the target, or why there is none.
String sessionsSubtitle(L10n l10n, ProactiveAgentSessions s) {
  if (!s.ready) return l10n.proactiveSessionUnavailable(s.error ?? '');
  if (s.sessionId != null) return l10n.proactiveSessionChosen(s.sessionId!);
  return l10n.proactiveSessionHint;
}

/// Asks for a number of minutes and returns it, or null when cancelled.
///
/// Stateful on purpose: it owns the [TextEditingController], so the controller outlives the exit
/// animation. Disposing it in the caller right after `showDialog` resolves throws
/// "A TextEditingController was used after being disposed".
class _MinutesDialog extends StatefulWidget {
  const _MinutesDialog({required this.title, required this.initial});

  final String title;
  final int initial;

  @override
  State<_MinutesDialog> createState() => _MinutesDialogState();
}

class _MinutesDialogState extends State<_MinutesDialog> {
  late final TextEditingController _controller = TextEditingController(text: '${widget.initial}');
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final n = int.tryParse(_controller.text.trim());
    if (n == null || n < 1 || n > 10080) {
      setState(() => _error = '1–${context.l10n.proactiveMinutes(10080)}');
      return;
    }
    Navigator.pop(context, n);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: TextField(
        controller: _controller,
        keyboardType: TextInputType.number,
        autofocus: true,
        decoration: InputDecoration(
          helperText: '1–${context.l10n.proactiveMinutes(10080)}',
          errorText: _error,
        ),
        onSubmitted: (_) => _submit(),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(context.l10n.commonCancel)),
        FilledButton(onPressed: _submit, child: Text(context.l10n.commonSave)),
      ],
    );
  }
}

/// The live sessions, plus an explicit "no target" row.
class _SessionList extends StatelessWidget {
  const _SessionList({required this.sessions});

  final ProactiveAgentSessions sessions;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
          child: Text(l10n.proactivePickSession, style: theme.textTheme.titleMedium),
        ),
        Expanded(
          child: ListView(
            children: [
              ListTile(
                leading: const Icon(AppIcons.blocked),
                title: Text(l10n.proactiveSessionNone),
                subtitle: Text(l10n.proactiveSessionNoneHint),
                trailing: sessions.sessionId == null ? const Icon(AppIcons.selected) : null,
                onTap: () => Navigator.pop(context, ''),
              ),
              for (final a in sessions.agents)
                ListTile(
                  leading: Icon(a.idle ? AppIcons.chat : AppIcons.queued),
                  title: Text(a.id),
                  subtitle: Text(a.idle ? l10n.proactiveSessionIdle : l10n.proactiveSessionRunning),
                  trailing: a.id == sessions.sessionId ? const Icon(AppIcons.selected) : null,
                  // A running agent is still selectable: the plugin answers 409 while it is busy
                  // and the page then shows that honestly, rather than hiding the row.
                  onTap: () => Navigator.pop(context, a.id),
                ),
              if (sessions.agents.isEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
                  child: Text(l10n.proactiveSessionEmpty, style: theme.textTheme.bodySmall),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

/// P5: the kernel/plugin's internal codes, in the person's words. `agent_no-topic` etc. must never
/// reach the receipt row raw; those are our bug names, not something he can act on.
String? proactiveReasonLabel(String? reason) {
  if (reason == null || reason.isEmpty) return null;
  final code = reason.startsWith('skip:') ? reason.substring(5) : reason.replaceFirst(RegExp(r'^agent_'), '');
  return switch (code) {
    'no-topic' => '没话可说（旧版本才会有）',
    'agent-busy' || 'in-conversation' || 'desk-busy' => '你们正在聊，这次不插嘴',
    'no-session' => '还没选大肥鱼的会话',
    'no-agent' => '会话没加载起来',
    'deadline' || 'timeout' => '模型太慢，超时了',
    'transport' || 'no_answer' || 'no_transport' => '引擎没连上',
    'surface_busy' || 'start_failed' => '屏幕被别的界面占着，这次没弹出来',
    _ => null,
  };
}

/// `1.2 秒` / `850 毫秒`; null → `还没测到`.
String timingLabel(int? ms) {
  if (ms == null || ms < 0) return '还没测到';
  if (ms < 1000) return '$ms 毫秒';
  return '${(ms / 1000).toStringAsFixed(1)} 秒';
}

/// The outcome of one receipt, in the person's words.
String proactiveOutcomeLabel(L10n l10n, String? outcome) => switch (outcome) {
  'RESPONDED' => l10n.proactiveOutcomeResponded,
  'DISMISSED' => l10n.proactiveOutcomeDismissed,
  'ACKED' => l10n.proactiveOutcomeAcked,
  'IGNORED_SEEN' => l10n.proactiveOutcomeIgnoredSeen,
  'UNCONFIRMED' => l10n.proactiveOutcomeUnconfirmed,
  'NOT_DELIVERED' => l10n.proactiveOutcomeNotDelivered,
  'CANCELLED' => '这次没说',
  null => l10n.proactiveOutcomeOpen,
  _ => outcome,
};

class _ReceiptRow extends StatelessWidget {
  const _ReceiptRow(this.receipt);

  final ProactiveReceipt receipt;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final colors = DshColors.of(context);
    final (icon, color) = switch (receipt.outcome) {
      'RESPONDED' => (AppIcons.allowed, colors.success),
      'DISMISSED' || 'IGNORED_SEEN' => (AppIcons.stopped, colors.warning),
      'NOT_DELIVERED' => (AppIcons.error, colors.danger),
      'UNCONFIRMED' || 'CANCELLED' => (AppIcons.pending, colors.textTertiary),
      _ => (AppIcons.pending, colors.textTertiary),
    };
    // P5: "这次不说" is not FAILED — grey, and with the plugin's own words instead of a code.
    final skipped = receipt.outcome == 'CANCELLED' && (receipt.reason?.startsWith('skip:') ?? false);
    final reason = proactiveReasonLabel(receipt.reason) ?? receipt.reason;
    // The six moments the kernel keeps apart, so "it was sent" and "they saw it" never blur.
    final marks = <String>[
      if (receipt.acceptedAt != null) l10n.proactiveMarkAccepted,
      if (receipt.generatedAt != null) l10n.proactiveMarkGenerated,
      if (receipt.deliveredAt != null) l10n.proactiveMarkDelivered,
      if (receipt.shownAt != null) l10n.proactiveMarkShown,
      if (receipt.respondedAt != null) l10n.proactiveMarkResponded,
    ];
    final surface = switch (receipt.surface) {
      'notification' => l10n.loopSurfaceNotification,
      _ => null,
    };
    final detail = [?surface, if (marks.isNotEmpty) marks.join('  → '), ?reason].join(' · ');
    return ListPageRow(
      icon: icon,
      iconColor: color,
      title: skipped ? '这次不说' : proactiveOutcomeLabel(l10n, receipt.outcome),
      subtitle: detail.isEmpty ? null : detail,
      subtitleMaxLines: 3,
      meta: formatLogTime(receipt.closedAt ?? receipt.deliveredAt ?? receipt.createdAt),
    );
  }
}

