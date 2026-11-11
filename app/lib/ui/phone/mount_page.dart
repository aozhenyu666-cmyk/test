import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';
import '../../proactive/proactive_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';

/// C1：工作记忆挂载卡。
///
/// 一张卡固定三栏：目标（1 条）、已知条件（≤5）、中间结论（≤5）。
/// 这一页只做一件事：把卡上的东西摆给他看，让他随手补一条。
///
/// **卡里只能是他自己的话。** 所以「加一条」是他在这一页上手写，不是给鱼一个替他总结的入口；
/// 她提议的那一条走 `mount_propose` 带账本行号上来，显示在下面等他点头。
class MountPage extends StatefulWidget {
  const MountPage({super.key, this.channel});

  /// 测试用：塞一个假的 channel，免得每个用例都要起引擎。
  final ProactiveChannel? channel;

  @override
  State<MountPage> createState() => _MountPageState();
}

class _MountPageState extends State<MountPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  MountView? _view;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final v = await _channel.mount();
    if (!mounted) return;
    setState(() {
      _view = v;
      _loading = false;
    });
  }

  /// 所有写操作都走这里：失败就把插件给的错误码翻成人话，不自己判。
  Future<void> _send(
    Future<MountView?> Function() call, {
    required String done,
  }) async {
    final l = context.l10n;
    final r = await call();
    if (!mounted) return;
    if (r == null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l.mountFailed)));
      return;
    }
    setState(() => _view = r);
    final said = r.ok ? done : _error(l, r);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(said)));
    if (r.ok) unawaited(_refresh());
  }

  String _error(L10n l, MountView r) {
    switch (r.error) {
      case 'goal-empty':
        return l.mountGoalEmpty;
      case 'card-open':
        return l.mountCardOpen;
      case 'no-card':
        return l.mountNoCard;
      case 'givens-full':
        return l.mountGivensFull;
      case 'steps-full':
        return l.mountStepsFull;
      case 'step-needs-given':
        return l.mountStepNeedsGiven;
      case 'not-user-text':
        return l.mountNotUserText;
      case 'park-needs-why':
        return l.mountParkNeedsWhy;
      case 'propose-needs-source':
        return l.mountNeedsSource;
      default:
        // 插件带回来的原话优先；没有就用错误码，别编一句好听的。
        return r.message.isNotEmpty ? r.message : '失败：${r.error}';
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final card = _view?.card ?? const MountCard();
    return AppPage.list(
      title: l.mountTitle,
      subtitle: Text(l.mountSubtitle),
      actions: [
        GlassIconButton(icon: AppIcons.refresh, tooltip: l.mountRefresh, onPressed: () => unawaited(_refresh())),
      ],
      children: [
        if (_loading)
          ListPageCard(children: [ListPageRow(icon: AppIcons.loading, title: l.mountLoading)])
        else if (_view == null)
          ListPageCard(children: [ListPageRow(icon: AppIcons.warning, title: l.mountFailed, titleMaxLines: 3)])
        else ...[
          _statusCard(l, card),
          const SizedBox(height: 12),
          if (card.isOpen) ...[
            _goalCard(l, card),
            const SizedBox(height: 12),
            _givenCard(l, card),
            const SizedBox(height: 12),
            _stepCard(l, card),
            const SizedBox(height: 12),
            if (_view!.hasPending) ...[_pendingCard(l, _view!), const SizedBox(height: 12)],
            _actions(l, card),
          ] else if (_view!.hasPending) ...[
            _pendingCard(l, _view!),
            const SizedBox(height: 12),
            _actions(l, card),
          ] else
            _actions(l, card),
          if (card.cards.length > 1) ...[const SizedBox(height: 12), _otherCards(l, card)],
        ],
      ],
    );
  }

  /// 顶部：卡在哪一步。开着的卡才说目标；停着说停在哪；关掉说关掉了。
  Widget _statusCard(L10n l, MountCard card) {
    final colors = DshColors.of(context);
    final (icon, color, title) = switch (card.status) {
      'open' => (AppIcons.edit, colors.info, l.mountStatusOpen),
      'parked' => (AppIcons.lock, colors.warning, l.mountStatusParked),
      'closed' => (AppIcons.verified, colors.success, l.mountStatusClosed),
      _ => (AppIcons.info, colors.info, l.mountStatusNone),
    };
    return ListPageCard(
      children: [
        ListPageRow(
          icon: icon,
          iconColor: color,
          title: title,
          subtitle: card.status == 'parked' && card.parkedWhy.isNotEmpty
              ? l.mountParkedWhy.replaceAll('{why}', card.parkedWhy)
              : (card.status == 'none' ? l.mountEmptyHint : null),
          subtitleMaxLines: 3,
          value: card.hasCard ? '${card.givens.length}/${card.maxGivens} · ${card.steps.length}/${card.maxSteps}' : null,
        ),
      ],
    );
  }

  /// 目标：1 条，开卡时写下的，之后不改（改目标就是另一件事，重新开卡）。
  Widget _goalCard(L10n l, MountCard card) {
    return ListPageCard(
      title: l.mountGoal,
      children: [
        ListPageRow(icon: AppIcons.info, title: card.goal, titleMaxLines: 4),
      ],
    );
  }

  /// 已知条件：≤5 条。满了他自己删，这一页不给「让她补一条」的按钮。
  Widget _givenCard(L10n l, MountCard card) {
    return ListPageCard(
      title: '${l.mountGivens}（${card.givens.length}/${card.maxGivens}）',
      children: [
        if (card.givens.isEmpty)
          ListPageRow(icon: AppIcons.info, title: l.mountGivensEmpty, titleMaxLines: 2)
        else
          for (final g in card.givens)
            ListPageRow(
              icon: AppIcons.edit,
              title: g.text,
              titleMaxLines: 3,
              subtitle: g.src.isEmpty ? l.mountHandWritten : l.mountFromSource.replaceAll('{src}', g.src),
              subtitleMaxLines: 1,
            ),
        ListPageRow(
          icon: AppIcons.edit,
          title: l.mountAddGiven,
          onTap: card.canAddGiven ? () => unawaited(_addGiven()) : null,
          subtitle: card.canAddGiven ? null : l.mountGivensFull,
          subtitleMaxLines: 2,
        ),
      ],
    );
  }

  /// 中间结论：≤5 条，每一条都要标出由哪几条已知推出。
  Widget _stepCard(L10n l, MountCard card) {
    return ListPageCard(
      title: '${l.mountSteps}（${card.steps.length}/${card.maxSteps}）',
      children: [
        if (card.steps.isEmpty)
          ListPageRow(icon: AppIcons.info, title: l.mountStepsEmpty, titleMaxLines: 2)
        else
          for (final s in card.steps)
            ListPageRow(
              icon: AppIcons.verified,
              title: s.text,
              titleMaxLines: 3,
              subtitle: s.from.isEmpty ? null : l.mountFromGivens.replaceAll('{from}', s.from.map((i) => i + 1).join('、')),
              subtitleMaxLines: 2,
            ),
        ListPageRow(
          icon: AppIcons.edit,
          title: l.mountAddStep,
          // 卡上一条已知都没有时结论无从推起：这一行不给点，并说清为什么。
          onTap: card.canAddStep ? () => unawaited(_addStep(card)) : null,
          subtitle: card.isOpen && card.givens.isEmpty ? l.mountStepNeedsGiven : null,
          subtitleMaxLines: 2,
        ),
      ],
    );
  }

  /// 她提议的那一条：只有「点头」和「不要」两个选择。点头才落卡。
  Widget _pendingCard(L10n l, MountView view) {
    final colors = DshColors.of(context);
    return ListPageCard(
      title: l.mountPending,
      children: [
        ListPageRow(
          icon: AppIcons.info,
          iconColor: colors.info,
          title: view.pendingText ?? '',
          titleMaxLines: 3,
          subtitle: view.pendingSlot == 'step' ? l.mountPendingStep : l.mountPendingGiven,
          subtitleMaxLines: 2,
        ),
        ListPageRow(
          icon: AppIcons.allowed,
          title: l.mountAccept,
          onTap: () => unawaited(_send(
            () => _channel.mount(action: 'accept', from: view.pendingSlot == 'step' ? [0] : null),
            done: l.mountAccepted,
          )),
        ),
        ListPageRow(
          icon: AppIcons.blocked,
          title: l.mountDrop,
          onTap: () => unawaited(_send(() => _channel.mount(action: 'drop-proposal'), done: l.mountDropped)),
        ),
      ],
    );
  }

  Widget _actions(L10n l, MountCard card) {
    return ListPageCard(
      title: l.mountActions,
      children: [
        // 没有开着的卡就能开一张：没开过、停着、关掉了都成立。关掉之后不给开就是死路。
        if (!card.isOpen)
          ListPageRow(
            icon: AppIcons.edit,
            title: l.mountOpen,
            subtitle: l.mountOpenHint,
            subtitleMaxLines: 2,
            onTap: () => unawaited(_open()),
          ),
        if (card.isOpen)
          ListPageRow(
            icon: AppIcons.lock,
            title: l.mountPark,
            subtitle: l.mountParkHint,
            subtitleMaxLines: 2,
            onTap: () => unawaited(_park()),
          ),
        // 面板显示的就是最近一张卡，而它可能正停着：这时候「接着推」是唯一该做的事。
        if (card.status == 'parked')
          ListPageRow(
            icon: AppIcons.history,
            title: l.mountResume,
            subtitle: l.mountResumeHint,
            subtitleMaxLines: 2,
            onTap: () => unawaited(_resume(l, card.id)),
          ),
        if (card.isOpen || card.status == 'parked')
          ListPageRow(
            icon: AppIcons.verified,
            title: l.mountClose,
            subtitle: l.mountCloseHint,
            subtitleMaxLines: 2,
            onTap: () => unawaited(_send(() => _channel.mount(action: 'close'), done: l.mountClosed)),
          ),
      ],
    );
  }

  /// 停着的旧卡：点一下接着推。开着的卡只能有一张，所以有开着的就不给恢复。
  Widget _otherCards(L10n l, MountCard card) {
    return ListPageCard(
      title: l.mountOtherCards,
      children: [
        for (final c in card.cards)
          if (c['status'] != 'open')
            ListPageRow(
              icon: c['status'] == 'parked' ? AppIcons.history : AppIcons.verified,
              title: c['goal'] ?? '',
              titleMaxLines: 2,
              subtitle: (c['parkedWhy'] ?? '').isEmpty ? null : c['parkedWhy'],
              subtitleMaxLines: 2,
              // 有开着的卡时不给恢复：插件那边也会拒（card-open），这里先不让人白点。
              // 判据是「有没有开着的卡」，不是「有没有卡」——唯一一张停着的卡恰恰要能接着推。
              onTap: card.isOpen ? null : () => unawaited(_resume(l, c['id'] ?? '')),
            ),
      ],
    );
  }

  Future<void> _open() async {
    final l = context.l10n;
    final goal = await _ask(l.mountOpen, l.mountGoalPrompt);
    if (goal == null || !mounted) return;
    await _send(() => _channel.mount(action: 'open', goal: goal), done: l.mountOpened);
  }

  Future<void> _addGiven() async {
    final l = context.l10n;
    final text = await _ask(l.mountAddGiven, l.mountGivenPrompt);
    if (text == null || !mounted) return;
    await _send(() => _channel.mount(action: 'given', text: text), done: l.mountAdded);
  }

  Future<void> _addStep(MountCard card) async {
    final l = context.l10n;
    final out = await showDialog<(String, List<int>)>(
      context: context,
      builder: (_) => _StepDialog(givens: card.givens),
    );
    if (out == null || !mounted) return;
    await _send(() => _channel.mount(action: 'step', text: out.$1, from: out.$2), done: l.mountAdded);
  }

  Future<void> _park() async {
    final l = context.l10n;
    final where = await _ask(l.mountPark, l.mountParkPrompt);
    if (where == null || !mounted) return;
    await _send(() => _channel.mount(action: 'park', where: where), done: l.mountParked);
  }

  Future<void> _resume(L10n l, String id) async {
    if (id.isEmpty) return;
    await _send(() => _channel.mount(action: 'resume', id: id), done: l.mountResumed);
  }

  Future<String?> _ask(String title, String hint) async {
    final l = context.l10n;
    final ctrl = TextEditingController();
    final out = await showDialog<String>(
      context: context,
      builder: (ctx) {
        final ready = ValueNotifier<bool>(false);
        ctrl.addListener(() => ready.value = ctrl.text.trim().length >= 2);
        return AlertDialog(
          title: Text(title),
          content: SizedBox(
            width: 360,
            child: TextField(
              controller: ctrl,
              autofocus: true,
              maxLines: 3,
              minLines: 1,
              maxLength: 120,
              decoration: InputDecoration(hintText: hint),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: Text(l.commonCancel)),
            ValueListenableBuilder<bool>(
              valueListenable: ready,
              builder: (_, ok, __) => FilledButton(
                onPressed: ok ? () => Navigator.pop(ctx, ctrl.text.trim()) : null,
                child: Text(l.commonSave),
              ),
            ),
          ],
        );
      },
    );
    ctrl.dispose();
    return out;
  }
}

/// C1：浮层里那张常驻小卡。
///
/// 纯展示：给一个 [card]，它把三栏压成两行，不自己取数、不自己判。
/// 浮层那边（`pet_stage.dart`）接上去要把卡和点击带进来；这一版只把视图做出来并测过，
/// 接进原生 overlay 的布局不在这里做（见 `第七轮进度.md` C1 的「没做到的」）。
class MountMiniCard extends StatelessWidget {
  const MountMiniCard({super.key, required this.card, this.ask = false, this.onTap});

  final MountCard card;

  /// 断链检测亮过：卡上标一个「对不上了」，点开就是那张卡。
  final bool ask;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final colors = DshColors.of(context);
    if (!card.hasCard) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: scheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: ask ? colors.warning : scheme.outlineVariant),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(ask ? AppIcons.warning : AppIcons.edit, size: 14, color: ask ? colors.warning : colors.info),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      card.goal.isEmpty ? l.mountStatusParked : card.goal,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.labelLarge,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 2),
              Text(
                l.mountMiniCounts
                    .replaceAll('{givens}', '${card.givens.length}')
                    .replaceAll('{maxGivens}', '${card.maxGivens}')
                    .replaceAll('{steps}', '${card.steps.length}')
                    .replaceAll('{maxSteps}', '${card.maxSteps}'),
                style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
              ),
              if (ask) ...[
                const SizedBox(height: 2),
                Text(l.mountMiniAsk, style: theme.textTheme.bodySmall?.copyWith(color: colors.warning)),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// 加一条中间结论：文字 + 由哪几条已知推出（至少勾一条，否则不给存）。
class _StepDialog extends StatefulWidget {
  const _StepDialog({required this.givens});

  final List<MountEntry> givens;

  @override
  State<_StepDialog> createState() => _StepDialogState();
}

class _StepDialogState extends State<_StepDialog> {
  final _text = TextEditingController();
  final Set<int> _from = {};

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final ready = _text.text.trim().length >= 2 && _from.isNotEmpty;
    return AlertDialog(
      title: Text(l.mountAddStep),
      content: SizedBox(
        width: 360,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: _text,
                autofocus: true,
                maxLines: 3,
                minLines: 2,
                maxLength: 120,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(hintText: l.mountStepPrompt),
              ),
              const SizedBox(height: 8),
              Text(l.mountStepPick, style: Theme.of(context).textTheme.bodySmall),
              for (var i = 0; i < widget.givens.length; i += 1)
                CheckboxListTile(
                  dense: true,
                  value: _from.contains(i),
                  title: Text('${i + 1}. ${widget.givens[i].text}', maxLines: 2),
                  onChanged: (on) => setState(() {
                    if (on == true) {
                      _from.add(i);
                    } else {
                      _from.remove(i);
                    }
                  }),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l.commonCancel)),
        FilledButton(
          onPressed: ready
              ? () => Navigator.pop(context, (_text.text.trim(), (_from.toList()..sort())))
              : null,
          child: Text(l.commonSave),
        ),
      ],
    );
  }
}
