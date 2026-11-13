// 第九轮 B1：计划界面（全屏）。
//
// 一局一行。逐行的「必需应用 / 允许应用」是这一页存在的理由：严格模式放开谁、
// 锁住谁，全靠这两栏，所以两栏都不许空。**校验只有一处**——插件 `POST /plan/day`，
// 缺了它当场退回并点名是哪几局；这一页不复制一份规则，只把插件那句话显出来。
//
// 数据两头都从插件来：`GET /plan/day` 给今天的计划、当前分钟、最近用过的包名和
// 局型清单（含各自默认时长）。手机不硬编码协议，也不猜默认值。

import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';
import '../../loop/loop_channel.dart';
import '../../proactive/proactive_state.dart';
import '../common/app_sheet.dart';
import '../common/list_page_row.dart';
import '../common/sheet_title_bar.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';

/// 一局的草稿。字段名和插件 `planwire.js` 的 item 一致：多发一个键就是多一处要同步的地方。
class PlanRow {
  PlanRow({
    required this.protocolId,
    required this.title,
    required this.minute,
    required this.budget,
    List<String>? need,
    List<String>? allow,
  })  : need = need ?? <String>[],
        allow = allow ?? <String>[];

  String protocolId;
  String title;
  int minute;
  int budget;
  final List<String> need;
  final List<String> allow;

  Map<String, Object?> toWire() => {
        'protocolId': protocolId,
        'title': title,
        'minute': minute,
        'budget': budget,
        'need': need,
        'allow': allow,
      };
}

class PlanPage extends StatefulWidget {
  const PlanPage({super.key, this.channel, this.loop, this.asTab = false});

  /// 测试用：塞一个假的通道，省得每条用例都起引擎。
  final ProactiveChannel? channel;
  final LoopChannel? loop;

  /// 作为首页的标签页站着：没有可返回的上一页。
  final bool asTab;

  @override
  State<PlanPage> createState() => _PlanPageState();
}

class _PlanPageState extends State<PlanPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final LoopChannel _loop = widget.loop ?? LoopChannel();

  bool _loading = true;
  bool _failed = false;
  bool _saving = false;
  String _why = '';

  int _min = 0;
  List<PlanRow> _rows = [];
  List<LoopApp> _apps = const [];
  List<String> _recent = const [];
  List<_Protocol> _protocols = const [];

  /// 上一回插件点名的局（按标题）。只用来把那一行标红，判还是插件判的。
  Set<String> _missing = const {};

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final day = await _channel.planDay();
    final apps = await _loop.apps();
    if (!mounted) return;
    setState(() {
      _loading = false;
      _failed = day == null;
      _why = day == null ? 'no_answer' : '';
      _apps = apps;
      _recent = _strings(day?['recent']);
      _protocols = _protocolList(day?['protocols']);
      _min = day?['min'] is int ? day!['min'] as int : 0;
      _rows = _rowsOf(day?['plan']);
      if (_rows.isEmpty) _rows = [_blank()];
    });
  }

  List<PlanRow> _rowsOf(Object? plan) {
    final items = plan is Map ? plan['items'] : null;
    if (items is! List) return [];
    return [
      for (final i in items)
        if (i is Map && i['protocolId'] is String)
          PlanRow(
            protocolId: i['protocolId'] as String,
            title: (i['title'] as String?) ?? i['protocolId'] as String,
            minute: i['minute'] is int ? i['minute'] as int : _min,
            budget: i['budget'] is int ? i['budget'] as int : 30,
            need: _strings(i['need']),
            allow: _strings(i['allow']),
          ),
    ];
  }

  static List<String> _strings(Object? raw) =>
      raw is List ? [for (final x in raw) if (x is String && x.isNotEmpty) x] : const [];

  static List<_Protocol> _protocolList(Object? raw) => raw is List
      ? [
          for (final x in raw)
            if (x is Map && x['id'] is String)
              _Protocol(
                x['id'] as String,
                x['name'] is String ? x['name'] as String : x['id'] as String,
                x['minutes'] is int && (x['minutes'] as int) > 0 ? x['minutes'] as int : 30,
              ),
        ]
      : const [];

  PlanRow _blank() => PlanRow(
        protocolId: '',
        title: '',
        minute: _min,
        budget: 30,
      );

  Future<void> _pickProtocol(int index) async {
    if (_protocols.isEmpty) return;
    final picked = await showAppSheet<_Protocol>(
      context,
      builder: (_) => _PickerSheet<_Protocol>(
        title: '这一局是哪种局',
        rows: [
          for (final p in _protocols) (label: '${p.name}（默认 ${p.minutes} 分钟）', value: p),
        ],
        selected: _rows[index].protocolId,
        keyOf: (p) => p.id,
      ),
    );
    if (picked == null || !mounted) return;
    setState(() {
      final row = _rows[index];
      row.protocolId = picked.id;
      row.title = picked.name;
      row.budget = picked.minutes;
    });
  }

  Future<void> _pickTime(int index) async {
    final row = _rows[index];
    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(hour: row.minute ~/ 60, minute: row.minute % 60),
    );
    if (picked == null || !mounted) return;
    setState(() => row.minute = picked.hour * 60 + picked.minute);
  }

  Future<void> _pickApps(int index, {required bool need}) async {
    if (_apps.isEmpty) {
      _say('读不到已安装的 App。装过什么只有系统知道，这一页不猜。');
      return;
    }
    final row = _rows[index];
    final picked = await showAppSheet<List<String>>(
      context,
      isScrollControlled: true,
      builder: (_) => _AppPickerSheet(
        title: need ? '这一局要用哪些 App（必需）' : '严格模式下放行哪些 App（允许）',
        apps: _apps,
        recent: _recent,
        selected: need ? row.need : row.allow,
      ),
    );
    if (picked == null || !mounted) return;
    setState(() {
      final target = need ? row.need : row.allow;
      target
        ..clear()
        ..addAll(picked);
    });
  }

  void _say(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _submit() async {
    if (_saving) return;
    // 没选局型的行不算一局：插件那边会把它当坏条目丢掉，交上去就成了「0 局」的计划。
    // 这条不是替插件校验应用——应用还是插件说了算——它只挡住「这一行根本没填完」。
    if (_rows.any((r) => r.protocolId.isEmpty)) {
      _say('每一局都要先选是哪种局。');
      return;
    }
    setState(() {
      _saving = true;
      _missing = const {};
    });
    final r = await _channel.planDaySet([for (final row in _rows) row.toWire()]);
    if (!mounted) return;
    setState(() => _saving = false);
    if (r == null) {
      _say('交不上去：主机没回话。计划还是原样，没有半张。');
      return;
    }
    if (r['ok'] != true) {
      // 插件点名了哪几局：把这几行标出来，别让他从头找。
      final miss = _strings(r['missing']);
      setState(() => _missing = miss.toSet());
      _say(r['text'] is String && (r['text'] as String).isNotEmpty ? r['text'] as String : '这一份还交不上去。');
      return;
    }
    _say(r['text'] is String ? r['text'] as String : '计划定了。');
    unawaited(_load());
  }

  @override
  Widget build(BuildContext context) {
    return AppPage.list(
      title: '今天的计划',
      back: !widget.asTab,
      subtitle: const Text('一局一行。必需应用和允许应用都要写——严格模式就按这两栏放行。'),
      actions: [
        GlassIconButton(icon: AppIcons.refresh, tooltip: '重新读一遍', onPressed: () => unawaited(_load())),
      ],
      children: [
        if (_loading)
          const ListPageCard(children: [ListPageRow(icon: AppIcons.loading, title: '正在读今天的计划…')])
        else if (_failed)
          ListPageCard(
            children: [
              ListPageRow(icon: AppIcons.warning, title: '读不到今天的计划（$_why）', titleMaxLines: 2),
            ],
          )
        else ...[
          for (var i = 0; i < _rows.length; i += 1) _card(i),
          const SizedBox(height: 12),
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.add,
                title: '再加一局',
                onTap: () => setState(() => _rows.add(_blank())),
              ),
              ListPageRow(
                icon: AppIcons.selected,
                title: _saving ? '正在交…' : '就按这一份来',
                subtitle: '交上去立刻生效，到点自己开局',
                onTap: _saving ? null : () => unawaited(_submit()),
              ),
            ],
          ),
          const SizedBox(height: 12),
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.time,
                title: '现在是 ${_clock(_min)}',
                subtitle: '比当前时间早太多（20 分钟以上）的局会记成空局，不会补开',
                subtitleMaxLines: 2,
              ),
            ],
          ),
        ],
      ],
    );
  }

  Widget _card(int index) {
    final row = _rows[index];
    final miss = row.title.isNotEmpty && _missing.contains(row.title);
    final needText = row.need.isEmpty ? '还没写（必须写）' : row.need.map(_label).join('、');
    final allowText = row.allow.isEmpty ? '还没写（必须写）' : row.allow.map(_label).join('、');
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: ListPageCard(
        children: [
          ListPageRow(
            icon: AppIcons.calendar,
            title: row.title.isEmpty ? '选一种局' : row.title,
            subtitle: miss ? '这一局没写清用哪些 App' : '第 ${index + 1} 局',
            subtitleColor: miss ? DshColors.of(context).danger : null,
            onTap: () => unawaited(_pickProtocol(index)),
            trailing: _rows.length > 1
                ? GlassIconButton(
                    icon: AppIcons.delete,
                    tooltip: '删掉这一局',
                    size: 32,
                    grouped: false,
                    onPressed: () => setState(() => _rows.removeAt(index)),
                  )
                : null,
          ),
          ListPageRow(
            icon: AppIcons.time,
            title: '开始 ${_clock(row.minute)} · ${row.budget} 分钟',
            subtitle: '点这一行改开始时间，右边的减号加号改时长',
            onTap: () => unawaited(_pickTime(index)),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                GlassIconButton(
                  icon: AppIcons.collapse,
                  tooltip: '少 5 分钟',
                  size: 32,
                  grouped: false,
                  onPressed: () => setState(() => row.budget = row.budget > 5 ? row.budget - 5 : row.budget),
                ),
                GlassIconButton(
                  icon: AppIcons.expand,
                  tooltip: '多 5 分钟',
                  size: 32,
                  grouped: false,
                  onPressed: () => setState(() => row.budget = row.budget < 180 ? row.budget + 5 : row.budget),
                ),
              ],
            ),
          ),
          ListPageRow(
            icon: AppIcons.openWith,
            title: '必需应用',
            subtitle: needText,
            subtitleMaxLines: 2,
            subtitleColor: row.need.isEmpty ? DshColors.of(context).danger : null,
            onTap: () => unawaited(_pickApps(index, need: true)),
          ),
          ListPageRow(
            icon: AppIcons.blocked,
            title: '允许应用',
            subtitle: allowText,
            subtitleMaxLines: 2,
            subtitleColor: row.allow.isEmpty ? DshColors.of(context).danger : null,
            onTap: () => unawaited(_pickApps(index, need: false)),
          ),
        ],
      ),
    );
  }

  String _label(String pkg) {
    for (final a in _apps) {
      if (a.package == pkg) return a.label;
    }
    return pkg;
  }

  static String _clock(int minute) =>
      '${(minute ~/ 60).toString().padLeft(2, '0')}:${(minute % 60).toString().padLeft(2, '0')}';
}

class _Protocol {
  const _Protocol(this.id, this.name, this.minutes);

  final String id;
  final String name;
  final int minutes;
}

/// 一列可点的选项。`value` 不回给调用方以外的人：选项长什么样由调用方决定。
class _PickerSheet<T> extends StatelessWidget {
  const _PickerSheet({
    required this.title,
    required this.rows,
    required this.selected,
    required this.keyOf,
  });

  final String title;
  final List<({String label, T value})> rows;
  final String selected;
  final String Function(T) keyOf;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      top: false,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SheetTitleBar(title),
          Flexible(
            child: ListView(
              shrinkWrap: true,
              padding: EdgeInsets.zero,
              children: [
                SheetGroup(
                  children: [
                    for (final row in rows)
                      InkWell(
                        onTap: () => Navigator.of(context).pop(row.value),
                        child: SizedBox(
                          height: 56,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                            child: Row(
                              children: [
                                Expanded(
                                  child: Text(row.label, style: theme.textTheme.bodyLarge?.copyWith(fontSize: 15)),
                                ),
                                if (keyOf(row.value) == selected)
                                  Icon(AppIcons.selected, size: 20, color: theme.colorScheme.primary),
                              ],
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 应用多选：最近用过的先排，其余按名字。预填靠外面传进来的 [selected]。
class _AppPickerSheet extends StatefulWidget {
  const _AppPickerSheet({
    required this.title,
    required this.apps,
    required this.recent,
    required this.selected,
  });

  final String title;
  final List<LoopApp> apps;
  final List<String> recent;
  final List<String> selected;

  @override
  State<_AppPickerSheet> createState() => _AppPickerSheetState();
}

class _AppPickerSheetState extends State<_AppPickerSheet> {
  late final Set<String> _picked = {...widget.selected};

  /// 最近用过的在前（同一个包名只留第一次出现），其余按名字排。
  List<LoopApp> get _sorted {
    final rank = <String, int>{};
    for (var i = 0; i < widget.recent.length; i += 1) {
      rank.putIfAbsent(widget.recent[i], () => i);
    }
    final list = [...widget.apps];
    list.sort((a, b) {
      final ra = rank[a.package] ?? 1 << 20;
      final rb = rank[b.package] ?? 1 << 20;
      return ra != rb ? ra.compareTo(rb) : a.label.compareTo(b.label);
    });
    return list;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final apps = _sorted;
    return SafeArea(
      top: false,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SheetTitleBar(widget.title),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                padding: EdgeInsets.zero,
                children: [
                  SheetGroup(
                    children: [
                      for (final app in apps)
                        InkWell(
                          key: ValueKey('plan-app-${app.package}'),
                          onTap: () => setState(
                            () => _picked.contains(app.package) ? _picked.remove(app.package) : _picked.add(app.package),
                          ),
                          child: SizedBox(
                            height: 56,
                            child: Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 16),
                              child: Row(
                                children: [
                                  Expanded(
                                    child: Text(
                                      app.label,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: theme.textTheme.bodyLarge?.copyWith(fontSize: 15),
                                    ),
                                  ),
                                  Icon(
                                    _picked.contains(app.package) ? AppIcons.radioOn : AppIcons.radioOff,
                                    size: 22,
                                    color: _picked.contains(app.package)
                                        ? theme.colorScheme.primary
                                        : DshColors.of(context).textTertiary,
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(SheetTitleBar.inset, 12, SheetTitleBar.inset, 16),
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(_picked.toList()),
                child: Text(context.l10n.commonDone),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
