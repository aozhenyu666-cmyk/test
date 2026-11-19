import 'package:flutter/material.dart';

import '../models/guard_models.dart';
import '../services/pc_guard_controller.dart';
import '../theme/mist_theme.dart';

/// Compact always-on-top capsule mode shown at top-right when coding or studying.
class MiniCapsuleHud extends StatelessWidget {
  const MiniCapsuleHud({super.key, required this.controller});

  final PcGuardController controller;

  @override
  Widget build(BuildContext context) {
    final now = controller.currentNowUtcMs;
    final sample = controller.latestSample;
    final tier = sample?.classification.tier ?? ActivityTier.dual;
    final isAfk = sample?.isAfk ?? false;
    final statusColor = isAfk
        ? MistColors.amber
        : switch (tier) {
            ActivityTier.work || ActivityTier.neverBlock => MistColors.emerald,
            ActivityTier.blocked => MistColors.crimson,
            ActivityTier.dual => MistColors.cobalt,
          };

    final inFocus = controller.focusSession.isActiveAt(now);
    final remainEntMin =
        (controller.rules.dailyEntertainmentLimitMin -
                controller.ledger.entertainmentMinutes)
            .clamp(0, 999)
            .toStringAsFixed(0);
    final subtitle = inFocus
        ? '专注锁剩余 ${(controller.focusSession.remainingSecondsAt(now) / 60).ceil()}m · ${controller.focusSession.taskName}'
        : (sample != null && sample.snapshot.app.isNotEmpty)
            ? '${sample.snapshot.app} | 娱乐余 ${remainEntMin}m'
            : '大肥鱼 PC 严防待命 · 娱乐余 ${remainEntMin}m';

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: MistGradientBackdrop(
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: MistGlassSurface(
            radius: 18,
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            child: Row(
              children: [
                Container(
                  width: 10,
                  height: 10,
                  decoration: BoxDecoration(
                    color: statusColor,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${isAfk ? "离开键盘" : tier.label} · 专注 ${controller.ledger.workMinutes.toStringAsFixed(1)}m',
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      Text(
                        subtitle,
                        style: const TextStyle(fontSize: 11.5),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: () => controller.setCapsuleMode(false),
                  child: const Text('展开', style: TextStyle(fontSize: 12)),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
