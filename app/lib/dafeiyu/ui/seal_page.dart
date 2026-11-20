import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'app_picker_dialog.dart';

/// 证据类型：只有代码验得了的才能封（纯口头永远不算）。
const Map<String, (String, String, IconData)> kSealKinds = {
  'quiz': ('DSH 题库', '题', AppIcons.question),
  'external_quiz': ('外部刷题', '题', AppIcons.school),
  'file': ('笔记文件', '字', AppIcons.document),
  'screenshots': ('截图', '张', AppIcons.gallery),
  'app_minutes': ('App 用时', '分钟', AppIcons.time),
};

String sealHm(int m) => '${(m ~/ 60).toString().padLeft(2, '0')}:${(m % 60).toString().padLeft(2, '0')}';

(StatusTone, String) sealStatus(Map<String, dynamic> s, {required bool locking}) => switch ('${s['status']}') {
      'stretch' => (StatusTone.ok, '过拉伸'),
      'floor' => (StatusTone.ok, '过保底'),
      'waived' => (StatusTone.idle, '豁免'),
      'below' => (locking ? StatusTone.error : StatusTone.warn, locking ? '锁着' : '没过保底'),
      _ => (StatusTone.busy, '进行中'),
    };

/// 封印：前一晚冷静时立的约。只能加码、代码判、到点没过保底进严格锁、按档发娱乐分钟。
///
/// 23:00 前封明天的算数；过了 23:00 或当天现定的，奖励打七折。
class SealPage extends StatefulWidget {
  const SealPage({super.key, this.channel, this.loop});

  final ProactiveChannel? channel;
  final LoopChannel? loop;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) => Navigator.of(context)
      .push(MaterialPageRoute<void>(builder: (_) => SealPage(channel: channel)));

  @override
  State<SealPage> createState() => _SealPageState();
}

class _SealPageState extends State<SealPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final LoopChannel _loop = widget.loop ?? LoopChannel();
  Map<String, dynamic> _data = const {};
  bool _loading = true;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load({String action = 'list'}) async {
    final r = await _channel.sealsOp({'action': action});
    if (!mounted) return;
    setState(() {
      _loading = false;
      _failed = r == null || r['ok'] != true;
      if (r != null && r['ok'] == true) _data = r;
    });
  }

  Future<void> _op(Map<String, Object?> body, {String done = '好了'}) async {
    final r = await _channel.sealsOp(body);
    if (!mounted) return;
    final ok = r?['ok'] == true;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(ok ? done : '${r?['text'] ?? (r == null ? '连不上引擎，没改成' : '没改成（${r['error']}）')}')),
    );
    if (ok) {
      setState(() => _data = r!);
    }
  }

  List<Map<String, dynamic>> get _items =>
      _data['items'] is List ? [for (final x in _data['items'] as List) if (x is Map) Map<String, dynamic>.from(x)] : const [];

  // ------------------------------------------------------------------ 编辑器

  Future<void> _add({required bool today}) async {
    final input = await showModalBottomSheet<Map<String, Object?>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => _SealEditor(
        today: today,
        cutoffPassed: _data['cutoffPassed'] == true,
        minuteOfDay: (_data['minuteOfDay'] as num?)?.toInt() ?? 0,
        loop: _loop,
      ),
    );
    if (input == null || !mounted) return;
    await _op({'action': 'add', 'seal': input}, done: today ? '封好了（当天现定，奖励七折）' : '封好了：明天只能加码，不能改小');
  }

  Future<int?> _askNumber(String title, String hint, int initial) async {
    final c = TextEditingController(text: '$initial');
    final v = await showDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          key: const ValueKey('seal-number'),
          controller: c,
          autofocus: true,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: InputDecoration(helperText: hint),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.of(ctx).pop(int.tryParse(c.text.trim())), child: const Text('确定')),
        ],
      ),
    );
    c.dispose();
    return v;
  }

  Future<String?> _askReason() async {
    final c = TextEditingController();
    final v = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('豁免这一条'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('要写一句理由，占今天一次紧急解除；原来的数字留着，周验收看得见。'),
            const SizedBox(height: 12),
            TextField(key: const ValueKey('seal-reason'), controller: c, autofocus: true, maxLength: 120),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.of(ctx).pop(c.text.trim()), child: const Text('豁免')),
        ],
      ),
    );
    c.dispose();
    return v;
  }

  Future<void> _actions(Map<String, dynamic> s) async {
    final kind = '${s['kind']}';
    final unit = kSealKinds[kind]?.$2 ?? '';
    final done = s['status'] == 'floor' || s['status'] == 'stretch' || s['status'] == 'waived';
    final isToday = s['day'] == _data['today'];
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
              child: Text('${s['title']}', style: Theme.of(ctx).textTheme.titleMedium),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Text('拉伸 ${s['stretch']}$unit · 保底 ${s['floor']}$unit · ${sealHm((s['dueMinute'] as num).toInt())} 前；只能加码。'),
            ),
            if (isToday)
              ListTile(
                key: const ValueKey('seal-act-check'),
                leading: const Icon(AppIcons.refresh),
                title: const Text('现在判一次'),
                subtitle: const Text('读题库 / 文件 / 截图 / App 用时，过档就发分钟'),
                onTap: () => Navigator.of(ctx).pop('check'),
              ),
            if (isToday && kind == 'external_quiz' && !done)
              ListTile(
                key: const ValueKey('seal-act-claim'),
                leading: const Icon(AppIcons.edit),
                title: const Text('报一下做了几题'),
                subtitle: const Text('要有今天的截图；按该 App 用时封顶（每题至少半分钟）'),
                onTap: () => Navigator.of(ctx).pop('claim'),
              ),
            if (!done)
              ListTile(
                key: const ValueKey('seal-act-raise'),
                leading: const Icon(AppIcons.increment),
                title: const Text('加码'),
                subtitle: const Text('拉伸只能往上调，调了就回不去'),
                onTap: () => Navigator.of(ctx).pop('raise'),
              ),
            if (isToday && !done)
              ListTile(
                key: const ValueKey('seal-act-waive'),
                leading: Icon(AppIcons.attention, color: DshColors.of(ctx).warning),
                title: const Text('豁免（写理由）'),
                subtitle: const Text('占一次紧急解除，不开放行窗口'),
                onTap: () => Navigator.of(ctx).pop('waive'),
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (picked == null || !mounted) return;
    final id = '${s['id']}';
    switch (picked) {
      case 'check':
        await _op({'action': 'check'}, done: '判过了');
      case 'claim':
        final n = await _askNumber('今天做了几题', '只能往上报；没截图不算', (s['claimed'] as num?)?.toInt() ?? 0);
        if (n != null) await _op({'action': 'claim', 'id': id, 'count': n}, done: '记下了');
      case 'raise':
        final cur = (s['stretch'] as num).toInt();
        final n = await _askNumber('拉伸加到多少$unit', '至少 ${cur + 1}；保底跟着按一半算（不会变低）', cur + 1);
        if (n != null) {
          final floor = ((n + 1) ~/ 2) > (s['floor'] as num).toInt() ? (n + 1) ~/ 2 : (s['floor'] as num).toInt();
          await _op({'action': 'raise', 'id': id, 'stretch': n, 'floor': floor}, done: '加码了');
        }
      case 'waive':
        final why = await _askReason();
        if (why != null && why.isNotEmpty) await _op({'action': 'waive', 'id': id, 'reason': why}, done: '豁免了，记进账里');
    }
  }

  // ------------------------------------------------------------------ 页面

  ListPageRow _row(Map<String, dynamic> s, {required bool locking}) {
    final colors = DshColors.of(context);
    final k = kSealKinds['${s['kind']}'];
    final unit = k?.$2 ?? '';
    final (tone, label) = sealStatus(s, locking: locking);
    final paid = (s['paidMinutes'] as num?)?.toInt() ?? 0;
    final isToday = s['day'] == _data['today'];
    return ListPageRow(
      key: ValueKey('seal-row-${s['id']}'),
      icon: k?.$3 ?? AppIcons.verified,
      iconColor: tone == StatusTone.ok ? colors.success : (tone == StatusTone.error ? colors.danger : null),
      title: '${s['title']}${s['sameDay'] == true ? '（现定）' : ''}',
      subtitle: isToday
          ? '${s['actual']}/${s['stretch']}$unit · 保底 ${s['floor']} · ${sealHm((s['dueMinute'] as num).toInt())} 前'
          : '拉伸 ${s['stretch']}$unit · 保底 ${s['floor']} · ${sealHm((s['dueMinute'] as num).toInt())} 前 · ${k?.$1 ?? ''}',
      value: paid > 0 ? '+$paid 分钟' : null,
      badge: isToday ? StatusChip(tone: tone, label: label) : null,
      chevron: true,
      onTap: () => unawaited(_actions(s)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final today = '${_data['today'] ?? ''}';
    final tomorrow = '${_data['tomorrow'] ?? ''}';
    final items = _items;
    final todays = items.where((x) => x['day'] == today).toList()..sort((a, b) => (a['dueMinute'] as num).compareTo(b['dueMinute'] as num));
    final tomorrows = items.where((x) => x['day'] == tomorrow).toList()..sort((a, b) => (a['dueMinute'] as num).compareTo(b['dueMinute'] as num));
    final locking = _data['locking'] is Map ? Map<String, dynamic>.from(_data['locking'] as Map) : null;
    final passed = todays.where((x) => x['status'] == 'floor' || x['status'] == 'stretch').length;
    final cutoffPassed = _data['cutoffPassed'] == true;

    return AppPage.list(
      title: '封印',
      subtitle: const Text('前一晚冷静时立约，第二天只执行'),
      actions: [GlassIconButton(tooltip: '现在判一次', icon: AppIcons.refresh, onPressed: () => unawaited(_load(action: 'check')))],
      children: [
        OverviewCard(
          leading: const OverviewGlyph(AppIcons.lock, tint: 5),
          title: const Text('今天要交的'),
          subtitle: Text(
            _loading
                ? '读取中…'
                : _failed && items.isEmpty
                    ? '引擎没应答；封印要引擎在线'
                    : todays.isEmpty
                        ? '今天没有封印 · 明天 ${tomorrows.length} 条'
                        : '今天 ${todays.length} 条，过了 $passed 条 · 明天 ${tomorrows.length} 条',
          ),
          trailing: locking != null
              ? const StatusChip(tone: StatusTone.error, label: '锁着')
              : (cutoffPassed ? const StatusChip(tone: StatusTone.warn, label: '过了 23:00') : null),
        ),
        if (locking != null)
          ListPageCard(
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              ListPageRow(
                key: const ValueKey('seal-locking'),
                icon: AppIcons.lockActive,
                iconColor: colors.danger,
                title: '「${locking['title']}」到点没过保底',
                subtitle: '交够 ${locking['floor']}${kSealKinds['${locking['kind']}']?.$2 ?? ''} 就解；最长一小时。紧急解除照常能用。',
                subtitleMaxLines: 3,
              ),
            ],
          ),
        ListPageCard(
          key: const ValueKey('seal-today'),
          title: '今天',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            for (final s in todays) _row(s, locking: locking?['id'] == s['id']),
            ListPageRow(
              key: const ValueKey('seal-add-today'),
              icon: AppIcons.add,
              title: '今天现定一条',
              subtitle: '没提前封的也能补，但奖励打七折',
              chevron: true,
              onTap: () => unawaited(_add(today: true)),
            ),
          ],
        ),
        ListPageCard(
          key: const ValueKey('seal-tomorrow'),
          title: '明天${tomorrow.length >= 10 ? ' · ${tomorrow.substring(5)}' : ''}',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            for (final s in tomorrows) _row(s, locking: false),
            ListPageRow(
              key: const ValueKey('seal-add-tomorrow'),
              icon: AppIcons.addEntry,
              iconColor: Theme.of(context).colorScheme.primary,
              title: '封明天一条',
              subtitle: cutoffPassed ? '已过 23:00：现在封的算当天现定，奖励七折' : '23:00 前封的算数；封了只能加码',
              subtitleMaxLines: 2,
              chevron: true,
              onTap: () => unawaited(_add(today: false)),
            ),
          ],
        ),
        ListPageCard(
          title: '规矩',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          children: const [
            ListPageRow(icon: AppIcons.verified, title: '只看代码验得了的', subtitle: '题库答对数、文件字数、截图、App 用时；嘴上说的不算', subtitleMaxLines: 2),
            ListPageRow(icon: AppIcons.lock, title: '到点没过保底就锁', subtitle: '1 分钟缓冲后严格锁，交够保底就解，最长一小时', subtitleMaxLines: 2),
            ListPageRow(icon: AppIcons.balance, title: '按档发娱乐分钟', subtitle: '过拉伸 ×1.25 · 过保底 ×1 · 现定 ×0.7 · 晚交 ×0.5', subtitleMaxLines: 2),
            ListPageRow(icon: AppIcons.increment, title: '只能加码', subtitle: '姐妹、Gemini 都改不了；想改小就写理由豁免', subtitleMaxLines: 2),
          ],
        ),
      ],
    );
  }
}

/// 封一条的编辑器（底部弹层）。
class _SealEditor extends StatefulWidget {
  const _SealEditor({required this.today, required this.cutoffPassed, required this.minuteOfDay, required this.loop});

  final bool today;
  final bool cutoffPassed;
  final int minuteOfDay;
  final LoopChannel loop;

  @override
  State<_SealEditor> createState() => _SealEditorState();
}

class _SealEditorState extends State<_SealEditor> {
  final _title = TextEditingController();
  final _stretch = TextEditingController(text: '20');
  final _floor = TextEditingController();
  final _est = TextEditingController(text: '60');
  final _path = TextEditingController();
  String _kind = 'quiz';
  late int _due = widget.today ? ((widget.minuteOfDay ~/ 60) + 2).clamp(0, 23) * 60 : 18 * 60;
  LoopApp? _app;
  String? _error;

  @override
  void dispose() {
    for (final c in [_title, _stretch, _floor, _est, _path]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _pickApp() async {
    final picked = await showInstalledAppsPicker(context, title: '选一个 App', loop: widget.loop, confirmLabel: '就它');
    if (picked == null || picked.isEmpty || !mounted) return;
    setState(() => _app = picked.first);
  }

  Future<void> _pickDue() async {
    final t = await showTimePicker(context: context, initialTime: TimeOfDay(hour: _due ~/ 60, minute: _due % 60));
    if (t == null || !mounted) return;
    setState(() => _due = t.hour * 60 + t.minute);
  }

  void _submit() {
    final stretch = int.tryParse(_stretch.text.trim()) ?? 0;
    final floor = int.tryParse(_floor.text.trim());
    final needApp = _kind == 'app_minutes' || _kind == 'external_quiz';
    final err = _title.text.trim().isEmpty
        ? '写一句交什么'
        : stretch < 1
            ? '拉伸目标至少 1'
            : (floor != null && (floor < 1 || floor > stretch))
                ? '保底要在 1 到拉伸之间'
                : (_kind == 'file' && _path.text.trim().isEmpty)
                    ? '写清是哪份笔记（笔记库里的路径）'
                    : (needApp && _app == null)
                        ? '选一个 App'
                        : (widget.today && _due <= widget.minuteOfDay)
                            ? '截止时间已经过了'
                            : null;
    if (err != null) {
      setState(() => _error = err);
      return;
    }
    Navigator.of(context).pop(<String, Object?>{
      'title': _title.text.trim(),
      'kind': _kind,
      'stretch': stretch,
      'floor': ?floor,
      'dueMinute': _due,
      'estMinutes': int.tryParse(_est.text.trim()) ?? 60,
      if (_kind == 'file') 'path': _path.text.trim(),
      if (needApp) 'app': _app!.package,
      if (needApp) 'appLabel': _app!.label,
      'target': widget.today ? 'today' : 'tomorrow',
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final unit = kSealKinds[_kind]!.$2;
    final stretch = int.tryParse(_stretch.text.trim()) ?? 0;
    final discount = widget.today || widget.cutoffPassed;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(widget.today ? '今天现定一条' : '封明天一条', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(
                discount ? '封了只能加码。这一条奖励打七折（当天现定 / 过了 23:00）。' : '封了只能加码，明天的你改不了它。',
                style: TextStyle(color: discount ? colors.warning : colors.textTertiary),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const ValueKey('seal-title'),
                controller: _title,
                maxLength: 80,
                decoration: const InputDecoration(labelText: '交什么', hintText: '例：资料分析 40 题'),
              ),
              const SizedBox(height: 4),
              Text('证据', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 6),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final e in kSealKinds.entries)
                    ChoiceChip(
                      key: ValueKey('seal-kind-${e.key}'),
                      label: Text(e.value.$1),
                      selected: _kind == e.key,
                      onSelected: (_) => setState(() => _kind = e.key),
                    ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      key: const ValueKey('seal-stretch'),
                      controller: _stretch,
                      keyboardType: TextInputType.number,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: InputDecoration(labelText: '拉伸（$unit）'),
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      key: const ValueKey('seal-floor'),
                      controller: _floor,
                      keyboardType: TextInputType.number,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: InputDecoration(labelText: '保底（$unit）', hintText: '默认 ${(stretch + 1) ~/ 2}'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const ValueKey('seal-due'),
                      icon: const Icon(AppIcons.time),
                      label: Text('${sealHm(_due)} 前'),
                      onPressed: () => unawaited(_pickDue()),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      key: const ValueKey('seal-est'),
                      controller: _est,
                      keyboardType: TextInputType.number,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: const InputDecoration(labelText: '大概要几分钟'),
                    ),
                  ),
                ],
              ),
              if (_kind == 'file') ...[
                const SizedBox(height: 12),
                TextField(
                  key: const ValueKey('seal-path'),
                  controller: _path,
                  decoration: const InputDecoration(labelText: '哪份笔记', hintText: '笔记库里的路径，如 学习/行测.md'),
                ),
              ],
              if (_kind == 'app_minutes' || _kind == 'external_quiz') ...[
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  key: const ValueKey('seal-app'),
                  icon: const Icon(AppIcons.allFeatures),
                  label: Text(_app == null ? '选 App' : _app!.label),
                  onPressed: () => unawaited(_pickApp()),
                ),
              ],
              if (_error != null) ...[
                const SizedBox(height: 10),
                Text(_error!, style: TextStyle(color: colors.danger)),
              ],
              const SizedBox(height: 16),
              FilledButton(key: const ValueKey('seal-submit'), onPressed: _submit, child: const Text('封印')),
            ],
          ),
        ),
      ),
    );
  }
}
