import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';
import '../../proactive/proactive_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';

/// 设置 → 手机操控 → 姐妹 → 每日四部门（第五轮）。
///
/// 一天四段：信息收集、谋划思考、行为管理、统筹规划。每段两栏——汇报内容 + 自己的想法，
/// 有字数下限（插件给，界面只照着显示）。交不齐的当天没有放风额度，这条在插件里判。
///
/// 真伪不靠这里：字数、复读这一层是机械的；内容够不够，晚上对账时鲸澜读原文再评一次。
class DepositPage extends StatefulWidget {
  const DepositPage({super.key, this.channel});

  final ProactiveChannel? channel;

  @override
  State<DepositPage> createState() => _DepositPageState();
}

class _DepositPageState extends State<DepositPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  DepositView? _view;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    final view = await _channel.deposit();
    if (!mounted) return;
    setState(() => _view = view);
  }

  Future<void> _write(DepositItem item) async {
    final out = await showDialog<(String, String)>(
      context: context,
      builder: (_) => _DepositDialog(item: item),
    );
    if (out == null || !mounted) return;
    // A4：行为管理部的汇报栏由墙按遥测生成，页面不送 report（送了插件也不看，只用想法栏）。
    final view = await _channel.deposit(
      department: item.key,
      report: item.autoReport ? null : out.$1,
      thought: out.$2,
    );
    if (!mounted) return;
    setState(() => _view = view);
    final err = view?.error;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(err == null ? '交了「${item.label}」' : _errorText(err))),
    );
  }

  /// A4：白天记一条（≤200 字）。晚上写那一段时对着这些记录写，不是凭空回忆。
  Future<void> _note(DepositItem item) async {
    final text = await showDialog<String>(
      context: context,
      builder: (_) => _SlotDialog(item: item),
    );
    if (text == null || !mounted) return;
    final view = await _channel.deposit(department: item.key, text: text);
    if (!mounted) return;
    setState(() => _view = view);
    final err = view?.error;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(err == null ? '记进「${item.label}」了' : _errorText(err))),
    );
  }

  @override
  Widget build(BuildContext context) {
    final view = _view;
    return AppPage.list(
      title: '每日四部门',
      subtitle: const Text('一天四段，汇报内容加自己的想法；交不齐当天没有放风额度'),
      actions: [
        GlassIconButton(icon: AppIcons.refresh, tooltip: '刷新', onPressed: () => unawaited(_refresh())),
      ],
      children: [
        if (view == null)
          const ListPageCard(
            children: [ListPageRow(icon: AppIcons.info, title: '连不上大肥鱼，稍后再试', titleMaxLines: 2)],
          )
        else ...[
          _summary(view),
          const SizedBox(height: 12),
          ListPageCard(
            title: '今天四段',
            children: [
              for (final item in view.items)
                ListPageRow(
                  icon: item.done ? AppIcons.allowed : AppIcons.today,
                  iconColor: item.done ? DshColors.of(context).success : null,
                  title: item.label,
                  subtitle: item.done
                      ? '已交 ${item.reportChars} 字汇报 · ${item.thoughtChars} 字想法，点开可以重写'
                      : '${item.hint}（汇报 ≥ ${item.minReport} 字，想法 ≥ ${item.minThought} 字）',
                  subtitleMaxLines: 3,
                  value: item.done ? '已交' : '去写',
                  onTap: () => unawaited(_write(item)),
                  // A4：白天随手记一条；条数直接显示在这一行（晚上写汇报时对着它写）。
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text('今天 ${item.slots} 条', style: Theme.of(context).textTheme.labelMedium),
                      IconButton(
                        tooltip: '记一条（${item.duty}）',
                        icon: const Icon(AppIcons.edit, size: 18),
                        onPressed: () => unawaited(_note(item)),
                      ),
                    ],
                  ),
                ),
            ],
          ),
          if (view.error != null) ...[
            const SizedBox(height: 12),
            ListPageCard(
              children: [
                ListPageRow(icon: AppIcons.warning, title: _errorText(view.error!), titleMaxLines: 3),
              ],
            ),
          ],
        ],
      ],
    );
  }

  Widget _summary(DepositView view) {
    final colors = DshColors.of(context);
    final complete = view.complete;
    return ListPageCard(
      children: [
        ListPageRow(
          icon: complete ? AppIcons.allowed : AppIcons.blocked,
          iconColor: complete ? colors.success : colors.warning,
          title: complete ? '四段都交了' : '今天交了 ${view.done} / ${view.total} 段',
          titleMaxLines: 2,
          subtitle: complete ? '今天有放风额度' : '交齐之前不放额度${view.missingText.isEmpty ? '' : '：${view.missingText}'}',
          subtitleMaxLines: 3,
        ),
      ],
    );
  }

  String _errorText(String code) => switch (code) {
    'too-thin' => '字不够或者像套话，插件退回了。再补几句具体的。',
    'bad-department' => '没有这个部门，刷新一下再试。',
    'bad-slot' => '这条记录没记上：要么是空的，要么超过 200 字了。',
    'unsupported' => '这一段没过：今天这个部没有一条记录能对上它。先去记一条，再写。',
    'no_transport' => '连不上大肥鱼：引擎还没连上',
    'no_answer' => '大肥鱼没回应，稍后再试',
    'timeout' => '大肥鱼超时了，稍后再试',
    _ => '没交上（$code）',
  };
}

/// A4：白天记一条（≤200 字）。晚上写那一段时对着这些记录写，不是凭空回忆。
class _SlotDialog extends StatefulWidget {
  const _SlotDialog({required this.item});

  final DepositItem item;

  @override
  State<_SlotDialog> createState() => _SlotDialogState();
}

class _SlotDialogState extends State<_SlotDialog> {
  final _text = TextEditingController();
  static const int _max = 200;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final n = _text.text.trim().length;
    return AlertDialog(
      title: Text('记一条 · ${item.label}'),
      content: SizedBox(
        width: 360,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              if (item.duty.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text('这个部记：${item.duty}', style: Theme.of(context).textTheme.bodySmall),
                ),
              TextField(
                controller: _text,
                autofocus: true,
                maxLines: 4,
                minLines: 2,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: '刚才这一块做了什么',
                  helperText: '$n / $_max 字（超了插件会退回）',
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(context.l10n.commonCancel)),
        FilledButton(
          onPressed: n == 0 || n > _max ? null : () => Navigator.pop(context, _text.text.trim()),
          child: Text(context.l10n.commonSave),
        ),
      ],
    );
  }
}

/// 写一段：汇报内容 + 自己的想法，两边都实时显示够不够字。
///
/// 字数按插件同一套口径算（去掉空白和标点），免得界面说够了、插件说不够。
/// 但判断权在插件：这里只是提前给个提示。
class _DepositDialog extends StatefulWidget {
  const _DepositDialog({required this.item});

  final DepositItem item;

  @override
  State<_DepositDialog> createState() => _DepositDialogState();
}

class _DepositDialogState extends State<_DepositDialog> {
  final _report = TextEditingController();
  final _thought = TextEditingController();
  bool _busy = false;

  /// 只算非空白字符，够不够由插件判（插件那边还会去标点）。这里只是提前给个大概。
  int _chars(String text) => text.replaceAll(RegExp(r'\s'), '').length;

  @override
  void dispose() {
    _report.dispose();
    _thought.dispose();
    super.dispose();
  }

  void _submit() {
    // 防连点：pop 两次会把这一页也关掉。
    if (_busy) return;
    _busy = true;
    Navigator.pop(context, (_report.text.trim(), _thought.text.trim()));
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    return AlertDialog(
      title: Text(item.label),
      content: SizedBox(
        width: 360,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: _report,
                autofocus: !item.autoReport,
                readOnly: item.autoReport,
                enabled: !item.autoReport,
                maxLines: 6,
                minLines: 4,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: '汇报内容',
                  hintText: item.autoReport ? '这一栏由墙按今天的遥测自动生成，你不能改。' : item.hint,
                  helperText: item.autoReport
                      ? '墙生成，只读：${item.evidence}'
                      : '${_chars(_report.text)} / ${item.minReport} 字',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _thought,
                maxLines: 4,
                minLines: 3,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: '我的想法',
                  hintText: '不只是复述：你想清楚了什么、打算怎么走。',
                  helperText: '${_chars(_thought.text)} / ${item.minThought} 字',
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(context.l10n.commonCancel)),
        FilledButton(onPressed: _submit, child: Text(context.l10n.commonSave)),
      ],
    );
  }
}
