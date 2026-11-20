import 'dart:async';

import 'package:flutter/material.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';

const Map<String, (String, StatusTone)> _kNodeStatus = {
  'planned': ('预留', StatusTone.idle),
  'active': ('攻坚中', StatusTone.ok),
  'passed': ('达成', StatusTone.ok),
  'slipped': ('延期', StatusTone.warn),
};

const Map<String, String> _kOutcome = {
  'passed': '验收通过',
  'done': '完成',
  'partial': '部分 / 手动收工',
  'escalated': '升级收场',
  'overdue_locked': '超时',
};

/// 战役档案：一条长期 / 中期目标的预留时间节点，每个节点干过哪几局、花了多少分钟、交了什么、卡在哪。
///
/// 从节点一键开局（带上交付物和上次断点）；局结束自动写回这里。预警只提示，调盘由他点。
class HorizonDossierPage extends StatefulWidget {
  const HorizonDossierPage({super.key, required this.horizonId, this.channel});

  final String horizonId;
  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {required String horizonId, ProactiveChannel? channel}) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => HorizonDossierPage(horizonId: horizonId, channel: channel)),
      );

  @override
  State<HorizonDossierPage> createState() => _HorizonDossierPageState();
}

class _HorizonDossierPageState extends State<HorizonDossierPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  Map<String, dynamic>? _horizon;
  Map<String, dynamic> _summary = const {};
  bool _loading = true;
  bool _busy = false;
  final Set<String> _expanded = {};

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  List<Map<String, dynamic>> _list(Object? raw) =>
      raw is List ? [for (final x in raw) if (x is Map) Map<String, dynamic>.from(x)] : const [];

  int _int(Object? v) => v is num ? v.round() : 0;

  void _apply(Map<String, dynamic>? r) {
    if (r == null) return;
    if (r['horizon'] is Map) _horizon = Map<String, dynamic>.from(r['horizon'] as Map);
    if (r['summary'] is Map) _summary = Map<String, dynamic>.from(r['summary'] as Map);
  }

  Future<void> _load() async {
    final r = await _channel.horizonsOp({'action': 'dossier', 'horizonId': widget.horizonId});
    if (!mounted) return;
    setState(() {
      _loading = false;
      _apply(r);
    });
  }

  Future<Map<String, dynamic>?> _op(Map<String, Object?> body, {String? ok}) async {
    setState(() => _busy = true);
    final r = await _channel.horizonsOp({...body, 'horizonId': widget.horizonId});
    if (!mounted) return r;
    setState(() {
      _busy = false;
      if (r?['ok'] == true) _apply(r);
    });
    final text = r == null ? '连不上引擎，没改成' : (r['ok'] == true ? ok : '${r['text'] ?? r['error'] ?? '没改成'}');
    if (text != null) ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(text)));
    return r;
  }

  /// 从节点开一局：插件编译出今天的一条（带交付物 + 断点），手机马上开局。
  Future<void> _startFromNode(Map<String, dynamic> node) async {
    final r = await _op({'action': 'node_start', 'nodeId': node['id']});
    if (r?['ok'] != true || r?['item'] is! Map || !mounted) return;
    final item = Map<String, dynamic>.from(r!['item'] as Map);
    final open = await _channel.boutsCommand({
      'action': 'open',
      'id': item['id'],
      'title': item['title'],
      'allow': item['allow'],
      'minutes': item['minutes'],
      'parentHorizonId': widget.horizonId,
      'strict': true,
    });
    if (!mounted) return;
    final dropped = (r['droppedApps'] is List) ? (r['droppedApps'] as List).length : 0;
    final ok = open?['ok'] == true;
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(
      content: Text(ok
          ? '开局：${item['title']}（${item['minutes']} 分钟）${dropped > 0 ? '；娱乐 / 游戏 App 不放行（$dropped 个）' : ''}'
          : '${open?['toast'] ?? '没开成局'}'),
    ));
    if (ok) Navigator.of(context).maybePop();
  }

  Future<void> _editNode([Map<String, dynamic>? node]) async {
    final out = await showModalBottomSheet<Map<String, Object?>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _NodeEditor(node: node),
    );
    if (out == null) return;
    await _op({'action': 'node_upsert', 'node': {if (node != null) 'id': node['id'], ...out}}, ok: node == null ? '加了一个节点' : '改好了');
  }

  Future<void> _writeBreakpoint(Map<String, dynamic> node) async {
    final ctrl = TextEditingController(text: '${node['lastBreakpoint'] ?? ''}');
    final text = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('断点：下一局从哪接'),
        content: TextField(
          key: const ValueKey('node-breakpoint-input'),
          controller: ctrl,
          maxLines: 3,
          decoration: const InputDecoration(hintText: '例如：做到第 85 题，基期差公式还卡，下局先复盘错题'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('算了')),
          FilledButton(onPressed: () => Navigator.of(ctx).pop(ctrl.text.trim()), child: const Text('记下')),
        ],
      ),
    );
    ctrl.dispose();
    if (text == null) return;
    await _op({'action': 'node_breakpoint', 'nodeId': node['id'], 'breakpoint': text}, ok: '断点记下了，下一局开局就带上');
  }

  Future<void> _nodeMenu(Map<String, dynamic> node) async {
    final passed = node['status'] == 'passed';
    final pick = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(leading: const Icon(AppIcons.edit), title: const Text('改节点（标题 / 日子 / 交付物 / 预算）'), onTap: () => Navigator.of(ctx).pop('edit')),
            ListTile(leading: const Icon(AppIcons.history), title: const Text('往后挪 3 天'), onTap: () => Navigator.of(ctx).pop('extend')),
            if (!passed)
              ListTile(
                key: const ValueKey('node-mark-passed'),
                leading: const Icon(AppIcons.success),
                title: const Text('标记达成'),
                onTap: () => Navigator.of(ctx).pop('passed'),
              )
            else
              ListTile(leading: const Icon(AppIcons.refresh), title: const Text('重新打开'), onTap: () => Navigator.of(ctx).pop('reopen')),
            ListTile(
              leading: Icon(AppIcons.delete, color: DshColors.of(ctx).danger),
              title: Text('删掉这个节点', style: TextStyle(color: DshColors.of(ctx).danger)),
              onTap: () => Navigator.of(ctx).pop('delete'),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    switch (pick) {
      case 'edit':
        await _editNode(node);
      case 'extend':
        await _op({'action': 'node_extend', 'nodeId': node['id'], 'days': 3}, ok: '往后挪了 3 天');
      case 'passed':
        await _op({'action': 'node_status', 'nodeId': node['id'], 'status': 'passed'}, ok: '节点达成');
      case 'reopen':
        await _op({'action': 'node_status', 'nodeId': node['id'], 'status': 'active'}, ok: '重新打开了');
      case 'delete':
        await _op({'action': 'node_delete', 'nodeId': node['id']}, ok: '删掉了');
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final h = _horizon;
    final nodes = [..._list(h?['nodes'])]..sort((a, b) => '${a['targetDate'] ?? '9999'}'.compareTo('${b['targetDate'] ?? '9999'}'));
    final alerts = {
      for (final a in _list(_summary['alerts']))
        '${a['nodeId']}': a['alert'] is Map ? Map<String, dynamic>.from(a['alert'] as Map) : const <String, dynamic>{},
    };
    final spent = _int(_summary['spentMinutes']);
    final budget = _int(_summary['budgetMinutes']);
    final nextId = '${_summary['nextNodeId'] ?? ''}';

    return AppPage.list(
      title: h == null ? '战役档案' : '${h['title']}',
      subtitle: Text(h == null ? '读取中…' : '战役档案${'${h['due'] ?? ''}'.isNotEmpty ? ' · 截止 ${h['due']}' : ''}'),
      actions: [GlassIconButton(tooltip: '刷新', icon: AppIcons.refresh, onPressed: () => unawaited(_load()))],
      children: [
        if (_loading)
          const ListPageCard(children: [ListPageRow(icon: AppIcons.loading, title: '读取中…')])
        else if (h == null)
          const ListPageCard(children: [ListPageRow(icon: AppIcons.warning, title: '读不到这条目标（引擎没应答或已删除）')])
        else ...[
          OverviewCard(
            leading: const OverviewGlyph(AppIcons.planMode, tint: 4),
            title: Text('节点 ${_int(_summary['passed'])}/${_int(_summary['nodes'])} 达成'),
            subtitle: Text(
              budget > 0
                  ? '已投 ${(spent / 60).toStringAsFixed(1)} 小时 / 预算 ${(budget / 60).toStringAsFixed(1)} 小时'
                  : '还没有节点：先把目标拆成几个有日子、有交付物的节点',
            ),
            trailing: alerts.isEmpty ? null : StatusChip(tone: StatusTone.warn, label: '${alerts.length} 个要调盘'),
          ),
          for (final n in nodes) _nodeCard(theme, colors, n, alerts['${n['id']}'], isNext: n['id'] == nextId),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            child: OutlinedButton.icon(
              key: const ValueKey('dossier-add-node'),
              style: OutlinedButton.styleFrom(shape: AppShape.card, minimumSize: const Size.fromHeight(44)),
              onPressed: _busy ? null : () => unawaited(_editNode()),
              icon: const Icon(AppIcons.add, size: 18),
              label: const Text('加一个预留节点'),
            ),
          ),
        ],
      ],
    );
  }

  Widget _nodeCard(ThemeData theme, DshColors colors, Map<String, dynamic> n, Map<String, dynamic>? alert, {required bool isNext}) {
    final status = '${n['status'] ?? 'planned'}';
    final (label, tone) = _kNodeStatus[status] ?? ('预留', StatusTone.idle);
    final spent = _int(n['spentMinutes']);
    final budget = _int(n['budgetMinutes']);
    final ratio = budget > 0 ? (spent / budget).clamp(0.0, 1.0) : 0.0;
    final log = _list(n['executionLog']).reversed.toList();
    final open = _expanded.contains('${n['id']}');
    final passed = status == 'passed';
    final bp = '${n['lastBreakpoint'] ?? ''}'.trim();
    final deliverable = '${n['deliverable'] ?? ''}'.trim();

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: GlassSurface(
        key: ValueKey('node-card-${n['id']}'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 8, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '${n['title']}',
                      style: theme.textTheme.titleMedium?.copyWith(color: passed ? colors.textTertiary : null),
                    ),
                  ),
                  if (isNext && !passed) const Padding(padding: EdgeInsets.only(right: 6), child: StatusChip(tone: StatusTone.busy, label: '下一个')),
                  StatusChip(tone: tone, label: label),
                  IconButton(
                    tooltip: '节点操作',
                    icon: const Icon(AppIcons.more, size: 20),
                    onPressed: _busy ? null : () => unawaited(_nodeMenu(n)),
                  ),
                ],
              ),
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Text(
                  [
                    if ('${n['targetDate'] ?? ''}'.isNotEmpty) '预留到 ${n['targetDate']}',
                    '预计 ${_int(n['budgetBouts'])} 局',
                    '已投 $spent/$budget 分钟',
                  ].join(' · '),
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                ),
              ),
              const SizedBox(height: 8),
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: LinearProgressIndicator(
                    value: ratio,
                    minHeight: 6,
                    backgroundColor: theme.colorScheme.outlineVariant.withValues(alpha: 0.35),
                    color: ratio >= 0.8 && !passed ? colors.warning : (passed ? colors.success : theme.colorScheme.primary),
                  ),
                ),
              ),
              if (deliverable.isNotEmpty) ...[
                const SizedBox(height: 10),
                Text('交付：$deliverable', style: theme.textTheme.bodyMedium),
              ],
              if (bp.isNotEmpty) ...[
                const SizedBox(height: 4),
                Text('断点：$bp', style: theme.textTheme.bodySmall?.copyWith(color: colors.info)),
              ],
              if (alert != null && alert.isNotEmpty) ...[
                const SizedBox(height: 8),
                Row(
                  children: [
                    Icon(AppIcons.warning, size: 16, color: colors.warning),
                    const SizedBox(width: 6),
                    Expanded(child: Text('${alert['text'] ?? ''}', style: theme.textTheme.bodySmall?.copyWith(color: colors.warning))),
                    TextButton(
                      onPressed: _busy ? null : () => unawaited(_nodeMenu(n)),
                      child: const Text('调盘'),
                    ),
                  ],
                ),
              ],
              if (log.isNotEmpty) ...[
                const SizedBox(height: 6),
                InkWell(
                  onTap: () => setState(() => open ? _expanded.remove('${n['id']}') : _expanded.add('${n['id']}')),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Row(
                      children: [
                        Icon(open ? AppIcons.collapse : AppIcons.expand, size: 16, color: colors.textTertiary),
                        const SizedBox(width: 6),
                        Text('干过 ${log.length} 局', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                      ],
                    ),
                  ),
                ),
                for (final e in (open ? log : log.take(1)))
                  Padding(
                    padding: const EdgeInsets.only(left: 22, right: 8, bottom: 4),
                    child: Text(
                      '${e['day'] ?? ''} · ${_int(e['minutes'])} 分钟 · ${_kOutcome['${e['outcome']}'] ?? e['outcome']}${'${e['summary'] ?? ''}'.isNotEmpty ? ' · ${e['summary']}' : ''}',
                      style: theme.textTheme.bodySmall,
                    ),
                  ),
              ],
              const SizedBox(height: 8),
              Row(
                children: [
                  TextButton.icon(
                    onPressed: _busy ? null : () => unawaited(_writeBreakpoint(n)),
                    icon: const Icon(AppIcons.edit, size: 16),
                    label: const Text('写断点'),
                  ),
                  const Spacer(),
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: FilledButton.icon(
                      key: ValueKey('node-start-${n['id']}'),
                      style: FilledButton.styleFrom(shape: AppShape.card),
                      onPressed: _busy || passed ? null : () => unawaited(_startFromNode(n)),
                      icon: const Icon(AppIcons.play, size: 18),
                      label: const Text('从这里开一局'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 加 / 改一个节点：标题、预留到哪天、交付物、预计几局、每局多久。
class _NodeEditor extends StatefulWidget {
  const _NodeEditor({this.node});

  final Map<String, dynamic>? node;

  @override
  State<_NodeEditor> createState() => _NodeEditorState();
}

class _NodeEditorState extends State<_NodeEditor> {
  late final TextEditingController _title = TextEditingController(text: '${widget.node?['title'] ?? ''}');
  late final TextEditingController _deliverable = TextEditingController(text: '${widget.node?['deliverable'] ?? ''}');
  late String _date = '${widget.node?['targetDate'] ?? ''}';
  late int _bouts = (widget.node?['budgetBouts'] as num?)?.toInt() ?? 3;
  late int _per = () {
    final b = (widget.node?['budgetBouts'] as num?)?.toInt() ?? 0;
    final m = (widget.node?['budgetMinutes'] as num?)?.toInt() ?? 0;
    final per = b > 0 ? (m / b).round() : 45;
    return const [25, 45, 60, 90].reduce((a, c) => (c - per).abs() < (a - per).abs() ? c : a);
  }();

  @override
  void dispose() {
    _title.dispose();
    _deliverable.dispose();
    super.dispose();
  }

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final init = DateTime.tryParse(_date) ?? now.add(const Duration(days: 7));
    final d = await showDatePicker(context: context, initialDate: init, firstDate: now.subtract(const Duration(days: 30)), lastDate: now.add(const Duration(days: 730)));
    if (d == null) return;
    setState(() => _date = '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: EdgeInsets.fromLTRB(24, 0, 24, 16 + MediaQuery.viewInsetsOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(widget.node == null ? '加一个预留节点' : '改节点', style: theme.textTheme.titleMedium),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('node-title-input'),
            controller: _title,
            decoration: const InputDecoration(labelText: '节点（这一段要攻下什么）', hintText: '例如：资料分析 200 题'),
          ),
          const SizedBox(height: 8),
          TextField(
            key: const ValueKey('node-deliverable-input'),
            controller: _deliverable,
            decoration: const InputDecoration(labelText: '交付物（怎么算达成）', hintText: '例如：正确率 ≥85%，提炼 5 条速算规则'),
          ),
          const SizedBox(height: 8),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(AppIcons.calendar),
            title: Text(_date.isEmpty ? '预留到哪天（点选）' : '预留到 $_date'),
            onTap: () => unawaited(_pickDate()),
          ),
          Row(
            children: [
              const Text('预计'),
              IconButton(onPressed: _bouts > 1 ? () => setState(() => _bouts--) : null, icon: const Icon(AppIcons.remove)),
              Text('$_bouts 局', style: theme.textTheme.titleMedium),
              IconButton(onPressed: _bouts < 30 ? () => setState(() => _bouts++) : null, icon: const Icon(AppIcons.add)),
            ],
          ),
          SegmentedPill<int>(
            height: 34,
            segments: const [PillSegment(25, '25 分'), PillSegment(45, '45 分'), PillSegment(60, '60 分'), PillSegment(90, '90 分')],
            selected: _per,
            onChanged: (v) => setState(() => _per = v),
          ),
          const SizedBox(height: 16),
          FilledButton(
            key: const ValueKey('node-save'),
            style: FilledButton.styleFrom(shape: AppShape.card, minimumSize: const Size.fromHeight(44)),
            onPressed: () {
              if (_title.text.trim().isEmpty) return;
              Navigator.of(context).pop(<String, Object?>{
                'title': _title.text.trim(),
                'deliverable': _deliverable.text.trim(),
                'targetDate': _date,
                'budgetBouts': _bouts,
                'budgetMinutes': _bouts * _per,
              });
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }
}
