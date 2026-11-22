import 'dart:async';

import 'package:flutter/material.dart';

import '../services/pc_guard_controller.dart';
import '../theme/mist_theme.dart';

/// Full-screen Mist Glass enforcement shield. No one-tap skip button exists.
class BlockShieldOverlay extends StatefulWidget {
  const BlockShieldOverlay({super.key, required this.controller});

  final PcGuardController controller;

  @override
  State<BlockShieldOverlay> createState() => _BlockShieldOverlayState();
}

class _BlockShieldOverlayState extends State<BlockShieldOverlay> {
  final _aiClaimCtrl = TextEditingController();
  final _emergencyReasonCtrl = TextEditingController();
  String _feedback = '';

  Timer? _holdTimer;
  int _holdStartMs = 0;
  double _holdProgress = 0.0;
  static const int _holdRequiredMs = 5000;

  @override
  void dispose() {
    _holdTimer?.cancel();
    _aiClaimCtrl.dispose();
    _emergencyReasonCtrl.dispose();
    super.dispose();
  }

  void _startEmergencyHold() {
    if (_emergencyReasonCtrl.text.trim().length < 6) {
      setState(() => _feedback = '请先填写至少 6 个字的具体急事原因，再长按 5 秒启用放行');
      return;
    }
    _holdStartMs = DateTime.now().millisecondsSinceEpoch;
    _holdProgress = 0.0;
    _holdTimer?.cancel();
    _holdTimer = Timer.periodic(const Duration(milliseconds: 100), (t) async {
      final elapsed = DateTime.now().millisecondsSinceEpoch - _holdStartMs;
      final prog = (elapsed / _holdRequiredMs).clamp(0.0, 1.0);
      if (!mounted) {
        t.cancel();
        return;
      }
      setState(() => _holdProgress = prog);
      if (elapsed >= _holdRequiredMs) {
        t.cancel();
        _holdTimer = null;
        _holdStartMs = 0;
        final res = await widget.controller.grantEmergencyPass(
          reason: _emergencyReasonCtrl.text,
        );
        if (!mounted) return;
        setState(() {
          _holdProgress = 0.0;
          _feedback = res.message;
        });
      }
    });
  }

  void _cancelEmergencyHold() {
    if (_holdTimer != null) {
      _holdTimer?.cancel();
      _holdTimer = null;
      if (mounted) {
        setState(() {
          _holdProgress = 0.0;
          if (_holdStartMs > 0) {
            _feedback = '按住未满 5 秒，未授予急事放行。';
          }
          _holdStartMs = 0;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final block = c.activeBlock;
    if (block == null) return const SizedBox.shrink();

    final remainEmergency =
        (PcGuardController.maxEmergencyPassesPerDay - c.ledger.emergencyPassesUsed)
            .clamp(0, PcGuardController.maxEmergencyPassesPerDay);

    return Positioned.fill(
      child: Container(
        color: Colors.black.withValues(alpha: 0.72),
        alignment: Alignment.center,
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 700),
          child: MistGlassSurface(
            borderColor: MistColors.crimson.withValues(alpha: 0.55),
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.gpp_bad_rounded, color: MistColors.crimson, size: 28),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          '大肥鱼 PC 严防拦截盾',
                          style: Theme.of(context).textTheme.titleLarge?.copyWith(
                                fontWeight: FontWeight.w800,
                              ),
                        ),
                      ),
                      Chip(
                        backgroundColor: MistColors.crimson.withValues(alpha: 0.14),
                        label: Text(
                          block.actionKind == 'terminateAndShield'
                              ? '已结束娱乐进程'
                              : '已最小化娱乐窗口',
                          style: const TextStyle(
                            fontSize: 12,
                            color: MistColors.crimson,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: MistColors.crimson.withValues(alpha: 0.08),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '目标进程：${block.app}',
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        if (block.title.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text('窗口标题：${block.title}', style: const TextStyle(fontSize: 13)),
                        ],
                        const SizedBox(height: 6),
                        Text('拦截依据：${block.reason}', style: const TextStyle(fontSize: 13.5)),
                        if (block.actionHint.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text('AI 建议：${block.actionHint}', style: const TextStyle(fontSize: 12.5)),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 14),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () async {
                        final res = await c.dismissShieldAfterReturningToWork();
                        if (!mounted) return;
                        if (!res.ok) setState(() => _feedback = res.message);
                      },
                      icon: const Icon(Icons.work_outline_rounded, size: 18),
                      label: const Text('我已关闭娱乐，回到工作'),
                    ),
                  ),
                  if (block.canRequestAiPass) ...[
                    const Divider(height: 24),
                    const Text(
                      '向 AI 申请限时学习通行证（结合当前窗口标题实时审核）',
                      style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _aiClaimCtrl,
                            decoration: const InputDecoration(
                              isDense: true,
                              hintText: '说明你要在这个页面学习/查阅什么（如：看花生十三资料分析第4讲）',
                              border: OutlineInputBorder(),
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        FilledButton.tonal(
                          onPressed: c.aiRequestBusy
                              ? null
                              : () async {
                                  final res = await c.requestAiStudyPass(
                                    claim: _aiClaimCtrl.text,
                                  );
                                  if (!mounted) return;
                                  setState(() => _feedback = res.message);
                                },
                          child: Text(c.aiRequestBusy ? '审核中...' : 'AI 审批放行'),
                        ),
                      ],
                    ),
                  ],
                  const Divider(height: 24),
                  Text(
                    '急事临时放行 5 分钟（今日剩 $remainEmergency/${PcGuardController.maxEmergencyPassesPerDay} 次，需填原因并按住 5 秒）',
                    style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _emergencyReasonCtrl,
                    enabled: remainEmergency > 0,
                    decoration: const InputDecoration(
                      isDense: true,
                      hintText: '填写急事原因（至少 6 个字）',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Listener(
                    onPointerDown: remainEmergency > 0 ? (_) => _startEmergencyHold() : null,
                    onPointerUp: (_) => _cancelEmergencyHold(),
                    onPointerCancel: (_) => _cancelEmergencyHold(),
                    child: Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 14),
                      decoration: BoxDecoration(
                        color: remainEmergency > 0
                            ? MistColors.amber.withValues(alpha: 0.16)
                            : Colors.grey.withValues(alpha: 0.12),
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(
                          color: remainEmergency > 0
                              ? MistColors.amber.withValues(alpha: 0.5)
                              : Colors.grey.withValues(alpha: 0.3),
                        ),
                      ),
                      child: Column(
                        children: [
                          Text(
                            remainEmergency <= 0
                                ? '今日急事放行次数已用完'
                                : _holdProgress > 0
                                    ? '继续按住 ${(5.0 * (1 - _holdProgress)).toStringAsFixed(1)} 秒确认放行...'
                                    : '按住此处 5 秒启用 5 分钟急事放行',
                            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
                          ),
                          if (_holdProgress > 0) ...[
                            const SizedBox(height: 6),
                            LinearProgressIndicator(value: _holdProgress),
                          ],
                        ],
                      ),
                    ),
                  ),
                  if (_feedback.isNotEmpty) ...[
                    const SizedBox(height: 10),
                    Text(
                      _feedback,
                      style: const TextStyle(
                        color: MistColors.crimson,
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
