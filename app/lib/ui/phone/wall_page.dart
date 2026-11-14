import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';
import '../../proactive/proactive_state.dart';
import '../common/list_page_row.dart';
import '../companion/companion_hub_page.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';

/// A3：墙的对话界面。
///
/// **它不是一个聊天窗。** 墙不是第三个人格（`wall_auditor.js` 头注释已定），所以这一页
/// 长得像对话但只能做两件事：查已经落账的审查记录、针对一条判定申诉。
/// 用户不能在这里跟墙说话，墙也不接话——每一句话都是插件用模板生成的判定和依据。
///
/// 三种气泡：提交 / 判定 / 规则变更。没有第四种。
class WallPage extends StatefulWidget {
  const WallPage({super.key, this.channel, this.asTab = false});

  /// 测试用：塞一个假的 channel，免得每个用例都要起引擎。
  final ProactiveChannel? channel;

  /// 作为首页的标签页站着：没有可返回的上一页，所以不画返回键。
  final bool asTab;

  @override
  State<WallPage> createState() => _WallPageState();
}

class _WallPageState extends State<WallPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  WallThread? _thread;
  Map<String, Object?>? _status;
  bool _loading = true;
  bool _failed = false;
  String _failedWhy = '';

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final thread = await _channel.wallThread();
    final status = await _channel.wallStatus();
    if (!mounted) return;
    setState(() {
      _thread = thread;
      _status = status;
      // 记录读不到就是读不到：如实显示，不拿一个空列表假装「还没有记录」。
      _failed = thread == null;
      _failedWhy = thread == null ? 'no_answer' : '';
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return AppPage.list(
      title: l.wallTitle,
      back: !widget.asTab,
      subtitle: const Text('只收证据和申诉。墙不聊天，也不谈判。'),
      actions: [
        GlassIconButton(
          icon: AppIcons.plugin,
          tooltip: '大肥鱼伴侣中枢',
          onPressed: () => unawaited(CompanionHubPage.open(context)),
        ),
        GlassIconButton(icon: AppIcons.refresh, tooltip: l.wallRefresh, onPressed: () => unawaited(_refresh())),
      ],
      children: [
        _statusBar(l),
        const SizedBox(height: 12),
        if (_loading)
          const ListPageCard(children: [ListPageRow(icon: AppIcons.loading, title: '正在读墙的记录…')])
        else if (_failed)
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.warning,
                title: l.wallThreadFailed.replaceAll('{reason}', _failedWhy),
                titleMaxLines: 3,
              ),
            ],
          )
        else if (_thread == null || _thread!.isEmpty)
          ListPageCard(
            children: [
              ListPageRow(icon: AppIcons.info, title: l.wallThreadEmpty, titleMaxLines: 3),
            ],
          )
        else
          ListPageCard(
            title: l.wallTitle,
            children: [for (final b in _thread!.bubbles) _bubble(l, b)],
          ),
        const SizedBox(height: 12),
        _actions(l),
      ],
    );
  }

  /// 顶部状态条：此刻主状态、娱乐锁没锁、锁到几点、原因。
  Widget _statusBar(L10n l) {
    final s = _status;
    final colors = DshColors.of(context);
    if (s == null) {
      return ListPageCard(
        children: [
          ListPageRow(icon: AppIcons.warning, title: l.wallStatusUnknown, titleMaxLines: 2),
        ],
      );
    }
    final locked = s['locked'] == true;
    final until = s['openUntil'] is num ? (s['openUntil'] as num).toInt() : 0;
    final why = s['why'] is String ? s['why'] as String : '';
    return ListPageCard(
      children: [
        ListPageRow(
          icon: locked ? AppIcons.lock : AppIcons.unlock,
          iconColor: locked ? colors.warning : colors.success,
          title: locked ? l.wallStatusLocked : l.wallStatusOpen,
          value: locked && until > 0 ? l.wallStatusUntil.replaceAll('{time}', _hhmm(until)) : null,
          subtitle: why.isEmpty ? null : l.wallStatusWhy.replaceAll('{why}', why),
          subtitleMaxLines: 2,
        ),
      ],
    );
  }

  /// 一条气泡。三种排版：提交靠左、判定按过没过给色、规则变更用另一套图标。
  Widget _bubble(L10n l, WallBubble b) {
    final colors = DshColors.of(context);
    if (b.isSubmit) {
      return ListPageRow(
        icon: AppIcons.edit,
        title: '${l.wallBubbleSubmit} · ${b.who}',
        subtitle: b.text,
        subtitleMaxLines: 3,
        value: _hhmm(b.at),
      );
    }
    if (b.isRule) {
      return ListPageRow(
        icon: AppIcons.verified,
        title: '${l.wallBubbleRule} · ${b.who}',
        subtitle: b.text,
        subtitleMaxLines: 4,
        value: _hhmm(b.at),
      );
    }
    // 判定：过给绿，不过给警告色。文案是墙的原话，一个字不改。
    return ListPageRow(
      icon: b.pass == true ? AppIcons.allowed : AppIcons.blocked,
      iconColor: b.pass == true ? colors.success : colors.warning,
      title: '${l.wallBubbleVerdict} · ${b.who}',
      subtitle: b.text,
      subtitleMaxLines: 4,
      value: _hhmm(b.at),
      // 只有判定可以申诉，而且只能针对这一条。
      onTap: b.at > 0 ? () => unawaited(_appeal(b)) : null,
    );
  }

  /// 输入区不是文本框：两个固定动作。「提交证据」走既有的 `submit_to_wall` 工具链，
  /// 这一页不重复实现一遍提交，只负责申诉（还有只读查看）。
  ///
  /// 提交那一行是**说明**不是按钮：`POST /wall` 里没有 submit 动作，提交证据只在聊天里
  /// 走 `submit_to_wall`。放一个点了没反应的假按钮，比少一个按钮更糟。
  Widget _actions(L10n l) {
    final t = _thread;
    final left = t?.appealLeft ?? 0;
    return ListPageCard(
      title: l.wallActionAppeal,
      children: [
        ListPageRow(
          icon: AppIcons.send,
          title: l.wallActionSubmit,
          subtitle: '不在这里：对鱼说「交这个」，走 submit_to_wall。',
          subtitleMaxLines: 2,
          onTap: null,
        ),
        ListPageRow(
          icon: AppIcons.history,
          title: left > 0 ? l.wallAppealLeft.replaceAll('{left}', '$left') : l.wallAppealNone,
          subtitle: '申诉只收新的物理证据；判还是由墙算。',
          subtitleMaxLines: 2,
        ),
        ListPageRow(
          icon: AppIcons.edit,
          title: l.wallAppealTarget,
          subtitle: '在上面点一条判定。',
          onTap: null,
        ),
      ],
    );
  }

  Future<void> _appeal(WallBubble b) async {
    final l = context.l10n;
    final out = await showDialog<(String, String)>(
      context: context,
      builder: (_) => _AppealDialog(bubble: b),
    );
    if (out == null || !mounted) return;
    final r = await _channel.wallAppeal(at: b.at, evidence: out.$2, task: out.$1.isEmpty ? null : out.$1);
    if (!mounted) return;
    setState(() {});
    final said = r == null ? '连不上大肥鱼，稍后再试' : (r.ok ? r.text : _appealError(l, r));
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(said)));
    if (r != null && r.ok) unawaited(_refresh());
  }

  String _appealError(L10n l, WallAppeal r) {
    final code = r.error ?? '';
    if (code == 'appeal:no-quota') return l.wallAppealNone;
    if (code == 'appeal:no-new-evidence') return l.wallAppealNoEvidence;
    if (code == 'appeal:not-verdict') return l.wallAppealNoTarget;
    // 插件带回来的原话优先；没有就用错误码，别编一句好听的。
    return r.text.isNotEmpty ? r.text : '申诉失败：$code';
  }

  static String _hhmm(int at) {
    if (at <= 0) return '';
    final d = DateTime.fromMillisecondsSinceEpoch(at);
    return '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }
}

/// 申诉：一条判定 + 一份新的物理证据。别的都不要。
class _AppealDialog extends StatefulWidget {
  const _AppealDialog({required this.bubble});

  final WallBubble bubble;

  @override
  State<_AppealDialog> createState() => _AppealDialogState();
}

class _AppealDialogState extends State<_AppealDialog> {
  final _task = TextEditingController();
  final _evidence = TextEditingController();

  @override
  void dispose() {
    _task.dispose();
    _evidence.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final theme = Theme.of(context);
    final ready = _evidence.text.trim().isNotEmpty;
    return AlertDialog(
      shape: AppShape.dialog,
      title: Text(l.wallActionAppeal),
      content: SizedBox(
        width: 360,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.65),
                  borderRadius: AppShape.radiusMd,
                  border: Border.all(color: theme.colorScheme.outlineVariant),
                ),
                child: Text(widget.bubble.text, style: theme.textTheme.bodySmall, maxLines: 4),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _task,
                decoration: InputDecoration(labelText: l.wallAppealTarget, hintText: '这件事叫什么（可空）'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _evidence,
                autofocus: true,
                maxLines: 3,
                minLines: 2,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: l.wallAppealEvidence,
                  hintText: l.wallAppealEvidenceHint,
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l.commonCancel)),
        FilledButton(
          onPressed: ready ? () => Navigator.pop(context, (_task.text.trim(), _evidence.text.trim())) : null,
          child: Text(l.wallAppealSend),
        ),
      ],
    );
  }
}
