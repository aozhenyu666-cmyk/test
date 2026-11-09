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
    final view = await _channel.deposit(department: item.key, report: out.$1, thought: out.$2);
    if (!mounted) return;
    setState(() => _view = view);
    final err = view?.error;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(err == null ? '交了「${item.label}」' : _errorText(err))),
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
    'no_transport' => '连不上大肥鱼：引擎还没连上',
    'no_answer' => '大肥鱼没回应，稍后再试',
    'timeout' => '大肥鱼超时了，稍后再试',
    _ => '没交上（$code）',
  };
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
    if (_busy) return;
    setState(() => _busy = true);
    Navigator.pop(context, (_report.text.trim(), _thought.text.trim()));
  }

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    return AlertDialog(
      title: Text(item.label),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _report,
              autofocus: true,
              maxLines: 6,
              minLines: 4,
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                labelText: '汇报内容',
                hintText: item.hint,
                helperText: '${_chars(_report.text)} / ${item.minReport} 字',
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
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(context.l10n.commonCancel)),
        FilledButton(onPressed: _submit, child: Text(context.l10n.commonSave)),
      ],
    );
  }
}
