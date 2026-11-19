import 'package:flutter/material.dart';

import '../services/pc_guard_controller.dart';
import '../theme/mist_theme.dart';
import 'ai_coach_pane.dart';
import 'block_shield_overlay.dart';
import 'ledger_pane.dart';
import 'mini_capsule_hud.dart';
import 'overview_pane.dart';
import 'rules_guard_pane.dart';

class DesktopShell extends StatefulWidget {
  const DesktopShell({
    super.key,
    required this.controller,
    required this.darkMode,
    required this.onToggleDarkMode,
  });

  final PcGuardController controller;
  final bool darkMode;
  final VoidCallback onToggleDarkMode;

  @override
  State<DesktopShell> createState() => _DesktopShellState();
}

class _DesktopShellState extends State<DesktopShell> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    if (c.capsuleMode) {
      return MiniCapsuleHud(controller: c);
    }

    return Scaffold(
      body: MistGradientBackdrop(
        child: Stack(
          children: [
            Column(
              children: [
                _buildTopBar(context, c),
                if (c.statusBanner != null) _buildStatusBanner(c),
                Expanded(
                  child: Row(
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(14, 4, 8, 14),
                        child: MistGlassSurface(
                          padding: const EdgeInsets.symmetric(
                            vertical: 12,
                            horizontal: 6,
                          ),
                          child: NavigationRail(
                            backgroundColor: Colors.transparent,
                            selectedIndex: _tab,
                            labelType: NavigationRailLabelType.all,
                            onDestinationSelected: (idx) =>
                                setState(() => _tab = idx),
                            destinations: const [
                              NavigationRailDestination(
                                icon: Icon(Icons.dashboard_outlined),
                                selectedIcon: Icon(Icons.dashboard_rounded),
                                label: Text('今日总览'),
                              ),
                              NavigationRailDestination(
                                icon: Icon(Icons.gpp_good_outlined),
                                selectedIcon: Icon(Icons.gpp_good_rounded),
                                label: Text('规则与严防'),
                              ),
                              NavigationRailDestination(
                                icon: Icon(Icons.psychology_outlined),
                                selectedIcon: Icon(Icons.psychology_rounded),
                                label: Text('AI 裁判'),
                              ),
                              NavigationRailDestination(
                                icon: Icon(Icons.receipt_long_outlined),
                                selectedIcon: Icon(Icons.receipt_long_rounded),
                                label: Text('账本与日志'),
                              ),
                            ],
                          ),
                        ),
                      ),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(6, 4, 14, 14),
                          child: switch (_tab) {
                            0 => OverviewPane(controller: c),
                            1 => RulesGuardPane(controller: c),
                            2 => AiCoachPane(controller: c),
                            _ => LedgerPane(controller: c),
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            if (c.activeBlock != null) BlockShieldOverlay(controller: c),
          ],
        ),
      ),
    );
  }

  Widget _buildTopBar(BuildContext context, PcGuardController c) {
    final now = c.currentNowUtcMs;
    final armed = c.rules.strictGuardArmed;
    final inFocus = c.focusSession.isActiveAt(now);
    final remainEntMin =
        (c.rules.dailyEntertainmentLimitMin - c.ledger.entertainmentMinutes)
            .clamp(0.0, 999.0);

    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
      child: MistGlassSurface(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          children: [
            const Text('🐟', style: TextStyle(fontSize: 20)),
            const SizedBox(width: 8),
            const Text(
              '大肥鱼 · PC 严防哨兵',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Wrap(
                spacing: 8,
                runSpacing: 4,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    decoration: BoxDecoration(
                      color: (armed ? MistColors.emerald : MistColors.amber)
                          .withValues(alpha: 0.16),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      armed ? '严防已武装 · Watchdog 互保' : '未武装严防（配置态）',
                      style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    decoration: BoxDecoration(
                      color: MistColors.cobalt.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      inFocus
                          ? '专注锁剩余 ${(c.focusSession.remainingSecondsAt(now) / 60).ceil()}m'
                          : '娱乐余 ${remainEntMin.toStringAsFixed(0)}/${c.rules.dailyEntertainmentLimitMin}m',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 12),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            IconButton(
              tooltip: '切换亮色 / 暗黑 Mist 玻璃主题',
              onPressed: widget.onToggleDarkMode,
              icon: Icon(
                widget.darkMode ? Icons.light_mode_rounded : Icons.dark_mode_rounded,
              ),
            ),
            OutlinedButton.icon(
              onPressed: () => c.setCapsuleMode(true),
              icon: const Icon(Icons.picture_in_picture_alt_rounded, size: 17),
              label: const Text('迷你胶囊'),
            ),
            const SizedBox(width: 8),
            if (!armed)
              FilledButton.icon(
                onPressed: c.armStrictGuard,
                icon: const Icon(Icons.verified_user_rounded, size: 17),
                label: const Text('开启严防守护'),
              )
            else if (!inFocus)
              FilledButton.icon(
                onPressed: () => c.startFocusSession(
                  minutes: 25,
                  taskName: '25分钟即时专注局',
                ),
                icon: const Icon(Icons.lock_clock_rounded, size: 17),
                label: const Text('快速专注 25m'),
              )
            else
              FilledButton.tonalIcon(
                onPressed: () => c.extendFocusSession(15),
                icon: const Icon(Icons.add_alarm_rounded, size: 17),
                label: const Text('专注 +15m'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBanner(PcGuardController c) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 0, 14, 6),
      child: MistGlassSurface(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        radius: 12,
        borderColor: MistColors.amber.withValues(alpha: 0.45),
        child: Row(
          children: [
            const Icon(Icons.info_outline_rounded, size: 18, color: MistColors.amber),
            const SizedBox(width: 8),
            Expanded(
              child: Text(c.statusBanner ?? '', style: const TextStyle(fontSize: 12.5)),
            ),
            TextButton(
              onPressed: c.clearStatusBanner,
              child: const Text('知道了'),
            ),
          ],
        ),
      ),
    );
  }
}
