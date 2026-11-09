import 'dart:async';

import 'package:flutter/material.dart';

import '../../proactive/proactive_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'deposit_page.dart';

/// 设置 → 手机操控 → 姐妹（R4-5）。
///
/// 两张角色卡左右滑：妹妹和姐姐。每张卡可以展开看人设、她们记得的你、最近说过的话。
/// 墙不做成角色卡，只在最下面占一块灰色的「今日审计」面板：今天立了几件、有证据完成几件、
/// 近 14 天完成率。
///
/// 这一页刻意没有任何能放松规则的入口。放松只能走晚上对账后的申请窗口——在这里放个按钮，
/// 就等于把墙开在设置里。
class SistersPage extends StatefulWidget {
  const SistersPage({super.key, this.channel});

  final ProactiveChannel? channel;

  @override
  State<SistersPage> createState() => _SistersPageState();
}

class _SistersPageState extends State<SistersPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  final _pages = PageController();
  SistersView? _view;
  DepositView? _deposit;
  int _index = 0;
  bool _expanded = false;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final view = await _channel.sisters();
    final deposit = await _channel.deposit();
    if (!mounted) return;
    setState(() {
      _view = view;
      _deposit = deposit;
      // 刷新后当前页可能已经不存在（比如双人格被关掉），回到第一张。
      if (_index >= (view?.cards.length ?? 0)) _index = 0;
    });
  }

  @override
  Widget build(BuildContext context) {
    final view = _view;
    return AppPage.list(
      title: '姐妹',
      subtitle: const Text('她们记得的你、最近说过的话，还有墙今天的审计和该交的四段'),
      actions: [
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        if (view == null)
          const ListPageCard(
            children: [ListPageRow(icon: AppIcons.info, title: '连不上大肥鱼，稍后再试', titleMaxLines: 2)],
          )
        else if (!view.ok)
          ListPageCard(
            children: [ListPageRow(icon: AppIcons.info, title: _errorText(view.error), titleMaxLines: 2)],
          )
        else if (view.cards.isEmpty)
          const ListPageCard(
            children: [
              ListPageRow(icon: AppIcons.info, title: '还没有角色卡', subtitle: '双人格可能关着', titleMaxLines: 2),
            ],
          )
        else ...[
          _cardPager(view),
          const SizedBox(height: 12),
          AnimatedSwitcher(
            duration: AppMotion.quick,
            child: KeyedSubtree(
              key: ValueKey('body-${view.cards[_indexSafe(view)].role}-$_expanded'),
              child: _cardBody(view),
            ),
          ),
          const SizedBox(height: 24),
          _auditPanel(view.audit),
          const SizedBox(height: 12),
          _depositCard(),
        ],
      ],
    );
  }

  int _indexSafe(SistersView view) => _index < view.cards.length ? _index : 0;

  /// 三个对象里的第三个：墙。它不聊天、不谈判，所以这里只有一条通往「今天该交什么」的路。
  ///
  /// 四段没交齐 = 当天没有放风额度，这个判断在插件里做，这里只把「还缺几段」说清楚。
  Widget _depositCard() {
    final d = _deposit;
    final colors = DshColors.of(context);
    final complete = d?.complete == true;
    return ListPageCard(
      children: [
        ListPageRow(
          icon: complete ? AppIcons.allowed : AppIcons.today,
          iconColor: d == null ? null : (complete ? colors.success : colors.warning),
          title: '每日四部门',
          subtitle: d == null
              ? '连不上大肥鱼，读不到今天交了几段'
              : complete
                  ? '今天四段都交了：信息收集、谋划思考、行为管理、统筹规划'
                  : '今天交了 ${d.done} / ${d.total} 段，交齐之前不放额度${d.missingText.isEmpty ? '' : '：${d.missingText}'}',
          subtitleMaxLines: 3,
          value: d == null ? null : '${d.done}/${d.total}',
          chevron: d != null,
          onTap: d == null ? null : () => _pushDeposit(),
        ),
      ],
    );
  }

  Future<void> _pushDeposit() async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const DepositPage()));
    // 回来把四段的状态和墙的审计一起刷新，免得面板还停在旧数字。
    if (mounted) await _refresh();
  }

  /// 两张卡左右滑。
  Widget _cardPager(SistersView view) {
    return Column(
      children: [
        SizedBox(
          height: _expanded ? 92 : 150,
          // PageView 自己铺满全宽，14 + 每页 2 = 16，和 ListPageCard 的默认边距对齐。
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14),
            child: PageView.builder(
              controller: _pages,
              itemCount: view.cards.length,
              onPageChanged: (i) => setState(() {
                _index = i;
                _expanded = false;
              }),
              itemBuilder: (context, i) => Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2),
                child: _card(view.cards[i], active: i == _index),
              ),
            ),
          ),
        ),
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (var i = 0; i < view.cards.length; i++)
              AnimatedContainer(
                duration: AppMotion.quick,
                margin: const EdgeInsets.symmetric(horizontal: 3),
                width: i == _index ? 18 : 6,
                height: 6,
                decoration: BoxDecoration(
                  color: i == _index
                      ? Theme.of(context).textTheme.bodyMedium?.color
                      : DshColors.of(context).textDisabled,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
          ],
        ),
      ],
    );
  }

  Widget _card(SistersCard card, {required bool active}) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final glass = DshSurfaces.of(context).card;
    final tint = card.role == 'strategist' ? colors.info : colors.warning;
    return GestureDetector(
      onTap: () => setState(() => _expanded = !_expanded),
      child: AnimatedContainer(
        duration: AppMotion.quick,
        padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
        decoration: BoxDecoration(
          color: glass.fallbackFill,
          borderRadius: AppShape.radiusLg,
          border: Border.all(
            color: active ? tint.withValues(alpha: 0.45) : glass.hairline,
            width: active ? 1 : glass.hairlineWidth,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 40,
                  height: 40,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: tint.withValues(alpha: 0.16),
                    borderRadius: AppShape.radiusXl,
                  ),
                  child: Text(
                    card.name.characters.take(1).toString(),
                    style: theme.textTheme.titleMedium?.copyWith(color: tint),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    card.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.titleMedium,
                  ),
                ),
                Text(_expanded ? '收起' : '展开', style: theme.textTheme.labelSmall),
              ],
            ),
            if (!_expanded) ...[
              const Spacer(),
              Text(card.line, maxLines: 2, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall),
            ],
          ],
        ),
      ),
    );
  }

  /// 展开后的正文：人设 / 她们记得的你 / 最近说过的话。
  Widget _cardBody(SistersView view) {
    if (!_expanded) {
      return ListPageCard(
        children: [
          ListPageRow(
            icon: AppIcons.info,
            title: '展开看人设、她们记得的你、最近说过的话',
            titleMaxLines: 2,
            onTap: () => setState(() => _expanded = true),
            chevron: true,
          ),
        ],
      );
    }
    final card = view.cards[_indexSafe(view)];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (card.persona.isNotEmpty) ...[
          ListPageCard(
            title: '人设',
            children: [ListPageRow(icon: AppIcons.info, title: card.persona, titleMaxLines: 6)],
          ),
          const SizedBox(height: 12),
        ],
        ListPageCard(
          title: '她们记得的你',
          children: view.memory.isEmpty
              ? const [ListPageRow(icon: AppIcons.info, title: '还没记住关于你的事', subtitle: '多聊几句')]
              : [
                  for (final m in view.memory)
                    ListPageRow(
                      icon: AppIcons.info,
                      title: m.title,
                      titleMaxLines: 2,
                      subtitle: m.subtitle.isEmpty ? null : m.subtitle,
                    ),
                ],
        ),
        const SizedBox(height: 12),
        ListPageCard(
          title: '最近说过的话',
          children: view.timeline.isEmpty
              ? const [ListPageRow(icon: AppIcons.info, title: '还没有记录')]
              : [
                  for (final t in view.timeline)
                    ListPageRow(
                      icon: AppIcons.info,
                      title: t.title,
                      titleMaxLines: 3,
                      subtitle: t.subtitle.isEmpty ? null : t.subtitle,
                      subtitleMaxLines: 2,
                      iconColor: _toneColor(t.tone),
                    ),
                ],
        ),
      ],
    );
  }

  /// 墙：一块灰色的面板。不是角色卡，不带表情，也没有任何能放松的按钮。
  Widget _auditPanel(SistersAudit audit) {
    final theme = Theme.of(context);
    final glass = DshSurfaces.of(context).card;
    final muted = theme.textTheme.bodySmall?.copyWith(color: DshColors.of(context).textTertiary);
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 16),
      decoration: BoxDecoration(
        color: glass.fallbackFill,
        borderRadius: AppShape.radiusLg,
        border: Border.all(color: glass.hairline, width: glass.hairlineWidth),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('〔墙〕今日审计', style: muted),
          const SizedBox(height: 10),
          if (!audit.hasToday && !audit.hasRate)
            Text('今天还没有审计记录', style: theme.textTheme.bodySmall)
          else ...[
            if (audit.hasToday)
              Text('今天立了 ${audit.promised} 件 · 有证据完成 ${audit.done} 件', style: theme.textTheme.bodyMedium),
            if (audit.hasRate) ...[
              const SizedBox(height: 6),
              Text(
                '近 ${audit.rateDays} 天：完成 ${audit.rateDone} / ${audit.rateTotal}（${audit.ratePct}%）',
                style: theme.textTheme.bodySmall,
              ),
            ],
          ],
          if (audit.text.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(audit.text, style: muted?.copyWith(height: 1.5)),
          ],
        ],
      ),
    );
  }

  Color? _toneColor(String tone) {
    final colors = DshColors.of(context);
    return switch (tone) {
      'good' => colors.success,
      'warn' => colors.warning,
      'bad' => colors.danger,
      'info' => colors.info,
      _ => null,
    };
  }

  String _errorText(String? code) => switch (code) {
    'no_transport' => '连不上大肥鱼：引擎还没连上',
    'no_answer' => '大肥鱼没回应，稍后再试',
    'timeout' => '大肥鱼超时了，稍后再试',
    _ => '读不到姐妹（$code）',
  };
}
