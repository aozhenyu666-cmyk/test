import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/guard_models.dart';
import '../services/pc_guard_controller.dart';
import '../services/rule_engine.dart';
import '../theme/mist_theme.dart';

class RulesGuardPane extends StatefulWidget {
  const RulesGuardPane({super.key, required this.controller});

  final PcGuardController controller;

  @override
  State<RulesGuardPane> createState() => _RulesGuardPaneState();
}

class _RulesGuardPaneState extends State<RulesGuardPane> {
  late final TextEditingController _limitCtrl;
  late final TextEditingController _cooldownCtrl;
  late final TextEditingController _curfewStartCtrl;
  late final TextEditingController _curfewEndCtrl;
  late final TextEditingController _workAppsCtrl;
  late final TextEditingController _workKwCtrl;
  late final TextEditingController _dualSitesCtrl;
  late final TextEditingController _blockedAppsCtrl;
  late final TextEditingController _blockedKwCtrl;

  bool _nightCurfewEnabled = true;
  bool _autoStartEnabled = true;
  bool _focusHardLockDefault = true;
  String _saveFeedback = '';
  bool _dirty = false;

  @override
  void initState() {
    super.initState();
    _limitCtrl = TextEditingController();
    _cooldownCtrl = TextEditingController();
    _curfewStartCtrl = TextEditingController();
    _curfewEndCtrl = TextEditingController();
    _workAppsCtrl = TextEditingController();
    _workKwCtrl = TextEditingController();
    _dualSitesCtrl = TextEditingController();
    _blockedAppsCtrl = TextEditingController();
    _blockedKwCtrl = TextEditingController();
    _syncFromRules(widget.controller.rules);
  }

  void _syncFromRules(GuardRulesConfig r) {
    _nightCurfewEnabled = r.nightCurfewEnabled;
    _autoStartEnabled = r.autoStartEnabled;
    _focusHardLockDefault = r.focusHardLockDefault;
    _limitCtrl.text = r.dailyEntertainmentLimitMin.toString();
    _cooldownCtrl.text = r.loosenCooldownHours.toString();
    _curfewStartCtrl.text = RuleEngine.formatMinuteOfDay(r.nightCurfewStartMin);
    _curfewEndCtrl.text = RuleEngine.formatMinuteOfDay(r.nightCurfewEndMin);
    _workAppsCtrl.text = r.workApps.join(', ');
    _workKwCtrl.text = r.workTitleKeywords.join(', ');
    _dualSitesCtrl.text = r.dualSiteKeywords.join(', ');
    _blockedAppsCtrl.text = r.blockedApps.join(', ');
    _blockedKwCtrl.text = r.blockedTitleKeywords.join(', ');
    _dirty = false;
  }

  @override
  void dispose() {
    _limitCtrl.dispose();
    _cooldownCtrl.dispose();
    _curfewStartCtrl.dispose();
    _curfewEndCtrl.dispose();
    _workAppsCtrl.dispose();
    _workKwCtrl.dispose();
    _dualSitesCtrl.dispose();
    _blockedAppsCtrl.dispose();
    _blockedKwCtrl.dispose();
    super.dispose();
  }

  Future<void> _saveRules() async {
    final c = widget.controller;
    final cur = c.rules;
    final limit = int.tryParse(_limitCtrl.text.trim()) ?? cur.dailyEntertainmentLimitMin;
    final cooldown = int.tryParse(_cooldownCtrl.text.trim()) ?? cur.loosenCooldownHours;
    final startMin = _parseHhMm(_curfewStartCtrl.text) ?? cur.nightCurfewStartMin;
    final endMin = _parseHhMm(_curfewEndCtrl.text) ?? cur.nightCurfewEndMin;

    final proposed = cur.copyWith(
      dailyEntertainmentLimitMin: limit,
      nightCurfewEnabled: _nightCurfewEnabled,
      nightCurfewStartMin: startMin,
      nightCurfewEndMin: endMin,
      autoStartEnabled: _autoStartEnabled,
      focusHardLockDefault: _focusHardLockDefault,
      loosenCooldownHours: cooldown,
      workApps: _splitCsv(_workAppsCtrl.text),
      workTitleKeywords: _splitCsv(_workKwCtrl.text),
      dualSiteKeywords: _splitCsv(_dualSitesCtrl.text),
      blockedApps: _splitCsv(_blockedAppsCtrl.text),
      blockedTitleKeywords: _splitCsv(_blockedKwCtrl.text),
    );

    final res = await c.proposeRulesUpdate(proposed);
    if (!mounted) return;
    setState(() {
      _syncFromRules(res.nextRules);
      final parts = <String>[
        if (res.appliedImmediately.isNotEmpty)
          '立即生效：${res.appliedImmediately.join("；")}',
        if (res.queuedLoosenings.isNotEmpty)
          '已进 ${res.nextRules.loosenCooldownHours}h 冷却队列：${res.queuedLoosenings.join("；")}',
      ];
      _saveFeedback = parts.isEmpty ? '无规则变更' : parts.join('\n');
    });
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final r = c.rules;
    final now = c.currentNowUtcMs;

    return ListView(
      children: [
        MistGlassSurface(
          borderColor: (r.strictGuardArmed ? MistColors.emerald : MistColors.amber)
              .withValues(alpha: 0.45),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    r.strictGuardArmed ? Icons.verified_user_rounded : Icons.warning_amber_rounded,
                    color: r.strictGuardArmed ? MistColors.emerald : MistColors.amber,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      r.strictGuardArmed
                          ? '严防防退出体系已武装（WM_CLOSE 隐藏托盘 + Watchdog 互保 + 改松冷却）'
                          : '当前处于自由配置态：配好规则和 AI 接口后请点击「开启严防守护」',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              const Text(
                '• 开启严防后：点关闭按钮/Alt+F4 仅隐藏到系统托盘；任务管理器杀主进程后，独立伴生守护进程 dsh_fish_watchdog.exe 将在 2 秒内重新拉起；\n'
                '• 规则机制（Pluckeye 原则）：改严立即生效，改松进入冷却队列，杜绝冲动期自我放水。',
                style: TextStyle(fontSize: 12.5, height: 1.45),
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 10,
                runSpacing: 8,
                children: [
                  if (!r.strictGuardArmed)
                    FilledButton.icon(
                      onPressed: () async {
                        await c.armStrictGuard();
                        if (mounted) setState(() => _syncFromRules(c.rules));
                      },
                      icon: const Icon(Icons.lock_rounded, size: 18),
                      label: const Text('一键开启严防守护'),
                    )
                  else ...[
                    OutlinedButton.icon(
                      onPressed: () => _showFrictionDialog(context, c, alsoExit: false),
                      icon: const Icon(Icons.lock_open_rounded, size: 18),
                      label: const Text('申请解除严防（高阻力验证）'),
                    ),
                    OutlinedButton.icon(
                      onPressed: () => _showFrictionDialog(context, c, alsoExit: true),
                      icon: const Icon(Icons.power_settings_new_rounded, size: 18),
                      label: const Text('授权安全退出程序'),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        if (r.pendingLoosenings.isNotEmpty) ...[
          MistGlassSurface(
            borderColor: MistColors.amber.withValues(alpha: 0.4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '冷却中的改松请求（${r.pendingLoosenings.length} 项）',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 8),
                for (final item in r.pendingLoosenings)
                  ListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.hourglass_bottom_rounded, color: MistColors.amber),
                    title: Text(item.summary),
                    subtitle: Text(_formatRemainingCooldown(item.effectiveAtUtcMs - now)),
                    trailing: TextButton(
                      onPressed: () => c.cancelPendingLoosening(item.id),
                      child: const Text('撤销改松'),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 12),
        ],
        MistGlassSurface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text('管控规则与分类词库', style: Theme.of(context).textTheme.titleMedium),
                  const Spacer(),
                  if (_dirty)
                    TextButton(
                      onPressed: () => setState(() => _syncFromRules(c.rules)),
                      child: const Text('还原已保存值'),
                    ),
                ],
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _limitCtrl,
                      keyboardType: TextInputType.number,
                      onChanged: (_) => setState(() => _dirty = true),
                      decoration: const InputDecoration(
                        isDense: true,
                        labelText: '每日娱乐总上限 (分钟)',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: TextField(
                      controller: _cooldownCtrl,
                      keyboardType: TextInputType.number,
                      onChanged: (_) => setState(() => _dirty = true),
                      decoration: const InputDecoration(
                        isDense: true,
                        labelText: '改松冷却时间 (小时 1-48)',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: TextField(
                      controller: _curfewStartCtrl,
                      onChanged: (_) => setState(() => _dirty = true),
                      decoration: const InputDecoration(
                        isDense: true,
                        labelText: '宵禁开始 (HH:MM)',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: TextField(
                      controller: _curfewEndCtrl,
                      onChanged: (_) => setState(() => _dirty = true),
                      decoration: const InputDecoration(
                        isDense: true,
                        labelText: '宵禁结束 (HH:MM)',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 16,
                children: [
                  SizedBox(
                    width: 260,
                    child: SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      title: const Text('启用深夜宵禁'),
                      value: _nightCurfewEnabled,
                      onChanged: (v) => setState(() {
                        _nightCurfewEnabled = v;
                        _dirty = true;
                      }),
                    ),
                  ),
                  SizedBox(
                    width: 260,
                    child: SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      title: const Text('开机静默自启到托盘'),
                      value: _autoStartEnabled,
                      onChanged: (v) => setState(() {
                        _autoStartEnabled = v;
                        _dirty = true;
                      }),
                    ),
                  ),
                  SizedBox(
                    width: 260,
                    child: SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      title: const Text('专注默认铁锁模式'),
                      value: _focusHardLockDefault,
                      onChanged: (v) => setState(() {
                        _focusHardLockDefault = v;
                        _dirty = true;
                      }),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              _csvField(
                _blockedAppsCtrl,
                '明确娱乐拦截进程（纯娱乐 exe：命中后直接结束进程，逗号分隔；增加立即生效，删减需冷却）',
              ),
              const SizedBox(height: 8),
              _csvField(
                _blockedKwCtrl,
                '明确娱乐标题关键词（浏览器/窗口标题命中后最小化并弹盾，逗号分隔）',
              ),
              const SizedBox(height: 8),
              _csvField(
                _dualSitesCtrl,
                '灰区内容站点关键词（如 bilibili/youtube/知乎：专注/超额时由 AI 判别是否为学习，逗号分隔）',
              ),
              const SizedBox(height: 8),
              _csvField(
                _workKwCtrl,
                '工作/考公/求职标题关键词（优先放行，新增在严防下需冷却防绕过，逗号分隔）',
              ),
              const SizedBox(height: 8),
              _csvField(
                _workAppsCtrl,
                '工作白名单进程（永不拦截，新增在严防下需冷却，逗号分隔）',
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  FilledButton.icon(
                    onPressed: _saveRules,
                    icon: const Icon(Icons.save_rounded, size: 18),
                    label: const Text('提交规则变更'),
                  ),
                  const SizedBox(width: 12),
                  if (_saveFeedback.isNotEmpty)
                    Expanded(
                      child: Text(
                        _saveFeedback,
                        style: const TextStyle(fontSize: 12.5),
                      ),
                    ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _csvField(TextEditingController ctrl, String label) {
    return TextField(
      controller: ctrl,
      minLines: 1,
      maxLines: 3,
      onChanged: (_) => setState(() => _dirty = true),
      style: const TextStyle(fontSize: 12.5),
      decoration: InputDecoration(
        isDense: true,
        labelText: label,
        border: const OutlineInputBorder(),
      ),
    );
  }

  Future<void> _showFrictionDialog(
    BuildContext context,
    PcGuardController c, {
    required bool alsoExit,
  }) async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _FrictionExitDialog(controller: c, alsoExit: alsoExit),
    );
    if (mounted) setState(() => _syncFromRules(c.rules));
  }

  static List<String> _splitCsv(String raw) {
    return raw
        .split(RegExp(r'[,，\n]+'))
        .map((e) => e.trim().toLowerCase())
        .where((e) => e.isNotEmpty)
        .toSet()
        .toList();
  }

  static int? _parseHhMm(String raw) {
    final m = RegExp(r'^(\d{1,2}):(\d{2})$').firstMatch(raw.trim());
    if (m == null) return null;
    final hh = int.tryParse(m.group(1)!);
    final mm = int.tryParse(m.group(2)!);
    if (hh == null || mm == null || hh < 0 || hh > 23 || mm < 0 || mm > 59) {
      return null;
    }
    return hh * 60 + mm;
  }

  static String _formatRemainingCooldown(int remainMs) {
    if (remainMs <= 0) return '已到期，将在下一巡检周期生效';
    final totalMin = (remainMs / 60000).ceil();
    final h = totalMin ~/ 60;
    final m = totalMin % 60;
    return '还剩 $h 小时 $m 分钟生效';
  }
}

class _NoPasteTextInputFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    if (newValue.text.length - oldValue.text.length > 2) {
      return oldValue;
    }
    return newValue;
  }
}

class _FrictionExitDialog extends StatefulWidget {
  const _FrictionExitDialog({
    required this.controller,
    required this.alsoExit,
  });

  final PcGuardController controller;
  final bool alsoExit;

  @override
  State<_FrictionExitDialog> createState() => _FrictionExitDialogState();
}

class _FrictionExitDialogState extends State<_FrictionExitDialog> {
  late final String _challenge;
  final _challengeCtrl = TextEditingController();
  final _reasonCtrl = TextEditingController();
  Timer? _timer;
  int _waitedSec = 0;
  String _error = '';

  @override
  void initState() {
    super.initState();
    final now = widget.controller.currentNowUtcMs;
    final inFocus = widget.controller.focusSession.isActiveAt(now);
    _challenge = RuleEngine.generateFrictionChallenge(inFocus ? 80 : 48);
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {
        if (_waitedSec < 60) _waitedSec++;
      });
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _challengeCtrl.dispose();
    _reasonCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final now = c.currentNowUtcMs;
    final hardLockedNow =
        c.focusSession.isActiveAt(now) && c.focusSession.hardLocked;
    final remainFocusMin = (c.focusSession.remainingSecondsAt(now) / 60).ceil();
    final reasonLen = _reasonCtrl.text.trim().length;
    final canSubmit = !hardLockedNow &&
        _waitedSec >= 60 &&
        reasonLen >= 15 &&
        _challengeCtrl.text.trim().toUpperCase() == _challenge;

    return AlertDialog(
      title: Text(widget.alsoExit ? '授权安全退出（高阻力验证）' : '解除严防保护（高阻力验证）'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (hardLockedNow)
                Container(
                  width: double.infinity,
                  margin: const EdgeInsets.only(bottom: 12),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: MistColors.crimson.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: MistColors.crimson.withValues(alpha: 0.4)),
                  ),
                  child: Text(
                    '当前处于专注铁锁倒计时（剩余 $remainFocusMin 分钟），倒计时归零前彻底禁止解除严防或退出程序！',
                    style: const TextStyle(
                      color: MistColors.crimson,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              Text(
                '1. 请逐字手打下方 ${_challenge.length} 位随机挑战码（不可复制粘贴）：',
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
              ),
              const SizedBox(height: 8),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: MistColors.cobalt.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  _challenge,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 14.5,
                    letterSpacing: 1.2,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _challengeCtrl,
                enabled: !hardLockedNow,
                inputFormatters: [_NoPasteTextInputFormatter()],
                contextMenuBuilder: (ctx, editableState) =>
                    const SizedBox.shrink(),
                onChanged: (_) => setState(() => _error = ''),
                decoration: const InputDecoration(
                  isDense: true,
                  labelText: '在此逐字输入上方挑战码（不区分大小写，禁止粘贴）',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                '2. 明确原因（至少 15 字，当前 $reasonLen/15）：',
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
              ),
              const SizedBox(height: 6),
              TextField(
                controller: _reasonCtrl,
                enabled: !hardLockedNow,
                minLines: 2,
                maxLines: 3,
                onChanged: (_) => setState(() => _error = ''),
                decoration: const InputDecoration(
                  labelText: '例如：今日计划已完成，需重启电脑安装系统更新',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                _waitedSec < 60
                    ? '3. 冷静倒计时：还需等待 ${60 - _waitedSec} 秒...'
                    : '3. 冷静期 60 秒已完成。',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: _waitedSec >= 60 ? MistColors.emerald : MistColors.amber,
                ),
              ),
              if (_error.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(_error, style: const TextStyle(color: MistColors.crimson, fontSize: 12.5)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('放弃退出，继续专注'),
        ),
        FilledButton(
          onPressed: !canSubmit
              ? null
              : () async {
                  final nav = Navigator.of(context);
                  final res = await c.completeFrictionDisarmOrExit(
                    alsoExit: widget.alsoExit,
                    challengeInput: _challengeCtrl.text,
                    expectedChallenge: _challenge,
                    reason: _reasonCtrl.text,
                    waitedSeconds: _waitedSec,
                  );
                  if (!mounted) return;
                  if (res.ok) {
                    nav.pop();
                  } else {
                    setState(() => _error = res.message);
                  }
                },
          child: Text(widget.alsoExit ? '验证并退出程序' : '验证并解除严防'),
        ),
      ],
    );
  }
}
