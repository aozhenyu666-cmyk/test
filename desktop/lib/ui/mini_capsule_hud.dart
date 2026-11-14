import 'package:flutter/material.dart';

import '../services/dsh_bridge.dart';
import '../services/pc_sensor.dart';
import '../theme/mist_theme.dart';

/// Compact always-on-top capsule mode shown at top-right when coding or studying.
class MiniCapsuleHud extends StatelessWidget {
  const MiniCapsuleHud({super.key, required this.controller});

  final DesktopController controller;

  @override
  Widget build(BuildContext context) {
    final s = controller.latestSample;
    final cat = s?.category ?? PcActivityCategory.other;
    final statusColor = cat == PcActivityCategory.entertainment
        ? MistColors.crimson
        : cat.isFocus
            ? MistColors.emerald
            : MistColors.cobalt;

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
                  decoration: BoxDecoration(color: statusColor, shape: BoxShape.circle),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${cat.label} · 专注 ${(s?.focusMinutes ?? 0).toStringAsFixed(1)}m',
                        style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      Text(
                        controller.enableVideoSliceTimer
                            ? '3 分钟切片倒计时：${controller.videoSliceSecondsLeft}s'
                            : (s?.app.isNotEmpty == true ? '${s!.app} | ${s.title}' : 'DSH Go 大肥鱼哨兵待命'),
                        style: const TextStyle(fontSize: 11.5),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: '立即触发反思卡片',
                  onPressed: controller.triggerManualMediaPause,
                  icon: const Icon(Icons.casino_rounded, size: 20),
                ),
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
