import 'package:flutter/material.dart';

import '../services/dsh_bridge.dart';
import '../theme/mist_theme.dart';
import 'chat_pane.dart';
import 'dice_drill_card.dart';
import 'hub_pane.dart';
import 'mini_capsule_hud.dart';
import 'pc_sensor_pane.dart';
import 'wall_drop_pane.dart';

class DesktopShell extends StatefulWidget {
  const DesktopShell({
    super.key,
    required this.controller,
    required this.darkMode,
    required this.onToggleDarkMode,
  });

  final DesktopController controller;
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
                Expanded(
                  child: Row(
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(14, 4, 8, 14),
                        child: MistGlassSurface(
                          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 6),
                          child: NavigationRail(
                            backgroundColor: Colors.transparent,
                            selectedIndex: _tab,
                            labelType: NavigationRailLabelType.all,
                            onDestinationSelected: (idx) => setState(() => _tab = idx),
                            destinations: const [
                              NavigationRailDestination(
                                icon: Icon(Icons.chat_bubble_outline_rounded),
                                selectedIcon: Icon(Icons.chat_bubble_rounded),
                                label: Text('对话'),
                              ),
                              NavigationRailDestination(
                                icon: Icon(Icons.dashboard_outlined),
                                selectedIcon: Icon(Icons.dashboard_rounded),
                                label: Text('中枢·六骰'),
                              ),
                              NavigationRailDestination(
                                icon: Icon(Icons.shield_outlined),
                                selectedIcon: Icon(Icons.shield_rounded),
                                label: Text('墙与交付'),
                              ),
                              NavigationRailDestination(
                                icon: Icon(Icons.radar_rounded),
                                selectedIcon: Icon(Icons.radar_rounded),
                                label: Text('PC 哨兵'),
                              ),
                            ],
                          ),
                        ),
                      ),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(6, 4, 14, 14),
                          child: switch (_tab) {
                            0 => ChatPane(controller: c),
                            1 => HubPane(controller: c),
                            2 => WallDropPane(controller: c),
                            _ => PcSensorPane(controller: c),
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            if (c.sliceModalPending) _buildSliceGuardOverlay(context, c),
          ],
        ),
      ),
    );
  }

  Widget _buildTopBar(BuildContext context, DesktopController c) {
    final connected = c.hasHttpCompanion || c.hasDshClient;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
      child: MistGlassSurface(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          children: [
            const Text('🐟', style: TextStyle(fontSize: 20)),
            const SizedBox(width: 8),
            const Text('DSH Go 大肥鱼 · PC', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            const SizedBox(width: 14),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(
                color: (connected ? MistColors.emerald : MistColors.amber).withValues(alpha: 0.16),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                c.connectionStatus,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 12),
              ),
            ),
            const Spacer(),
            IconButton(
              tooltip: '切换亮色 / 暗黑 Mist 玻璃主题',
              onPressed: widget.onToggleDarkMode,
              icon: Icon(widget.darkMode ? Icons.light_mode_rounded : Icons.dark_mode_rounded),
            ),
            OutlinedButton.icon(
              onPressed: () => c.setCapsuleMode(true),
              icon: const Icon(Icons.picture_in_picture_alt_rounded, size: 17),
              label: const Text('迷你胶囊'),
            ),
            const SizedBox(width: 8),
            FilledButton.icon(
              onPressed: () => _openConnectDialog(context, c),
              icon: const Icon(Icons.link_rounded, size: 17),
              label: const Text('连接手机 / Remote'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSliceGuardOverlay(BuildContext context, DesktopController c) {
    return Positioned.fill(
      child: Container(
        color: Colors.black.withValues(alpha: 0.56),
        alignment: Alignment.center,
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 740),
          child: MistGlassSurface(
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.pause_presentation_rounded, color: MistColors.crimson),
                      const SizedBox(width: 8),
                      Text('大肥鱼 PC 专注拦截 / 切片检验', style: Theme.of(context).textTheme.titleLarge),
                      const Spacer(),
                      TextButton(
                        onPressed: c.dismissSliceModal,
                        child: const Text('完成反思并继续'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(c.slicePromptReason, style: const TextStyle(fontSize: 13.5)),
                  const SizedBox(height: 14),
                  SixDiceDrillCard(controller: c),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _openConnectDialog(BuildContext context, DesktopController c) async {
    final urlCtrl = TextEditingController(text: c.companionUrl);
    final tokenCtrl = TextEditingController(text: c.launchToken);
    final serverCtrl = TextEditingController(text: c.remoteServerUrl);
    final emailCtrl = TextEditingController();
    final passCtrl = TextEditingController();

    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('连接到大肥鱼 / DSH Host'),
        content: SizedBox(
          width: 540,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('方式一：Tailscale / 局域网直连（推荐与手机同局域网）', style: TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                TextField(
                  controller: urlCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Host / Companion URL (如 http://100.x.y.z:48912)',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: tokenCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Launch Token（如 DSH Web 需要可填写，伴侣端可留空）',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerRight,
                  child: FilledButton(
                    onPressed: () async {
                      await c.connectCompanionEndpoint(urlCtrl.text, token: tokenCtrl.text);
                      if (ctx.mounted) Navigator.of(ctx).pop();
                    },
                    child: const Text('直连 Host'),
                  ),
                ),
                const Divider(height: 24),
                const Text('方式二：Pure-Dart Remote 中继 (Noise_IK 端到端加密)', style: TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                TextField(
                  controller: serverCtrl,
                  decoration: const InputDecoration(labelText: 'Remote Server URL', border: OutlineInputBorder()),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: emailCtrl,
                  decoration: const InputDecoration(labelText: '账号 Email', border: OutlineInputBorder()),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: passCtrl,
                  obscureText: true,
                  decoration: const InputDecoration(labelText: '密码', border: OutlineInputBorder()),
                ),
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerRight,
                  child: OutlinedButton(
                    onPressed: () async {
                      await c.loginRemotePassword(serverCtrl.text, emailCtrl.text, passCtrl.text);
                    },
                    child: const Text('登录 Remote 账号'),
                  ),
                ),
                if (c.remoteHosts.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  const Text('可用主机：', style: TextStyle(fontWeight: FontWeight.w600)),
                  for (final h in c.remoteHosts)
                    ListTile(
                      dense: true,
                      title: Text('${h.name} (${h.platform})'),
                      subtitle: Text(h.online ? '在线' : '离线'),
                      trailing: TextButton(
                        onPressed: () async {
                          await c.connectRemoteHost(h);
                          if (ctx.mounted) Navigator.of(ctx).pop();
                        },
                        child: const Text('连这台'),
                      ),
                    ),
                ],
              ],
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('关闭')),
        ],
      ),
    );
  }
}
