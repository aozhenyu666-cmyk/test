import 'dart:async';

import 'package:flutter/material.dart';

import '../../runtime/runtime_bridge.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'bank_manager_page.dart';
import 'covenant_page.dart';
import 'gemini_bridge_page.dart';
import 'proactive_page.dart';
import 'review_page.dart';
import 'seal_page.dart';

/// 体检：一页看出体系哪一环断了。手机这边的权限（无障碍、悬浮窗、通知、闹钟、电池、保活）这页自己读；
/// 引擎那边的（对表、测试模式、笔记库、题库、验收、公约、桥、复习）从插件 /health 读。每一行点一下去修。
class HealthPage extends StatefulWidget {
  const HealthPage({super.key, this.channel, this.bridge});

  final ProactiveChannel? channel;
  final RuntimeBridge? bridge;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => HealthPage(channel: channel)));

  @override
  State<HealthPage> createState() => _HealthPageState();
}

/// 手机这边的六项：键 → (名字, 断了时怎么说)。
const List<(String, String, String)> kPhoneChecks = [
  ('a11y', '无障碍', '没开：锁落不下来、看不到前台 App'),
  ('overlay', '悬浮窗', '没开：锁页和提醒弹不出来'),
  ('notifications', '通知', '没开：「紧急解除」和引擎常驻通知看不到'),
  ('exactAlarm', '精确闹钟', '没开：到点开局 / 到点判可能晚几分钟'),
  ('battery', '电池优化白名单', '没放行：系统会在后台把引擎掐掉'),
  ('keeper', '引擎保活', '你点过「停止」：引擎掉了不会自动拉起（在 App 里启动一次就恢复）'),
];

class _HealthPageState extends State<HealthPage> with WidgetsBindingObserver {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  ProactiveStatus? _status;
  Map<String, dynamic>? _health;
  GuardianPairingStatus? _guardian;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_load());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 从系统设置页回来时重读（他刚去开了权限）。
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_load());
  }

  Future<void> _load() async {
    unawaited(_loadGuardian());
    final r = await Future.wait<Object?>([_channel.status(), _channel.healthStatus()]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      _status = r[0] as ProactiveStatus?;
      final h = r[1];
      _health = h is Map<String, dynamic> && h['ok'] == true ? h : null;
    });
  }

  Future<void> _loadGuardian() async {
    try {
      final g = await _channel.guardianStatus();
      if (!mounted || g == null) return;
      setState(() => _guardian = g);
    } catch (_) {}
  }

  List<Map<String, dynamic>> get _items =>
      _health?['items'] is List ? [for (final x in _health!['items'] as List) if (x is Map) Map<String, dynamic>.from(x)] : const [];

  Future<void> _go(Widget page) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));
    if (mounted) unawaited(_load());
  }

  Future<void> _fix(String fix) async {
    switch (fix) {
      case 'capabilities':
        await _channel.openCapability('a11y');
      case 'lock-shadow':
        final ok = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('关掉测试模式？'),
            content: const Text('关了以后锁会真的落下。被锁出不来时：下拉通知栏点「紧急解除」；电话、短信、支付、导航永远不锁。'),
            actions: [
              TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('先不关')),
              FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('关掉，真锁')),
            ],
          ),
        );
        if (ok == true) {
          await _channel.setCompanionConfig({'lockShadow': false});
          await _load();
        }
      case 'vault':
        await _go(const ProactivePage());
      case 'bank':
        await _go(BankManagerPage(channel: _channel));
      case 'review':
        await _go(ReviewPage(channel: _channel));
      case 'seals':
        await _go(SealPage(channel: _channel));
      case 'covenant':
        await _go(CovenantPage(channel: _channel));
      case 'bridge':
        await _go(GeminiBridgePage(channel: _channel));
    }
  }

  (StatusTone, String) _tone(String level) => switch (level) {
        'error' => (StatusTone.error, '断了'),
        'warn' => (StatusTone.warn, '注意'),
        _ => (StatusTone.ok, '正常'),
      };

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final caps = _status?.capabilities ?? const <String, bool>{};
    final phoneBad = [for (final c in kPhoneChecks) if (caps[c.$1] == false) c];
    final items = _items;
    final engineBad = items.where((x) => x['level'] == 'error').length;
    final engineWarn = items.where((x) => x['level'] == 'warn').length;
    final groups = <String, List<Map<String, dynamic>>>{};
    for (final it in items) {
      groups.putIfAbsent('${it['group']}', () => []).add(it);
    }
    final bad = phoneBad.length + engineBad;
    final engineDown = !_loading && _health == null;

    return AppPage.list(
      title: '体检',
      subtitle: const Text('哪一环断了，点一下去修'),
      actions: [GlassIconButton(tooltip: '再查一遍', icon: AppIcons.refresh, onPressed: () => unawaited(_load()))],
      children: [
        OverviewCard(
          leading: OverviewGlyph(bad > 0 || engineDown ? AppIcons.warning : AppIcons.success, tint: bad > 0 || engineDown ? 1 : 3),
          title: Text(_loading
              ? '查着…'
              : engineDown
                  ? '引擎没应答'
                  : bad > 0
                      ? '$bad 处断了'
                      : engineWarn > 0
                          ? '能用，$engineWarn 处要注意'
                          : '都正常'),
          subtitle: Text(engineDown
              ? '引擎那一半查不了：看通知栏有没有引擎常驻通知，没有就打开 DSH Go 等它起来'
              : '${_health?['summary'] is Map ? (_health!['summary'] as Map)['line'] ?? '' : ''}'),
        ),
        ListPageCard(
          key: const ValueKey('health-phone'),
          title: '手机',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            for (final c in kPhoneChecks)
              if (caps.containsKey(c.$1))
                ListPageRow(
                  key: ValueKey('health-cap-${c.$1}'),
                  icon: caps[c.$1] == true ? AppIcons.success : AppIcons.warning,
                  iconColor: caps[c.$1] == true ? colors.success : colors.danger,
                  title: c.$2,
                  subtitle: caps[c.$1] == true ? null : c.$3,
                  subtitleMaxLines: 2,
                  badge: StatusChip(tone: caps[c.$1] == true ? StatusTone.ok : StatusTone.error, label: caps[c.$1] == true ? '正常' : '断了'),
                  chevron: caps[c.$1] != true && c.$1 != 'keeper',
                  onTap: caps[c.$1] != true && c.$1 != 'keeper' ? () => unawaited(_channel.openCapability(c.$1)) : null,
                ),
            ListPageRow(
              key: const ValueKey('health-autostart'),
              icon: AppIcons.autostart,
              title: '自启动 / 后台高耗电（系统不告诉 App，要你自己确认一次）',
              subtitle: 'iQOO：i管家 → 应用管理 → 自启动 / 后台耗电管理 → DSH Go 都打开',
              subtitleMaxLines: 3,
              chevron: true,
              onTap: () => unawaited((widget.bridge ?? RuntimeBridge()).openVendorSettings('autostart')),
            ),
            if (_guardian != null)
              ListPageRow(
                key: const ValueKey('health-guardian'),
                icon: !_guardian!.installed
                    ? AppIcons.permissions
                    : (_guardian!.paired ? AppIcons.success : AppIcons.warning),
                iconColor: !_guardian!.installed
                    ? colors.textTertiary
                    : (_guardian!.paired ? colors.success : colors.warning),
                title: '独立哨兵（防强停 / 防卸载）',
                subtitle: !_guardian!.installed
                    ? '未安装（可选加固，主包内置 EngineKeeper 已正常保活）'
                    : _guardian!.paired
                        ? (_guardian!.sameCert ? '同签名自动互信，跨进程看护就绪' : '已通过 6 位配对码完成互信')
                        : '打开「大肥鱼哨兵」输入 6 位配对码（或安装同签名新版自动互信）',
                subtitleMaxLines: 2,
                badge: StatusChip(
                  tone: !_guardian!.installed
                      ? StatusTone.ok
                      : (_guardian!.paired ? StatusTone.ok : StatusTone.warn),
                  label: !_guardian!.installed
                      ? '可选'
                      : (_guardian!.paired ? (_guardian!.sameCert ? '同签互信' : '已配对') : '待配对'),
                ),
                chevron: _guardian!.installed && !_guardian!.paired,
                onTap: _guardian!.installed && !_guardian!.paired
                    ? () => unawaited(_go(const ProactivePage()))
                    : null,
              ),
          ],
        ),
        for (final g in groups.entries)
          ListPageCard(
            key: ValueKey('health-group-${g.key}'),
            title: g.key,
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              for (final it in g.value)
                () {
                  final (tone, label) = _tone('${it['level']}');
                  final fix = '${it['fix'] ?? ''}';
                  final level = '${it['level']}';
                  return ListPageRow(
                    key: ValueKey('health-${it['id']}'),
                    icon: level == 'ok' ? AppIcons.success : AppIcons.warning,
                    iconColor: level == 'ok' ? colors.success : (level == 'error' ? colors.danger : colors.warning),
                    title: '${it['label']}',
                    subtitle: '${it['detail'] ?? ''}'.isEmpty ? null : '${it['detail']}',
                    subtitleMaxLines: 3,
                    badge: StatusChip(tone: tone, label: label),
                    chevron: fix.isNotEmpty,
                    onTap: fix.isEmpty ? null : () => unawaited(_fix(fix)),
                  );
                }(),
            ],
          ),
        const SizedBox(height: 12),
      ],
    );
  }
}
