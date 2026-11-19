import 'package:flutter/material.dart';

import '../services/pc_guard_controller.dart';
import '../theme/mist_theme.dart';

class LedgerPane extends StatelessWidget {
  const LedgerPane({super.key, required this.controller});

  final PcGuardController controller;

  @override
  Widget build(BuildContext context) {
    final l = controller.ledger;
    final apps = l.perAppSeconds.entries.toList()
      ..sort((a, b) => b.value.compareTo(a.value));

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          flex: 4,
          child: MistGlassSurface(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('全量应用时长账本 (${l.dayKey})', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    Chip(label: Text('工作 ${l.workMinutes.toStringAsFixed(1)}m')),
                    Chip(label: Text('灰区 ${l.dualMinutes.toStringAsFixed(1)}m')),
                    Chip(label: Text('娱乐 ${l.entertainmentMinutes.toStringAsFixed(1)}m')),
                    Chip(label: Text('AFK ${l.afkMinutes.toStringAsFixed(1)}m')),
                  ],
                ),
                const Divider(height: 20),
                Expanded(
                  child: apps.isEmpty
                      ? const Center(child: Text('暂无应用统计记录'))
                      : ListView.builder(
                          itemCount: apps.length,
                          itemBuilder: (_, idx) {
                            final e = apps[idx];
                            final mins = e.value ~/ 60;
                            final secs = e.value % 60;
                            return ListTile(
                              dense: true,
                              contentPadding: EdgeInsets.zero,
                              title: Text(e.key, style: const TextStyle(fontWeight: FontWeight.w600)),
                              trailing: Text('${mins}m ${secs}s'),
                            );
                          },
                        ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          flex: 6,
          child: MistGlassSurface(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text('执法、Watchdog 与防绕过日志', style: Theme.of(context).textTheme.titleMedium),
                    const Spacer(),
                    Text(
                      '拦截 ${l.blocksTriggered} · 重生 ${l.watchdogRestarts} · 急事 ${l.emergencyPassesUsed}/2',
                      style: const TextStyle(fontSize: 12),
                    ),
                  ],
                ),
                const Divider(height: 20),
                Expanded(
                  child: l.eventLogs.isEmpty
                      ? const Center(child: Text('暂无执法或防绕过事件记录'))
                      : ListView.builder(
                          itemCount: l.eventLogs.length,
                          itemBuilder: (_, idx) {
                            final log = l.eventLogs[idx];
                            final color = switch (log.kind) {
                              'block' => MistColors.crimson,
                              'watchdog_restart' => MistColors.amber,
                              'ai_lease' => MistColors.emerald,
                              'exit_attempt' => MistColors.crimson,
                              _ => MistColors.cobalt,
                            };
                            return Container(
                              margin: const EdgeInsets.only(bottom: 8),
                              padding: const EdgeInsets.all(10),
                              decoration: BoxDecoration(
                                color: color.withValues(alpha: 0.08),
                                borderRadius: BorderRadius.circular(10),
                              ),
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    _fmtTime(log.atUtcMs),
                                    style: TextStyle(
                                      fontSize: 11.5,
                                      fontFamily: 'monospace',
                                      color: color,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                  const SizedBox(width: 10),
                                  Expanded(
                                    child: Text(log.detail, style: const TextStyle(fontSize: 12.5)),
                                  ),
                                ],
                              ),
                            );
                          },
                        ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  static String _fmtTime(int utcMs) {
    final bj = DateTime.fromMillisecondsSinceEpoch(
      utcMs + 8 * 3600 * 1000,
      isUtc: true,
    );
    final hh = bj.hour.toString().padLeft(2, '0');
    final mm = bj.minute.toString().padLeft(2, '0');
    final ss = bj.second.toString().padLeft(2, '0');
    return '$hh:$mm:$ss';
  }
}
