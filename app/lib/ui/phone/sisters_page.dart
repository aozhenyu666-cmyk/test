import 'dart:async';

import 'package:flutter/material.dart';

import '../../proactive/proactive_state.dart';
import '../common/list_page_row.dart';
import '../common/segmented_pill.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'deposit_page.dart';
import 'wall_page.dart';
import 'plan_page.dart';

/// C2：四个基本操作的显示名和图标。键是插件给的 op 码，不在这里造第二套命名。
const _opLabels = <String, (String, IconData)>{
  'recall': ('回想', AppIcons.history),
  'brief': ('精简', AppIcons.edit),
  'predict': ('预判', AppIcons.info),
  'ask': ('提问', AppIcons.chat),
};

/// 设置 → 手机操控 → 姐妹（R4-5）。
///
/// 姐妹两张角色卡，用分段胶囊切换：选中谁就显示谁的一句话定位和人设。
/// 记忆和最近说过的话是共享的，各自一块卡。
///
/// 墙不做成角色卡，只在下面占一块「今日审计」：今天立了几件、有证据完成几件、
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
  SistersView? _view;
  DepositView? _deposit;
  int _index = 0;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    final view = await _channel.sisters();
    final deposit = await _channel.deposit();
    if (!mounted) return;
    setState(() {
      _view = view;
      _deposit = deposit;
      // 刷新后当前这张可能已经不存在（比如双人格被关掉），回到第一张。
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
        // 第九轮 B1：今天还没有计划——先把他带到计划界面。三个角色谁在场都是这一张。
        if (view?.planGate ?? false) ...[
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.calendar,
                title: '今天还没有计划',
                subtitle: '先把今天几局、每局用哪些 App 定下来；不定就一直挂着这张',
                subtitleMaxLines: 2,
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const PlanPage()),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
        ],
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
          if (view.cards.length > 1) ...[_selector(view), const SizedBox(height: 12)],
          ..._sisterSection(view.cards[_indexSafe(view)]),
          const SizedBox(height: 12),
          _memoryCard(view),
          const SizedBox(height: 12),
          _timelineCard(view),
          const SizedBox(height: 24),
          _auditCard(view.audit),
          const SizedBox(height: 12),
          _opsCard(view.drillOps),
          const SizedBox(height: 12),
          _depositCard(),
        ],
      ],
    );
  }

  int _indexSafe(SistersView view) => _index < view.cards.length ? _index : 0;

  /// 两张角色卡之间切换。和设置页里别处的「二选一」是同一个控件。
  ///
  /// 只有一张卡时不出现——一个只有一段的胶囊等于没有选择。
  Widget _selector(SistersView view) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: SegmentedPill<String>(
        segments: [for (final card in view.cards) PillSegment(card.role, card.name)],
        selected: view.cards[_indexSafe(view)].role,
        onChanged: (role) => setState(() {
          final i = view.cards.indexWhere((card) => card.role == role);
          if (i >= 0) _index = i;
        }),
      ),
    );
  }

  /// 当前这张：名字 + 一句话定位，人设单独一块。
  List<Widget> _sisterSection(SistersCard card) {
    final tint = card.role == 'strategist' ? DshColors.of(context).info : DshColors.of(context).warning;
    return [
      ListPageCard(
        children: [
          ListPageRow(
            icon: AppIcons.chat,
            iconColor: tint,
            title: card.name,
            titleMaxLines: 2,
            subtitle: card.line.isEmpty ? null : card.line,
            subtitleMaxLines: 3,
          ),
        ],
      ),
      if (card.persona.isNotEmpty) ...[
        const SizedBox(height: 12),
        ListPageCard(
          title: '人设',
          children: [ListPageRow(icon: AppIcons.info, title: card.persona, titleMaxLines: 8)],
        ),
      ],
    ];
  }

  Widget _memoryCard(SistersView view) {
    return ListPageCard(
      title: '她们记得的你',
      children: view.memory.isEmpty
          ? const [ListPageRow(icon: AppIcons.info, title: '还没记住关于你的事', subtitle: '多聊几句')]
          : [
              for (final note in view.memory)
                ListPageRow(
                  icon: AppIcons.info,
                  title: note.title,
                  titleMaxLines: 2,
                  subtitle: note.subtitle.isEmpty ? null : note.subtitle,
                ),
            ],
    );
  }

  Widget _timelineCard(SistersView view) {
    return ListPageCard(
      title: '最近说过的话',
      children: view.timeline.isEmpty
          ? const [ListPageRow(icon: AppIcons.info, title: '还没有记录')]
          : [
              for (final note in view.timeline)
                ListPageRow(
                  icon: AppIcons.info,
                  title: note.title,
                  titleMaxLines: 3,
                  subtitle: note.subtitle.isEmpty ? null : note.subtitle,
                  subtitleMaxLines: 2,
                  iconColor: _toneColor(note.tone),
                ),
            ],
    );
  }

  /// 墙：一块不带表情、也没有任何放松按钮的审计面板。
  Widget _auditCard(SistersAudit audit) {
    final colors = DshColors.of(context);
    return ListPageCard(
      title: '〔墙〕今日审计',
      children: [
        if (!audit.hasToday && !audit.hasRate)
          const ListPageRow(icon: AppIcons.info, title: '今天还没有审计记录')
        else ...[
          if (audit.hasToday)
            ListPageRow(
              icon: AppIcons.today,
              title: '今天立了 ${audit.promised} 件',
              value: '完成 ${audit.done} 件',
            ),
          if (audit.hasRate)
            ListPageRow(
              icon: AppIcons.allowed,
              iconColor: colors.success,
              title: '近 ${audit.rateDays} 天完成率',
              value: '${audit.rateDone}/${audit.rateTotal}（${audit.ratePct}%）',
            ),
        ],
        if (audit.text.isNotEmpty)
          ListPageRow(icon: AppIcons.info, title: audit.text, titleMaxLines: 8),
        // A3：审查记录（提交 / 判定 / 规则变更）和申诉入口。墙不聊天，所以这里只是看一眼。
        ListPageRow(
          icon: AppIcons.history,
          title: '审查记录',
          value: '看判定',
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => const WallPage()),
          ),
        ),
      ],
    );
  }

  /// C2：四个基本操作的近 14 天通过率。
  ///
  /// 只把插件算好的数字摆出来：没练过的操作写「还没练过」，不写成 0%（那会被当成满分）。
  Widget _opsCard(Map<String, DrillOpStat> stats) {
    final rows = <Widget>[];
    for (final e in stats.entries) {
      final label = _opLabels[e.key];
      if (label == null) continue;
      final stat = e.value;
      rows.add(ListPageRow(
        icon: label.$2,
        title: label.$1,
        value: !stat.tried ? '还没练过' : '${stat.passed}/${stat.asked}（${(stat.rate * 100).round()}%）',
      ));
    }
    return ListPageCard(
      title: '〔四个基本操作〕近 14 天',
      children: [
        if (rows.isEmpty)
          const ListPageRow(icon: AppIcons.info, title: '还没有练过')
        else
          ...rows,
      ],
    );
  }

  /// 三个对象里的第三个：墙。这里只有一条通往「今天该交什么」的路。
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
