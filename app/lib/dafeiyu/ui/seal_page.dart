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
  'bout_minutes': ('通过验收的局', '分钟', AppIcons.workflow),
  'gemini_receipt': ('Gemini 回执', '条', AppIcons.chat),
  'study_cards': ('学习制卡', '张', AppIcons.skill),
};

/// 时间轴上的一段：「09:00–10:30」或「10:30 前」。
String sealSpan(Map<String, dynamic> s) {
  final due = (s['dueMinute'] as num?)?.toInt() ?? 0;
  final start = (s['startMinute'] as num?)?.toInt() ?? -1;
  return start >= 0 ? '${sealHm(start)}–${sealHm(due)}' : '${sealHm(due)} 前';
}

String sealHm(int m) => '${(m ~/ 60).toString().padLeft(2, '0')}:${(m % 60).toString().padLeft(2, '0')}';

/// 谁定的（和引擎 SOURCE_LABEL 同一套说法）。
const Map<String, String> kSealSourceLabel = {
  'night': '你前一晚封的', 'morning': '你开工前定的', 'live': '你当天现加的', 'template': '模板 / 本周计划自动套的',
  'ai': '两条鱼提议的', 'gemini': 'Gemini 提议的',
};

(StatusTone, String) sealStatus(Map<String, dynamic> s, {required bool locking}) => s['confirmed'] == false && s['status'] != 'waived'
    ? (StatusTone.warn, '待你点头')
    : switch ('${s['status']}') {
      'stretch' => (StatusTone.ok, '过拉伸'),
      'floor' => (StatusTone.ok, '过保底'),
      'waived' => (StatusTone.idle, '豁免'),
      'below' => (locking ? StatusTone.error : StatusTone.warn, locking ? '锁着' : '没过保底'),
      _ => (StatusTone.busy, '进行中'),
    };

/// 验收点：今天按时间轴验（到开始时刻自动开局、到截止时刻代码判、没过保底进严格锁），明天的前一晚封。
/// 只能加码、按档发娱乐分钟。前一晚 23:00 前封的、当天 10:00 前且开工前定的不打折；开工后现加的七折。
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

  /// 今天这一条算不算「开工前定的」（不打折）。
  bool get _todayCold {
    final cut = (_data['coldCutoffMinute'] as num?)?.toInt() ?? 600;
    final min = (_data['minuteOfDay'] as num?)?.toInt() ?? 0;
    return _data['workStarted'] != true && min < cut;
  }

  Future<void> _add({required bool today}) async {
    final input = await showModalBottomSheet<Map<String, Object?>>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => _SealEditor(
        today: today,
        cutoffPassed: today ? !_todayCold : _data['cutoffPassed'] == true,
        minuteOfDay: (_data['minuteOfDay'] as num?)?.toInt() ?? 0,
        loop: _loop,
      ),
    );
    if (input == null || !mounted) return;
    await _op({'action': 'add', 'seal': input},
        done: today ? (_todayCold ? '加上了：到开始时刻自动开局' : '加上了（开工后现加，奖励七折）') : '封好了：明天只能加码，不能改小');
  }

  Future<int?> _askNumber(String title, String hint, int initial) async {
    final v = await showDialog<String>(
      context: context,
      builder: (_) => _PromptDialog(
        title: title,
        initial: '$initial',
        helper: hint,
        number: true,
        fieldKey: const ValueKey('seal-number'),
        confirm: '确定',
      ),
    );
    return v == null ? null : int.tryParse(v);
  }

  Future<String?> _askReason() => showDialog<String>(
        context: context,
        builder: (_) => const _PromptDialog(
          title: '豁免这一条',
          body: '要写一句理由，占今天一次紧急解除；原来的数字留着，周验收看得见。',
          maxLength: 120,
          fieldKey: ValueKey('seal-reason'),
          confirm: '豁免',
        ),
      );

  Future<void> _actions(Map<String, dynamic> s) async {
    final kind = '${s['kind']}';
    final unit = kSealKinds[kind]?.$2 ?? '';
    final done = s['status'] == 'floor' || s['status'] == 'stretch' || s['status'] == 'waived';
    final isToday = s['day'] == _data['today'];
    final proposal = s['confirmed'] == false && s['status'] != 'waived';
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
              child: Text('拉伸 ${s['stretch']}$unit · 保底 ${s['floor']}$unit · ${sealSpan(s)}；只能加码。'
                  '${kSealSourceLabel['${s['source']}'] == null ? '' : '\n来源：${kSealSourceLabel['${s['source']}']}'}'),
            ),
            if (proposal)
              ListTile(
                key: const ValueKey('seal-act-confirm'),
                leading: const Icon(AppIcons.success),
                title: const Text('点头：这条算数'),
                subtitle: const Text('点头后到点代码判，没过保底就锁；过了发娱乐分钟'),
                onTap: () => Navigator.of(ctx).pop('confirm'),
              ),
            if (proposal)
              ListTile(
                key: const ValueKey('seal-act-decline'),
                leading: const Icon(AppIcons.close),
                title: const Text('不要'),
                subtitle: const Text('你没答应过的提议，直接作废，不占紧急解除'),
                onTap: () => Navigator.of(ctx).pop('decline'),
              ),
            if (isToday && !done && ((s['openedAt'] as num?)?.toInt() ?? 0) == 0)
              ListTile(
                key: const ValueKey('seal-act-open'),
                leading: const Icon(AppIcons.play),
                title: const Text('现在开这一局'),
                subtitle: const Text('不等开始时刻；局通过验收的分钟数也记到这一点上'),
                onTap: () => Navigator.of(ctx).pop('open'),
              ),
            if (isToday)
              ListTile(
                key: const ValueKey('seal-act-check'),
                leading: const Icon(AppIcons.refresh),
                title: const Text('现在判一次'),
                subtitle: const Text('读题库 / 文件 / 截图 / App 用时 / 局 / 回执，过档就发分钟'),
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
      case 'confirm':
        await _op({'action': 'confirm', 'id': id}, done: '点头了：从现在起算数');
      case 'decline':
        await _op({'action': 'decline', 'id': id}, done: '不要了，作废');
      case 'check':
        await _op({'action': 'check'}, done: '判过了');
      case 'open':
        await _op({'action': 'open', 'id': id}, done: '开局了');
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

  /// 周冷数据包：复制给体系外的模型看（只有数字；末尾带固定提问）。
  Future<void> _copyWeek() async {
    final r = await _channel.sealsOp({'action': 'week'});
    if (!mounted) return;
    final text = '${r?['text'] ?? ''}';
    if (text.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('引擎没应答，没拿到数据')));
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('复制好了：粘贴给 Claude / GPT，让它只回一个【周验收】块')));
  }

  /// 粘贴【周验收】回来：解析出最多 3 条调整，再逐条决定采不采纳。
  Future<void> _pasteReview() async {
    final text = await showDialog<String>(
      context: context,
      builder: (_) => const _PromptDialog(
        title: '粘贴【周验收】',
        hint: '把对方回的整段粘进来（前后的废话不用删）',
        minLines: 4,
        maxLines: 10,
        fieldKey: ValueKey('week-review-text'),
        confirmKey: ValueKey('week-review-submit'),
        confirm: '解析',
      ),
    );
    if (text == null || text.trim().isEmpty || !mounted) return;
    final r = await _channel.sealsOp({'action': 'week-review', 'text': text});
    if (!mounted) return;
    if (r == null || r['ok'] != true) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${r?['text'] ?? '没解析出来'}')));
      return;
    }
    setState(() => _data = r);
    await _decideReview(Map<String, dynamic>.from(r['review'] as Map));
  }

  Future<void> _decideReview(Map<String, dynamic> review) async {
    final adj = review['adjustments'] is List ? [for (final a in review['adjustments'] as List) if (a is Map) '${a['text']}'] : <String>[];
    final picked = <int>{};
    final ok = await showModalBottomSheet<bool>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, set) => SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('周验收${review['by'] == 'gemini' ? '（Gemini 交来的）' : ''}', style: Theme.of(ctx).textTheme.titleMedium),
                const SizedBox(height: 6),
                Text('判断：${review['verdict'] ?? '—'}'),
                if ('${review['watch'] ?? ''}'.isNotEmpty) Text('下周盯：${review['watch']}'),
                const SizedBox(height: 10),
                Text('勾上要采纳的（采纳的写进笔记库，鲸澜这一周排验收点照着办；不自动改任何数字）：',
                    style: TextStyle(color: DshColors.of(ctx).textTertiary)),
                for (var i = 0; i < adj.length; i++)
                  CheckboxListTile(
                    key: ValueKey('week-adj-$i'),
                    value: picked.contains(i),
                    onChanged: (v) => set(() => v == true ? picked.add(i) : picked.remove(i)),
                    title: Text(adj[i]),
                    controlAffinity: ListTileControlAffinity.leading,
                    contentPadding: EdgeInsets.zero,
                  ),
                const SizedBox(height: 8),
                FilledButton(key: const ValueKey('week-decide'), onPressed: () => Navigator.of(ctx).pop(true), child: Text(picked.isEmpty ? '一条都不采纳' : '采纳 ${picked.length} 条')),
              ],
            ),
          ),
        ),
      ),
    );
    if (ok != true || !mounted) return;
    final r = await _channel.sealsOp({'action': 'week-decide', 'id': review['id'], 'accepted': picked.toList()..sort()});
    if (!mounted) return;
    if (r != null && r['ok'] == true) setState(() => _data = r);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(r?['ok'] == true ? '记下了' : '没存上')));
  }

  // ------------------------------------------------------------------ 今天的模式（超级验收者的底层设定）

  Map<String, dynamic> get _verify => _data['verify'] is Map ? Map<String, dynamic>.from(_data['verify'] as Map) : const {};

  String _clock(Object? ms) {
    final t = (ms as num?)?.toInt() ?? 0;
    if (t <= 0) return '';
    final d = DateTime.fromMillisecondsSinceEpoch(t);
    return '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }

  String _verifyLine(Map<String, dynamic> v) {
    if (v['active'] == true) {
      final until = (v['untilMinute'] as num?)?.toInt();
      return '开着：只放常用 App${until != null ? '（到 ${sealHm(until)}）' : ''}${v['reason'] == 'start' ? ' · 你说开工了' : ''}';
    }
    if (v['enabled'] != true) return '关着';
    final spend = v['spend'] is Map ? v['spend'] as Map : null;
    return switch ('${v['why']}') {
      'spend' => '你在花余额（到 ${_clock(spend?['until'])}），花完回来',
      'meal' => '吃饭时段，让路',
      'break' => '放风中，让路',
      'relax' => '申请到的放风段，让路',
      'emergency' => '紧急解除中，让路',
      'night' => '深夜：交给深夜锁',
      _ => '时段外（工作时段里自动开）',
    };
  }

  Future<void> _toggleVerify(bool on) async {
    if (!on) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('关掉默认验收？'),
          content: const Text('这是放松：明天起才生效，今天照旧。想反悔随时打开。'),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('不关了')),
            FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('明天起关')),
          ],
        ),
      );
      if (ok != true || !mounted) return;
    }
    await _opText({'action': on ? 'verify-on' : 'verify-off'});
  }

  /// 和 _op 一样，但成功时把插件回的那句话显示出来。
  Future<void> _opText(Map<String, Object?> body) async {
    final r = await _channel.sealsOp(body);
    if (!mounted) return;
    final ok = r?['ok'] == true;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${r?['text'] ?? (r == null ? '连不上引擎' : ok ? '好了' : '没成（${r['error']}）')}')));
    if (ok) setState(() => _data = r!);
  }

  Future<void> _spend() async {
    final out = await showDialog<(int, String)>(context: context, builder: (_) => const _SpendDialog());
    if (out == null || !mounted) return;
    await _opText({'action': 'spend', 'minutes': out.$1, 'reason': out.$2});
  }

  Future<void> _setEveningCap(int current) async {
    final v = await showDialog<String>(
      context: context,
      builder: (_) => _PromptDialog(
        title: '晚上的娱乐上限（分钟）',
        initial: '$current',
        helper: '30–180。调低立即生效；调高明天起（今晚不变）',
        number: true,
        fieldKey: const ValueKey('evening-cap-field'),
        confirm: '设定',
      ),
    );
    final n = int.tryParse(v ?? '');
    if (n == null || !mounted) return;
    await _opText({'action': 'evening-cap', 'minutes': n});
  }

  Widget _modeCard(DshColors colors) {
    final v = _verify;
    if (v.isEmpty) return const SizedBox.shrink();
    final ev = v['evening'] is Map ? Map<String, dynamic>.from(v['evening'] as Map) : const <String, dynamic>{};
    final cap = (ev['cap'] as num?)?.toInt() ?? 90;
    final next = ev['next'] is Map ? ev['next'] as Map : null;
    final pendingOff = '${v['offFrom'] ?? ''}'.isNotEmpty;
    return ListPageCard(
      key: const ValueKey('seal-mode'),
      title: '今天的模式',
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
      children: [
        ListPageRow(
          key: const ValueKey('mode-verify'),
          icon: v['active'] == true ? AppIcons.lockActive : AppIcons.lock,
          iconColor: v['active'] == true ? colors.info : null,
          title: '默认验收',
          subtitle: pendingOff ? '${_verifyLine(v)} · 明天（${v['offFrom']}）起关' : _verifyLine(v),
          subtitleMaxLines: 2,
          trailing: Switch.adaptive(
            key: const ValueKey('mode-verify-switch'),
            value: !pendingOff,
            onChanged: (on) => unawaited(_toggleVerify(on)),
          ),
        ),
        if (v['recovery'] == true)
          ListPageRow(
            key: const ValueKey('mode-recovery'),
            icon: AppIcons.attention,
            iconColor: colors.warning,
            title: '恢复日',
            subtitle: '连续两天没过保底：今天只要一套 10 题，不加码；连续两天交上自动退出',
            subtitleMaxLines: 2,
            badge: const StatusChip(tone: StatusTone.warn, label: '恢复日'),
          ),
        ListPageRow(
          key: const ValueKey('mode-evening'),
          icon: AppIcons.time,
          iconColor: ev['hit'] == true ? colors.danger : null,
          title: '晚上娱乐上限 $cap 分钟',
          subtitle: ev['hit'] == true
              ? '今晚用完了：锁到明早 7 点（余额留着明天用）'
              : ev['active'] == true
                  ? '今晚已用 ${ev['used'] ?? 0}/$cap 分钟（18:00 起算）'
                  : '18:00 到深夜锁之间，娱乐 + 游戏最多这么多${next != null ? ' · ${next['from']} 起 ${next['minutes']} 分钟' : ''}',
          subtitleMaxLines: 2,
          badge: ev['hit'] == true ? const StatusChip(tone: StatusTone.error, label: '用完了') : null,
          chevron: true,
          onTap: () => unawaited(_setEveningCap(cap)),
        ),
        ListPageRow(
          key: const ValueKey('mode-spend'),
          icon: AppIcons.play,
          title: '要玩：说一句理由，花挣来的余额',
          subtitle: '5–60 分钟，不超过余额；也可以直接跟鱼说',
          subtitleMaxLines: 2,
          chevron: true,
          onTap: () => unawaited(_spend()),
        ),
        ListPageRow(
          key: const ValueKey('mode-start'),
          icon: AppIcons.planMode,
          title: '开工',
          subtitle: '时段外也进默认验收（2 小时）；跟鱼说「开始了 / 冲刺」一样',
          subtitleMaxLines: 2,
          chevron: true,
          onTap: () => unawaited(_opText({'action': 'start', 'hours': 2})),
        ),
        ListPageRow(
          icon: AppIcons.document,
          title: '模板',
          subtitle: '${v['inVault'] == true ? '笔记库 大肥鱼/模板.md' : '手机里的 模板.md'}：没定今天时到点自动套；改了第二天生效'
              '${'${v['appliedTemplate'] ?? ''}'.isNotEmpty && v['appliedDay'] == _data['today'] ? ' · 今天套了「${v['appliedTemplate']}」' : ''}',
          subtitleMaxLines: 3,
        ),
      ],
    );
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
          ? '${sealSpan(s)} · ${s['actual']}/${s['stretch']}$unit · 保底 ${s['floor']}'
          : '${sealSpan(s)} · 拉伸 ${s['stretch']}$unit · 保底 ${s['floor']} · ${k?.$1 ?? ''}',
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
      title: '验收点',
      subtitle: const Text('今天按时间轴验，明天的前一晚封'),
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
        _modeCard(colors),
        ListPageCard(
          key: const ValueKey('seal-today'),
          title: '今天 · 时间轴',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            for (final s in todays) _row(s, locking: locking?['id'] == s['id']),
            ListPageRow(
              key: const ValueKey('seal-add-today'),
              icon: AppIcons.add,
              title: '加一个验收点',
              subtitle: _todayCold ? '10:00 前、开工前定的不打折；到开始时刻自动开局' : '开工后现加的奖励打七折',
              subtitleMaxLines: 2,
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
          key: const ValueKey('seal-week'),
          title: '周验收',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            if (_data['weekReview'] is Map)
              ListPageRow(
                key: const ValueKey('seal-week-pending'),
                icon: AppIcons.attention,
                iconColor: colors.warning,
                title: '${(_data['weekReview'] as Map)['by'] == 'gemini' ? 'Gemini' : '粘贴回来'}的周验收在等你定',
                subtitle: '${(_data['weekReview'] as Map)['verdict'] ?? ''}',
                subtitleMaxLines: 2,
                badge: const StatusChip(tone: StatusTone.warn, label: '待定'),
                chevron: true,
                onTap: () => unawaited(_decideReview(Map<String, dynamic>.from(_data['weekReview'] as Map))),
              ),
            ListPageRow(
              key: const ValueKey('seal-copy-week'),
              icon: AppIcons.copy,
              title: '复制这一周的冷数据',
              subtitle: '只有数字（过了几个、没过几个、豁免原话、紧急几次），末尾带固定提问；粘贴给体系外的模型。连着 Gemini 桥的话 Gemini 自己读得到',
              subtitleMaxLines: 3,
              chevron: true,
              onTap: () => unawaited(_copyWeek()),
            ),
            ListPageRow(
              key: const ValueKey('seal-paste-review'),
              icon: AppIcons.paste,
              title: '粘贴【周验收】回来',
              subtitle: '最多 3 条调整，你逐条决定采不采纳',
              chevron: true,
              onTap: () => unawaited(_pasteReview()),
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
  /// 开始时刻（可不填：按截止前「大概要几分钟」算）。到这个点推进器自动开局。
  int? _start;
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

  Future<void> _pickStart() async {
    final init = _start ?? (_due - 60).clamp(0, 1439);
    final t = await showTimePicker(context: context, initialTime: TimeOfDay(hour: init ~/ 60, minute: init % 60));
    if (t == null || !mounted) return;
    setState(() => _start = t.hour * 60 + t.minute);
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
                            : (_start != null && _start! >= _due)
                                ? '开始要在截止之前'
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
      'startMinute': ?_start,
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
    final discount = widget.cutoffPassed;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(widget.today ? '加一个验收点（今天）' : '封明天一条', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(
                discount
                    ? (widget.today ? '已经开工了：这一条奖励打七折。加了只能加码。' : '过了 23:00：这一条奖励打七折。封了只能加码。')
                    : (widget.today ? '开工前定的不打折。到开始时刻自动开局，到截止时刻代码判。' : '封了只能加码，明天的你改不了它。'),
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
                      key: const ValueKey('seal-start'),
                      icon: const Icon(AppIcons.play),
                      label: Text(_start == null ? '几点开始' : '${sealHm(_start!)} 开始'),
                      onPressed: () => unawaited(_pickStart()),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const ValueKey('seal-due'),
                      icon: const Icon(AppIcons.time),
                      label: Text('${sealHm(_due)} 前'),
                      onPressed: () => unawaited(_pickDue()),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
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
              if (_kind == 'study_cards') ...[
                const SizedBox(height: 12),
                Text(
                  '交卡当天先发一半奖励；隔天复习时答对够保底张数，再发另一半（当场刷、自己翻答案说记得都不算）。'
                  '算数的卡：Gemini 推的带选项卡、判「过」的回执里的问答、BrainOS 命题卡；跟鱼聊天随手记的进复习池，但不算张数。',
                  key: const ValueKey('seal-study-hint'),
                  style: TextStyle(color: colors.textTertiary),
                ),
              ],
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

/// 一个输入框的对话框：控制器归它自己管（对话框关闭动画结束、真正卸载时才 dispose）。
/// 以前在 showDialog 返回后立刻 dispose，关闭动画还在用它，会报「used after being disposed」。
class _PromptDialog extends StatefulWidget {
  const _PromptDialog({
    required this.title,
    required this.confirm,
    this.initial = '',
    this.body,
    this.hint,
    this.helper,
    this.number = false,
    this.maxLength,
    this.minLines,
    this.maxLines = 1,
    this.fieldKey,
    this.confirmKey,
  });

  final String title;
  final String confirm;
  final String initial;
  final String? body;
  final String? hint;
  final String? helper;
  final bool number;
  final int? maxLength;
  final int? minLines;
  final int maxLines;
  final Key? fieldKey;
  final Key? confirmKey;

  @override
  State<_PromptDialog> createState() => _PromptDialogState();
}

class _PromptDialogState extends State<_PromptDialog> {
  late final TextEditingController _c = TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final field = TextField(
      key: widget.fieldKey,
      controller: _c,
      autofocus: true,
      keyboardType: widget.number ? TextInputType.number : TextInputType.multiline,
      inputFormatters: widget.number ? [FilteringTextInputFormatter.digitsOnly] : null,
      maxLength: widget.maxLength,
      minLines: widget.minLines,
      maxLines: widget.maxLines,
      decoration: InputDecoration(hintText: widget.hint, helperText: widget.helper),
    );
    return AlertDialog(
      title: Text(widget.title),
      content: widget.body == null
          ? field
          : Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [Text(widget.body!), const SizedBox(height: 12), field],
            ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('取消')),
        FilledButton(key: widget.confirmKey, onPressed: () => Navigator.of(context).pop(_c.text.trim()), child: Text(widget.confirm)),
      ],
    );
  }
}

/// 花余额：几分钟 + 一句理由。两个输入框的控制器归它自己管。
class _SpendDialog extends StatefulWidget {
  const _SpendDialog();

  @override
  State<_SpendDialog> createState() => _SpendDialogState();
}

class _SpendDialogState extends State<_SpendDialog> {
  final _min = TextEditingController(text: '20');
  final _why = TextEditingController();

  @override
  void dispose() {
    _min.dispose();
    _why.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('花挣来的余额'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const ValueKey('spend-minutes'),
            controller: _min,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            decoration: const InputDecoration(labelText: '几分钟（5–60）'),
          ),
          TextField(
            key: const ValueKey('spend-reason'),
            controller: _why,
            autofocus: true,
            maxLength: 120,
            decoration: const InputDecoration(labelText: '理由（花在什么上、为什么现在）'),
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('算了')),
        FilledButton(
          key: const ValueKey('spend-submit'),
          onPressed: () => Navigator.of(context).pop((int.tryParse(_min.text.trim()) ?? 0, _why.text.trim())),
          child: const Text('花'),
        ),
      ],
    );
  }
}

