import 'package:flutter/material.dart';

import '../models/guard_models.dart';
import '../services/bridge_local_sync.dart';
import '../services/pc_guard_controller.dart';
import '../services/rule_engine.dart';
import '../theme/mist_theme.dart';

class OverviewPane extends StatefulWidget {
  const OverviewPane({super.key, required this.controller});

  final PcGuardController controller;

  @override
  State<OverviewPane> createState() => _OverviewPaneState();
}

class _OverviewPaneState extends State<OverviewPane> {
  final _taskCtrl = TextEditingController();
  bool? _hardLockOverride;

  @override
  void dispose() {
    _taskCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final now = c.currentNowUtcMs;
    final inFocus = c.focusSession.isActiveAt(now);
    final inCurfew = RuleEngine.isInNightCurfew(c.rules, now);
    final limitMin = c.rules.dailyEntertainmentLimitMin;
    final entMin = c.ledger.entertainmentMinutes;
    final remainEntMin = (limitMin - entMin).clamp(0.0, 999.0);
    final entRatio = limitMin <= 0 ? 1.0 : (entMin / limitMin).clamp(0.0, 1.0);
    final hardLockValue = _hardLockOverride ?? c.rules.focusHardLockDefault;

    final sample = c.latestSample;
    final topApps = (c.ledger.perAppSeconds.entries.toList()
          ..sort((a, b) => b.value.compareTo(a.value)))
        .take(8)
        .toList();

    return ListView(
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: MistGlassSurface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.timer_rounded, color: MistColors.cobalt),
                        const SizedBox(width: 8),
                        Text(
                          '今日时长账本 (${c.ledger.dayKey})',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 10,
                      runSpacing: 8,
                      children: [
                        _metricChip(
                          '专注工作',
                          '${c.ledger.workMinutes.toStringAsFixed(1)} min',
                          MistColors.emerald,
                        ),
                        _metricChip(
                          '灰区浏览',
                          '${c.ledger.dualMinutes.toStringAsFixed(1)} min',
                          MistColors.cobalt,
                        ),
                        _metricChip(
                          '娱乐已用',
                          '${entMin.toStringAsFixed(1)} / $limitMin min',
                          entRatio >= 1.0 ? MistColors.crimson : MistColors.amber,
                        ),
                        _metricChip(
                          '离开键盘',
                          '${c.ledger.afkMinutes.toStringAsFixed(1)} min',
                          MistColors.inkMuted,
                        ),
                      ],
                    ),
                    const SizedBox(height: 14),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            entRatio >= 1.0
                                ? '今日娱乐额度已耗尽（后续娱乐将被立即拦截）'
                                : '娱乐额度剩余 ${remainEntMin.toStringAsFixed(1)} 分钟',
                            style: TextStyle(
                              fontSize: 12.5,
                              fontWeight: FontWeight.w600,
                              color: entRatio >= 1.0 ? MistColors.crimson : null,
                            ),
                          ),
                        ),
                        Text(
                          '拦截 ${c.ledger.blocksTriggered} 次 · 复活 ${c.ledger.watchdogRestarts} 次',
                          style: const TextStyle(fontSize: 12),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(999),
                      child: LinearProgressIndicator(
                        value: entRatio,
                        minHeight: 8,
                        color: entRatio >= 1.0 ? MistColors.crimson : MistColors.amber,
                        backgroundColor: MistColors.cobalt.withValues(alpha: 0.12),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: MistGlassSurface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          inFocus ? Icons.lock_clock_rounded : Icons.play_circle_outline_rounded,
                          color: inFocus ? MistColors.crimson : MistColors.cobalt,
                        ),
                        const SizedBox(width: 8),
                        Text(
                          inFocus ? '专注锁进行中' : '开启电脑专注锁',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const Spacer(),
                        if (inCurfew)
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                            decoration: BoxDecoration(
                              color: MistColors.crimson.withValues(alpha: 0.14),
                              borderRadius: BorderRadius.circular(999),
                            ),
                            child: const Text(
                              '深夜宵禁生效中',
                              style: TextStyle(fontSize: 11.5, color: MistColors.crimson, fontWeight: FontWeight.w700),
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    if (inFocus) ...[
                      Text(
                        '当前任务：${c.focusSession.taskName}',
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        '剩余倒计时：${_formatMmSs(c.focusSession.remainingSecondsAt(now))}',
                        style: const TextStyle(
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
                          color: MistColors.cobalt,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        c.focusSession.hardLocked
                            ? '铁锁模式已生效：倒计时结束前禁止解除严防或退出程序，只能延长。'
                            : '标准专注锁：期间禁止一切未批准娱乐应用。',
                        style: const TextStyle(fontSize: 12),
                      ),
                      const SizedBox(height: 10),
                      Wrap(
                        spacing: 8,
                        children: [
                          FilledButton.tonalIcon(
                            onPressed: () => c.extendFocusSession(15),
                            icon: const Icon(Icons.add_alarm_rounded, size: 18),
                            label: const Text('延长 +15 分钟'),
                          ),
                          OutlinedButton(
                            onPressed: () => c.extendFocusSession(30),
                            child: const Text('延长 +30 分钟'),
                          ),
                        ],
                      ),
                    ] else ...[
                      TextField(
                        controller: _taskCtrl,
                        decoration: const InputDecoration(
                          isDense: true,
                          labelText: '本轮唯一专注任务（如：行测资料分析 30 题 / 改完登录模块）',
                          border: OutlineInputBorder(),
                        ),
                      ),
                      const SizedBox(height: 6),
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        dense: true,
                        title: const Text('铁锁模式（倒计时结束前彻底禁止退出/解除严防）', style: TextStyle(fontSize: 12.5)),
                        value: hardLockValue,
                        onChanged: (v) => setState(() => _hardLockOverride = v),
                      ),
                      const SizedBox(height: 4),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          for (final mins in const [25, 45, 60, 90])
                            FilledButton.tonal(
                              onPressed: () => c.startFocusSession(
                                minutes: mins,
                                taskName: _taskCtrl.text,
                                hardLocked: hardLockValue,
                              ),
                              child: Text('锁 $mins 分钟'),
                            ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        _buildBridgeSyncCard(context, c, now),
        const SizedBox(height: 12),
        MistGlassSurface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.radar_rounded, color: MistColors.cobalt),
                  const SizedBox(width: 8),
                  Text('实时前台与分类监测', style: Theme.of(context).textTheme.titleMedium),
                  const Spacer(),
                  Text(
                    c.sensorRunning ? '每 2 秒实时巡检中' : '巡检已暂停',
                    style: const TextStyle(fontSize: 12),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              if (sample == null)
                const Text('正在采集首个前台窗口快照...')
              else ...[
                Wrap(
                  spacing: 10,
                  runSpacing: 8,
                  children: [
                    _metricChip(
                      '当前分类',
                      sample.isAfk ? '离开键盘 (AFK)' : sample.classification.tier.label,
                      sample.isAfk
                          ? MistColors.amber
                          : switch (sample.classification.tier) {
                              ActivityTier.work || ActivityTier.neverBlock => MistColors.emerald,
                              ActivityTier.blocked => MistColors.crimson,
                              ActivityTier.dual => MistColors.cobalt,
                            },
                    ),
                    Chip(label: Text('进程：${sample.snapshot.app.isEmpty ? "unknown" : sample.snapshot.app}')),
                    Chip(label: Text('空闲：${sample.snapshot.idleSec}s')),
                    if (sample.classification.isExplicitGreyZone)
                      const Chip(label: Text('灰区内容站点（可由 AI 自动判别）')),
                  ],
                ),
                if (sample.snapshot.title.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text(
                    '窗口标题：${sample.snapshot.title}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 13),
                  ),
                ],
              ],
              if (c.activeLeases.isNotEmpty) ...[
                const Divider(height: 20),
                const Text('当前生效通行证：', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700)),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    for (final lease in c.activeLeases)
                      Chip(
                        backgroundColor: MistColors.emerald.withValues(alpha: 0.14),
                        label: Text(
                          '${lease.app == "*" ? "全应用急事" : lease.app} · 剩 ${((lease.expiresAtUtcMs - now) / 60000).ceil().clamp(1, 99)}m (${lease.reason})',
                          style: const TextStyle(fontSize: 12),
                        ),
                      ),
                  ],
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 12),
        MistGlassSurface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('今日 Top 应用时长分布', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 10),
              if (topApps.isEmpty)
                const Text('暂无累计时长记录，切到其他窗口后将自动统计。', style: TextStyle(fontSize: 13))
              else
                for (final entry in topApps)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Row(
                      children: [
                        SizedBox(
                          width: 180,
                          child: Text(
                            entry.key,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                        ),
                        Expanded(
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(999),
                            child: LinearProgressIndicator(
                              value: topApps.first.value <= 0
                                  ? 0
                                  : (entry.value / topApps.first.value).clamp(0.0, 1.0),
                              minHeight: 7,
                              backgroundColor: MistColors.cobalt.withValues(alpha: 0.10),
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        SizedBox(
                          width: 78,
                          child: Text(
                            _formatDurationSec(entry.value),
                            textAlign: TextAlign.right,
                            style: const TextStyle(fontSize: 12.5),
                          ),
                        ),
                      ],
                    ),
                  ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildBridgeSyncCard(
    BuildContext context,
    PcGuardController c,
    int now,
  ) {
    final syncedOnline = c.phoneLockSyncEnabled && c.bridgeState.isSyncedOnline;
    final ageSec = c.bridgeState.syncAgeSecondsAt(now);
    final statusText = !c.phoneLockSyncEnabled
        ? '独立运行（已关闭手机跟锁）'
        : syncedOnline
            ? '手机桥：在线（同步于 $ageSec 秒前）'
            : '独立运行（未连本机桥）';

    final statusColor = syncedOnline
        ? switch (c.bridgeState.directive) {
            PhoneSyncDirective.tighten => MistColors.crimson,
            PhoneSyncDirective.relax => MistColors.emerald,
            PhoneSyncDirective.none => MistColors.cobalt,
          }
        : MistColors.inkMuted;

    return MistGlassSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                syncedOnline
                    ? Icons.phonelink_lock_rounded
                    : Icons.phonelink_off_rounded,
                color: syncedOnline ? MistColors.cobalt : MistColors.inkMuted,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  statusText,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              if (syncedOnline && c.bridgeState.reason.isNotEmpty)
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: statusColor.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(
                      color: statusColor.withValues(alpha: 0.35),
                    ),
                  ),
                  child: Text(
                    c.bridgeState.reason,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      color: statusColor,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 6),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            title: const Text(
              '自愿跟锁手机（127.0.0.1:8788 本机桥联动）',
              style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600),
            ),
            subtitle: const Text(
              '开启后每 20 秒同步手机封印/默认验收/深夜锁/晚间封顶并同步放行花余额放风/紧急解除/恢复日；桥离线时自动退回 PC 独立规则',
              style: TextStyle(fontSize: 12),
            ),
            value: c.phoneLockSyncEnabled,
            onChanged: (v) => c.setPhoneLockSyncEnabled(v),
          ),
        ],
      ),
    );
  }

  static Widget _metricChip(String label, String value, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.13),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.28)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label, style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.w700)),
          const SizedBox(height: 2),
          Text(value, style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }

  static String _formatMmSs(int totalSec) {
    final s = totalSec.clamp(0, 86400);
    final mm = (s ~/ 60).toString().padLeft(2, '0');
    final ss = (s % 60).toString().padLeft(2, '0');
    return '$mm:$ss';
  }

  static String _formatDurationSec(int sec) {
    if (sec < 60) return '${sec}s';
    final min = sec ~/ 60;
    final rem = sec % 60;
    return '${min}m ${rem}s';
  }
}
