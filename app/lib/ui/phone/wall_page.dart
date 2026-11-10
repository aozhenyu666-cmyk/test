import 'dart:async';

import 'package:flutter/material.dart';

import '../../proactive/proactive_state.dart';
import '../common/list_page_row.dart';
import '../common/segmented_pill.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';

/// 第五轮「面对墙」：墙是他的上级 / 经理，只在他先开口时说话。
///
/// 页面打开时是空的：顶部是门和账本给的数据，下面选一场会谈（晨间计划 / 晚间回顾 / 总谈），
/// 然后**他先说**。每场会谈的第一句记一次"主动开口"（插件 self-instruction），这是整套系统的核心指标。
/// 墙能辩论、定计划、提高标准，但不能用嘴开门：娱乐锁和过关只认证据。
class WallPage extends StatefulWidget {
  const WallPage({super.key});

  @override
  State<WallPage> createState() => _WallPageState();
}

class _Line {
  const _Line(this.mine, this.text);
  final bool mine;
  final String text;
}

const _meetings = <String, String>{'morning': '晨间计划', 'evening': '晚间回顾', 'general': '总谈'};

const _hints = <String, String>{
  'morning': '你先说：今天你给自己下的指令是什么？',
  'evening': '你先说：今天哪件事和早上想的不一样？',
  'general': '你先说：这次要谈哪件大事？',
};

class _WallPageState extends State<WallPage> {
  final _channel = ProactiveChannel();
  final _input = TextEditingController();
  Map<String, Object?>? _brief;
  bool _loaded = false;
  late String _meeting;

  /// 这场会谈他开过头没有：开过之后的话不再算"主动开口"。
  bool _opened = false;
  bool _sending = false;
  final List<_Line> _lines = [];

  @override
  void initState() {
    super.initState();
    final hour = DateTime.now().hour;
    _meeting = hour < 12 ? 'morning' : (hour >= 19 ? 'evening' : 'general');
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final brief = await _channel.wallBrief();
    if (!mounted) return;
    setState(() {
      _brief = brief;
      _loaded = true;
    });
  }

  void _pickMeeting(String meeting) {
    if (meeting == _meeting) return;
    setState(() {
      _meeting = meeting;
      _opened = false;
    });
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty || _sending) return;
    final opener = !_opened;
    setState(() {
      _lines.add(_Line(true, text));
      _sending = true;
      _opened = true;
      _input.clear();
    });
    final (reply, error) = await _channel.wallSay(text, meeting: _meeting, opener: opener);
    if (!mounted) return;
    setState(() {
      _sending = false;
      _lines.add(_Line(false, reply != null && reply.isNotEmpty ? _stripLabel(reply) : _errorText(error)));
    });
    unawaited(_refresh());
  }

  /// 插件给墙的话标了〔墙〕：页面上已经知道是谁在说，去掉这层。
  static String _stripLabel(String text) => text.startsWith('〔墙〕') ? text.substring(3).trimLeft() : text;

  static String _errorText(String? error) => switch (error) {
    'no-wall-session' => '还没选墙的会话：去「主动搭话」页选一个单独的会话给墙。',
    'timeout' => '墙这次想得太久了，再发一次。',
    'agent-busy' || 'desk-busy' => '墙还在处理上一句，等一下。',
    _ => '没送到（${error ?? '未知'}）。',
  };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dsh = DshColors.of(context);
    final brief = _brief;
    final hasSession = brief?['wallSession'] == true;
    return AppPage.list(
      title: '面对墙',
      subtitle: const Text('墙不会先开口。它能和你辩、帮你定计划，但开门只认证据。'),
      children: [
        _BriefCard(brief: brief, loaded: _loaded),
        const SizedBox(height: 24),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          child: SegmentedPill<String>(
            height: 40,
            segments: [for (final e in _meetings.entries) PillSegment(e.key, e.value)],
            selected: _meeting,
            onChanged: _sending ? (_) {} : _pickMeeting,
          ),
        ),
        if (_loaded && !hasSession)
          const ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.warning,
                title: '还没选墙的会话',
                subtitle: '在「主动搭话」页给墙选一个单独的会话（不能和鱼丸、鲸澜共用）',
                subtitleMaxLines: 2,
              ),
            ],
          ),
        for (final line in _lines) _Bubble(line: line),
        if (_sending)
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
            child: Text('墙在看数据……', style: theme.textTheme.bodySmall?.copyWith(color: dsh.textTertiary)),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 8, 0),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: TextField(
                  key: const ValueKey('wall-input'),
                  controller: _input,
                  minLines: 1,
                  maxLines: 6,
                  textInputAction: TextInputAction.newline,
                  decoration: InputDecoration(hintText: _opened ? '接着说' : _hints[_meeting]),
                ),
              ),
              IconButton(
                key: const ValueKey('wall-send'),
                icon: const Icon(AppIcons.send),
                tooltip: '发给墙',
                onPressed: _sending ? null : () => unawaited(_send()),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// 顶部数据条：门和账本给的，不是他说的。
class _BriefCard extends StatelessWidget {
  const _BriefCard({required this.brief, required this.loaded});

  final Map<String, Object?>? brief;
  final bool loaded;

  static int _n(Object? v) => v is num ? v.toInt() : 0;
  static Map<Object?, Object?> _m(Object? v) => v is Map ? v : const {};

  @override
  Widget build(BuildContext context) {
    final b = brief;
    if (b == null) {
      return ListPageCard(
        title: '数据',
        children: [
          ListPageRow(
            icon: AppIcons.info,
            title: loaded ? '读不到数据' : '读取中',
            subtitle: loaded ? '大肥鱼没回应，稍后再进来' : null,
          ),
        ],
      );
    }
    final y = b['yesterday'] is Map ? _m(b['yesterday']) : null;
    final rate = _m(b['rate']);
    final pred = _m(b['prediction']);
    final today = _m(b['today']);
    final tasks = today['tasks'] is List ? today['tasks'] as List : const [];
    final cases = b['cases'] is List ? b['cases'] as List : const [];
    final rateTotal = _n(rate['total']);
    final rateDone = _n(rate['done']);
    final predN = _n(pred['n']);
    return ListPageCard(
      title: '数据',
      children: [
        ListPageRow(
          icon: AppIcons.today,
          title: '昨天',
          value: y == null ? '没立项' : '${_n(y['done'])}/${_n(y['total'])} 有证据',
        ),
        ListPageRow(
          icon: AppIcons.chart,
          title: '近 14 天',
          value: rateTotal == 0 ? '—' : '$rateDone/$rateTotal（${(rateDone * 100 / rateTotal).round()}%）',
        ),
        ListPageRow(
          icon: AppIcons.plan,
          title: '预测准度',
          subtitle: predN == 0
              ? '还没有对过账的预测'
              : '你平均差 ${pred['his']} 件，墙差 ${pred['wall']} 件（$predN 次）',
          subtitleMaxLines: 2,
        ),
        ListPageRow(icon: AppIcons.chat, title: '本周主动开口', value: '${_n(_m(b['selfInstructions'])['week'])} 次'),
        ListPageRow(
          icon: AppIcons.todo,
          title: today['morningDone'] == true ? '今天（已立项）' : '今天（还没立项）',
          subtitle: tasks.isEmpty
              ? '晨间计划时和墙定 1–3 件事'
              : [
                  for (final (i, t) in tasks.indexed)
                    if (t is Map) '${i + 1}. ${t['task']}（${t['evidence']}）',
                ].join('\n'),
          subtitleMaxLines: 4,
        ),
        if (cases.isNotEmpty)
          ListPageRow(
            icon: AppIcons.bookmark,
            title: '案卷',
            subtitle: [
              for (final c in cases)
                if (c is Map) '${c['name']}${c['due'] == true ? '（该查了）' : ''}',
            ].join('、'),
            subtitleMaxLines: 3,
          ),
        if (b['generalDue'] == true)
          const ListPageRow(icon: AppIcons.time, title: '总谈', subtitle: '超过一周没开了'),
      ],
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.line});

  final _Line line;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dsh = DshColors.of(context);
    final mine = line.mine;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      child: Column(
        crossAxisAlignment: mine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
        children: [
          if (!mine)
            Padding(
              padding: const EdgeInsets.only(left: 4, bottom: 2),
              child: Text('墙', style: theme.textTheme.labelSmall?.copyWith(color: dsh.textTertiary)),
            ),
          Container(
            constraints: const BoxConstraints(maxWidth: 560),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              color: mine ? theme.colorScheme.primaryContainer : theme.colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(14),
            ),
            child: SelectableText(
              line.text,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: mine ? theme.colorScheme.onPrimaryContainer : theme.colorScheme.onSurface,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
