import 'dart:async';

import 'package:flutter/material.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/intercept_channel.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'app_picker_dialog.dart';

/// 一层的说法：分类表只有这一份，锁页、额度、局的放行都按它判。
const Map<String, (String, String)> kTierText = {
  'game': ('游戏', '干活时锁；空闲时花娱乐余额，另有每天上限'),
  'fun': ('娱乐', '干活时锁；空闲时花娱乐余额'),
  'dual': ('双用途', '干活时先跟鲸澜要通行证；空闲时花娱乐余额'),
  'work': ('工作', '干活时直接放行'),
  'other': ('其他', '未分类应用：移入工作白名单立即生效；干活时不在本局放行名单里就锁'),
  'base': ('系统底座', '桌面、DSH、Gemini、NekoBox、输入法、电话支付、相册文件：永远不锁'),
};

/// App 分类表：原来「娱乐锁机」的黑名单、局的放行预设、插件的分层，收成这一张表。
///
/// 未分类（其他）→ 工作白名单立即生效；仅娱乐/游戏/双用途改松需等 24 小时，期间可以撤回——
/// 和笔记库里 大肥鱼/墙/App分层.md 是同一张表、同一条规矩。
class AppTiersPage extends StatefulWidget {
  const AppTiersPage({super.key, this.channel, this.loop, this.intercept});

  final ProactiveChannel? channel;
  final LoopChannel? loop;
  final InterceptChannel? intercept;

  static Future<void> open(BuildContext context) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const AppTiersPage()));

  @override
  State<AppTiersPage> createState() => _AppTiersPageState();
}

class _AppTiersPageState extends State<AppTiersPage> {
  static const _defaultWhitelistPresets = <LoopApp>[
    LoopApp('md.obsidian', 'Obsidian'),
    LoopApp('com.tencent.mm', '微信'),
    LoopApp('com.google.android.apps.bard', 'Gemini'),
    LoopApp('com.tencent.mobileqq', 'QQ'),
  ];

  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final LoopChannel _loop = widget.loop ?? LoopChannel();
  late final InterceptChannel _intercept = widget.intercept ?? InterceptChannel();
  final TextEditingController _q = TextEditingController();

  bool _loading = true;
  bool _failed = false;
  List<LoopApp> _installedApps = const [];
  List<String> _defaultAllow = const [];
  List<Map<String, dynamic>> _rows = const [];
  final Set<String> _open = {'game', 'fun', 'dual', 'work'};
  String? _note;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void dispose() {
    _q.dispose();
    super.dispose();
  }

  /// 旧「娱乐锁机」黑名单 → 分类表的「娱乐」（改严，立即生效），然后清掉旧名单，免得两张表打架。
  Future<int> _migrateInterceptTargets() async {
    try {
      final st = await _intercept.status();
      final targets = st.config.targets;
      if (targets.isEmpty) return 0;
      var moved = 0;
      for (final t in targets) {
        final r = await _channel.tiersOp({'action': 'set', 'pkg': t.packageName, 'tier': 'fun'});
        if (r?['ok'] == true) moved++;
      }
      if (moved == targets.length) await _intercept.patch({'targets': const <Object?>[], 'enabled': false});
      return moved;
    } catch (_) {
      return 0;
    }
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _failed = false;
    });
    final moved = await _migrateInterceptTargets();
    final apps = await _loop.apps();
    final res = await _channel.tiersOp({
      'action': 'list',
      'apps': [for (final a in apps) {'pkg': a.package, 'label': a.label}],
    });
    if (!mounted) return;
    final hasDefaultAllow = res != null && res['defaultAllow'] is List;
    final parsedAllow = hasDefaultAllow
        ? [for (final p in res['defaultAllow'] as List) if ('$p'.trim().isNotEmpty) '$p'.trim()]
        : null;
    setState(() {
      _loading = false;
      _failed = res == null || res['ok'] != true;
      _installedApps = apps;
      if (parsedAllow != null) _defaultAllow = parsedAllow;
      _rows = res?['rows'] is List
          ? [for (final r in res!['rows'] as List) if (r is Map) Map<String, dynamic>.from(r)]
          : const [];
      if (moved > 0) _note = '原来「娱乐锁机」名单里的 $moved 个 App 已并入「娱乐」';
    });
  }

  String _labelOf(Map<String, dynamic> r) {
    final l = '${r['label'] ?? ''}'.trim();
    return l.isEmpty ? '${r['pkg']}' : l;
  }

  String _pkgDisplayName(String pkg) {
    for (final r in _rows) {
      if ('${r['pkg']}' == pkg) {
        final label = '${r['label'] ?? ''}'.trim();
        if (label.isNotEmpty) return label;
      }
    }
    for (final a in _installedApps) {
      if (a.package == pkg && a.label.trim().isNotEmpty) return a.label.trim();
    }
    for (final p in _defaultWhitelistPresets) {
      if (p.package == pkg) return p.label;
    }
    return pkg;
  }

  Future<void> _editDefaultAllow() async {
    final picked = await showInstalledAppsPicker(
      context,
      title: '管理手机默认专注白名单',
      loop: _loop,
      initialApps: _installedApps,
      presets: _defaultWhitelistPresets,
      presetsLabel: '常用专注工具一键勾选',
      initialSelected: _defaultAllow,
      allowEmpty: false,
      emptyError: '至少保留一个默认专注白名单应用',
      confirmLabel: '保存白名单',
    );
    if (picked == null || !mounted) return;
    final pkgs = picked.map((a) => a.package).toList();
    final res = await _channel.setDefaultAllow(pkgs);
    if (!mounted) return;
    if (res != null && res['ok'] != false) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    final serverAllowRaw = res?['defaultAllow'] ?? res?['allow'];
    final serverAllow = serverAllowRaw is List
        ? [for (final p in serverAllowRaw) if ('$p'.trim().isNotEmpty) '$p'.trim()]
        : const <String>[];
    final rejectedRaw = res?['rejectedGames'];
    final rejected = rejectedRaw is List
        ? [for (final g in rejectedRaw) if ('$g'.trim().isNotEmpty) _pkgDisplayName('$g'.trim())]
        : const <String>[];
    final toast = '${res?['toast'] ?? ''}'.trim();
    final String msg;
    if (res == null) {
      msg = '连不上引擎，默认白名单未保存';
    } else if (res['ok'] == false) {
      final err = toast.isNotEmpty ? toast : '${res['error'] ?? '引擎拒绝修改'}';
      msg = rejected.isNotEmpty ? '$err（已拦截纯游戏：${rejected.join('、')}）' : err;
    } else {
      final updated = serverAllow.isNotEmpty ? serverAllow : pkgs;
      final base = toast.isNotEmpty ? toast : '已持久化手机默认专注白名单（${updated.length} 个应用）';
      msg = rejected.isNotEmpty ? '$base · 已拦截纯游戏：${rejected.join('、')}' : base;
      setState(() {
        _defaultAllow = updated;
        _note = msg;
      });
    }
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
    await _load();
  }

  Future<void> _choose(Map<String, dynamic> row) async {
    final cur = '${row['tier']}';
    final pending = row['pendingTier'] as String?;
    final picked = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 4),
              child: Text(_labelOf(row), style: Theme.of(ctx).textTheme.titleMedium),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Text(
                cur == 'other'
                    ? '未分类（其他）→ 工作白名单立即生效；往严里挪也立即生效。'
                    : '未分类（其他）→ 工作白名单立即生效；仅娱乐/游戏/双用途改松需等 24 小时。',
              ),
            ),
            for (final t in const ['game', 'fun', 'dual', 'work'])
              ListTile(
                key: ValueKey('tier-pick-$t'),
                leading: Icon(t == cur ? AppIcons.success : AppIcons.queued),
                title: Text(kTierText[t]!.$1),
                subtitle: Text(kTierText[t]!.$2),
                selected: t == cur,
                onTap: () => Navigator.of(ctx).pop(t),
              ),
            if (pending != null)
              ListTile(
                key: const ValueKey('tier-cancel'),
                leading: const Icon(AppIcons.close),
                title: Text('撤回「${kTierText[pending]?.$1 ?? pending}」'),
                onTap: () => Navigator.of(ctx).pop('cancel'),
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (picked == null || !mounted) return;
    final res = await _channel.tiersOp(
      picked == 'cancel' ? {'action': 'cancel', 'pkg': row['pkg']} : {'action': 'set', 'pkg': row['pkg'], 'tier': picked},
    );
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('${res?['text'] ?? (res == null ? '连不上引擎，没改成' : '改好了')}')),
    );
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final q = _q.text.trim().toLowerCase();
    final rows = q.isEmpty
        ? _rows
        : _rows.where((r) => _labelOf(r).toLowerCase().contains(q) || '${r['pkg']}'.toLowerCase().contains(q)).toList();
    final byTier = <String, List<Map<String, dynamic>>>{};
    for (final r in rows) {
      byTier.putIfAbsent('${r['tier']}', () => []).add(r);
    }
    for (final l in byTier.values) {
      l.sort((a, b) => _labelOf(a).compareTo(_labelOf(b)));
    }

    return AppPage.list(
      title: 'App 分类表',
      subtitle: const Text('未分类（其他）→ 工作白名单立即生效；仅娱乐/游戏/双用途改松需等 24 小时'),
      actions: [GlassIconButton(tooltip: '刷新', icon: AppIcons.refresh, onPressed: () => unawaited(_load()))],
      children: [
        ListPageCard(
          key: const ValueKey('tiers-default-allow-card'),
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          children: [
            ListPageRow(
              key: const ValueKey('tiers-default-allow-row'),
              icon: AppIcons.verified,
              iconColor: colors.success,
              title: '管理手机默认专注白名单',
              subtitle: _defaultAllow.isEmpty
                  ? '点击弹窗多选工作与学习应用，确认后立刻持久化到引擎'
                  : '已放行 ${_defaultAllow.length} 个：${_defaultAllow.map(_pkgDisplayName).join('、')}',
              subtitleMaxLines: 2,
              value: '${_defaultAllow.length} 个',
              chevron: true,
              onTap: _loading ? null : () => unawaited(_editDefaultAllow()),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: TextField(
            key: const ValueKey('tiers-search'),
            controller: _q,
            decoration: const InputDecoration(prefixIcon: Icon(AppIcons.search), hintText: '搜 App 名字或包名', isDense: true),
            onChanged: (_) => setState(() {}),
          ),
        ),
        if (_note != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 12),
            child: Text(_note!, style: TextStyle(color: colors.success)),
          ),
        if (_loading)
          const ListPageCard(children: [ListPageRow(icon: AppIcons.loading, title: '读取中…')])
        else if (_failed)
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.warning,
                iconColor: colors.warning,
                title: '引擎没应答，读不到分类表',
                subtitle: '点右上角刷新再试；改分类要引擎在线',
                subtitleMaxLines: 2,
              ),
            ],
          )
        else
          for (final t in const ['game', 'fun', 'dual', 'work', 'other', 'base'])
            if ((byTier[t] ?? const []).isNotEmpty || (t != 'other' && t != 'base'))
              ListPageCard(
                key: ValueKey('tier-group-$t'),
                title: '${kTierText[t]!.$1} · ${(byTier[t] ?? const []).length}',
                margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
                children: [
                  ListPageRow(
                    icon: _open.contains(t) || q.isNotEmpty ? AppIcons.collapse : AppIcons.expand,
                    title: kTierText[t]!.$2,
                    titleMaxLines: 2,
                    titleStyle: Theme.of(context).textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    onTap: () => setState(() => _open.contains(t) ? _open.remove(t) : _open.add(t)),
                  ),
                  if (_open.contains(t) || q.isNotEmpty)
                    for (final r in byTier[t] ?? const <Map<String, dynamic>>[])
                      ListPageRow(
                        key: ValueKey('tier-row-${r['pkg']}'),
                        icon: AppIcons.allFeatures,
                        title: _labelOf(r),
                        subtitle: r['pendingTier'] != null
                            ? '24 小时后改为「${kTierText['${r['pendingTier']}']?.$1 ?? r['pendingTier']}」（点开可撤回）'
                            : (r['override'] != null ? '你定的' : null),
                        badge: r['pendingTier'] != null ? const StatusChip(tone: StatusTone.warn, label: '挂起') : null,
                        chevron: t != 'base',
                        onTap: t == 'base' ? null : () => unawaited(_choose(r)),
                      ),
                ],
              ),
      ],
    );
  }
}
