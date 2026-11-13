import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../loop/loop_channel.dart';
import '../../proactive/proactive_state.dart';
import '../../state/phone_state.dart';
import '../common/app_sheet.dart';
import '../common/list_page_row.dart';
import '../common/risk_notice.dart';
import '../common/segmented_pill.dart';
import '../features/feature_kit.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'deposit_page.dart';
import 'phone_widgets.dart';

extension AppRuleText on AppRule {
  String get label => switch (this) {
    AppRule.deny => appL10n.phoneAppRuleDeny,
    AppRule.confirmEachStep => appL10n.phoneAppRuleConfirm,
    AppRule.normal => appL10n.phoneAppRuleNormal,
  };

  String get detail => switch (this) {
    AppRule.deny => appL10n.phoneAppRuleDenyHint,
    AppRule.confirmEachStep => appL10n.phoneAppRuleConfirmHint,
    AppRule.normal => appL10n.phoneAppRuleNormalHint,
  };

  IconData get icon => switch (this) {
    AppRule.deny => AppIcons.blocked,
    AppRule.confirmEachStep => AppIcons.askFirst,
    AppRule.normal => AppIcons.allowed,
  };

  /// Higher is looser.
  int get looseness => index;
}

/// Every package the page lists: both lists, the shipped defaults, and what the
/// person changed this session.
List<String> listedPackages(PhoneState state) {
  final p = state.policies;
  if (p == null) return const [];
  return {
    ...p.denyPackages,
    ...p.confirmEveryWritePackages,
    ...p.defaultDeny,
    ...p.defaultConfirm,
    ...state.retained,
  }.toList();
}

/// 应用名单: banks and payment apps are off limits by default, WeChat asks at
/// every step. Loosening a rule first shows the risk.
class PhoneAppRulesPage extends ConsumerWidget {
  const PhoneAppRulesPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final state = ref.watch(phoneProvider);
    final policies = state.policies;
    final packages = listedPackages(state);
    List<String> by(AppRule rule) => [
      for (final p in packages)
        if (policies!.ruleFor(p) == rule) p,
    ];

    final groups = [
      for (final rule in AppRule.values)
        if (by(rule) case final list when list.isNotEmpty) (rule, list),
    ];
    return AppPage.list(
      title: l10n.phoneAppRulesTitle,
      subtitle: Text(l10n.phoneAppRulesSubtitle),
      children: [
        if (policies == null)
          EmptyView(
            icon: AppIcons.appRules,
            title: l10n.phoneAppRulesUnavailable,
            message: l10n.phoneTryLater,
            padding: const EdgeInsets.fromLTRB(32, 40, 32, 24),
          )
        else ...[
          const _WallCard(),
          const SizedBox(height: 24),
          const _GeminiSessionCard(),
          const SizedBox(height: 24),
          for (final (i, (rule, list)) in groups.indexed) ...[
            if (i > 0) const SizedBox(height: 24),
            ListPageCard(
              title: switch (rule) {
                AppRule.deny => l10n.phoneAppRuleDeny,
                AppRule.confirmEachStep => l10n.phoneAppRuleConfirm,
                AppRule.normal => l10n.phoneAppRulesChanged,
              },
              children: [for (final package in list) _AppRow(package: package, policies: policies)],
            ),
          ],
          const SizedBox(height: 24),
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.add,
                iconColor: Theme.of(context).colorScheme.primary,
                title: l10n.phoneAppRulesAdd,
                subtitle: l10n.phoneAppRulesAddHint,
                onTap: () => _add(context, ref),
              ),
            ],
          ),
        ],
        PageFootnote(l10n.phoneSensitiveActionsNote),
      ],
    );
  }

  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final added = await showDialog<(String, AppRule)>(context: context, builder: (_) => const _AddAppDialog());
    if (added == null) return;
    final ok = await ref.read(phoneProvider.notifier).setRule(added.$1, added.$2);
    if (!ok && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.phoneAppRulesSaveFailed)));
    }
  }
}

class _AppRow extends ConsumerWidget {
  const _AppRow({required this.package, required this.policies});

  final String package;
  final PhonePolicies policies;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final rule = policies.ruleFor(package);
    final label = policies.labelFor(package);
    final fallback = policies.defaultRuleFor(package);
    final changed = fallback != null && fallback != rule;
    return ListPageRow(
      icon: rule.icon,
      iconColor: rule == AppRule.deny ? DshColors.of(context).danger : null,
      title: label,
      subtitle: [
        if (label != package) package,
        if (changed) l10n.phoneAppRuleDefault(fallback.label),
      ].join(' · ').ifEmpty(null),
      onTap: () => _change(context, ref, rule, label),
    );
  }

  Future<void> _change(BuildContext context, WidgetRef ref, AppRule current, String label) async {
    final next = await showAppSheet<AppRule>(
      context,
      builder: (sheet) => SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SheetHeader(label),
            SheetGroup(
              children: [
                for (final rule in AppRule.values)
                  ListPageRow(
                    icon: rule.icon,
                    title: rule.label,
                    subtitle: rule.detail,
                    chevron: false,
                    trailing: rule == current
                        ? Icon(AppIcons.selected, size: 22, color: Theme.of(sheet).colorScheme.primary)
                        : const SizedBox.square(dimension: 22),
                    onTap: () => Navigator.pop(sheet, rule),
                  ),
              ],
            ),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
    if (next == null || next == current || !context.mounted) return;
    if (next.looseness > current.looseness) {
      final ok = await confirmLoosening(context, label: label, from: current);
      if (!ok || !context.mounted) return;
    }
    final saved = await ref.read(phoneProvider.notifier).setRule(package, next);
    if (!saved && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.phoneAppRulesSaveFailed)));
    }
  }
}

/// The risk note shown before a rule gets looser. True to go ahead.
Future<bool> confirmLoosening(BuildContext context, {required String label, required AppRule from}) =>
    confirmRisk(context, RiskNotices.appRule(label, fromDeny: from == AppRule.deny));

class _AddAppDialog extends StatefulWidget {
  const _AddAppDialog();

  @override
  State<_AddAppDialog> createState() => _AddAppDialogState();
}

class _AddAppDialogState extends State<_AddAppDialog> {
  final _package = TextEditingController();
  final _search = TextEditingController();
  AppRule _rule = AppRule.deny;

  /// 已安装的桌面应用。`dsh/loop` 的 `apps` 早就有这个能力（触发测试页在用），
  /// 这里只是把它接到「加一条规则」上——以前要用户自己背包名。
  List<LoopApp>? _apps;
  bool _manual = false;

  static final _pattern = RegExp(r'^[A-Za-z][\w]*(\.[A-Za-z][\w]*)+$');

  @override
  void initState() {
    super.initState();
    unawaited(_loadApps());
  }

  Future<void> _loadApps() async {
    // 列不出来（原生通道缺失、插件没装）就直接进手输，别给一个永远转圈的空列表。
    List<LoopApp> apps;
    try {
      apps = await LoopChannel().apps();
    } on Object {
      apps = const [];
    }
    if (!mounted) return;
    setState(() {
      _apps = apps;
      if (apps.isEmpty) _manual = true;
    });
  }

  @override
  void dispose() {
    _package.dispose();
    _search.dispose();
    super.dispose();
  }

  bool get _valid => _pattern.hasMatch(_package.text.trim());

  void _pick(LoopApp app) => Navigator.pop(context, (app.package, _rule));

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final apps = _apps;
    final query = _search.text.trim().toLowerCase();
    final shown = apps == null
        ? const <LoopApp>[]
        : (query.isEmpty
              ? apps
              : apps
                    .where((a) => a.label.toLowerCase().contains(query) || a.package.toLowerCase().contains(query))
                    .toList());
    return AlertDialog(
      title: Text(l10n.phoneAppRulesAdd),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 规则先选：挑完应用就直接生效，不会再问一遍。
            Center(
              child: SegmentedPill<AppRule>(
                expand: false,
                segments: [
                  PillSegment(AppRule.deny, l10n.phoneAppRuleDeny),
                  PillSegment(AppRule.confirmEachStep, l10n.phoneAppRuleConfirm),
                ],
                selected: _rule,
                onChanged: (rule) => setState(() => _rule = rule),
              ),
            ),
            const SizedBox(height: 16),
            if (apps == null)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (!_manual) ...[
              TextField(
                controller: _search,
                autocorrect: false,
                decoration: const InputDecoration(labelText: '搜应用', hintText: '微信、抖音……'),
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: 8),
              SizedBox(
                height: 232,
                child: shown.isEmpty
                    ? const Center(child: Text('没找到，试试手动输入包名'))
                    : ListView.builder(
                        shrinkWrap: true,
                        itemCount: shown.length,
                        itemBuilder: (context, i) {
                          final app = shown[i];
                          return ListTile(
                            dense: true,
                            title: Text(app.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                            subtitle: Text(app.package, maxLines: 1, overflow: TextOverflow.ellipsis),
                            onTap: () => _pick(app),
                          );
                        },
                      ),
              ),
            ] else ...[
              TextField(
                controller: _package,
                autofocus: true,
                autocorrect: false,
                decoration: InputDecoration(
                  labelText: l10n.phoneAppRulesPackage,
                  hintText: 'com.taobao.taobao',
                  helperText: l10n.phoneAppRulesPackageHint,
                ),
                onChanged: (_) => setState(() {}),
              ),
            ],
          ],
        ),
      ),
      actions: [
        if (apps != null)
          TextButton(
            onPressed: () => setState(() {
              _manual = !_manual;
              _package.clear();
            }),
            child: Text(_manual ? '从已安装里选' : '手动输入包名'),
          ),
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.commonCancel)),
        if (_manual)
          FilledButton(
            onPressed: _valid ? () => Navigator.pop(context, (_package.text.trim(), _rule)) : null,
            child: Text(l10n.commonAdd),
          ),
      ],
    );
  }
}

extension on String {
  String? ifEmpty(String? other) => isEmpty ? other : this;
}

/// 应用管理里的「Gemini 互动」：开一场连麦，期间只许 Gemini（电话/短信/地图/支付除外）。
/// 两个人格也能在聊天里用 gemini_live / gemini_end 开、关同一场；这里只是手机上的一处入口。
/// 墙（规则执行层）：只读地显示规则现在的状态。
///
/// 刻意没有「放松」按钮——放松只能走晚上对账后的申请窗口；设置页不该再开一个当场解锁的口子。
class _WallCard extends StatefulWidget {
  const _WallCard();

  @override
  State<_WallCard> createState() => _WallCardState();
}

class _WallCardState extends State<_WallCard> {
  final ProactiveChannel _channel = ProactiveChannel();
  Map<String, Object?>? _wall;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final wall = await _channel.wallStatus();
    if (!mounted) return;
    setState(() => _wall = wall);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final w = _wall;
    // 读不到就整张卡不出现：设置页不该为墙的离线多一行噪音。
    if (w == null) return const SizedBox.shrink();
    final locked = w['locked'] == true;
    final why = (w['why'] as String?)?.trim() ?? '';
    final days = (w['relaxRemaining'] as num?)?.toInt() ?? 0;
    final changed = w['relaxToday'] == true || w['tightenToday'] == true;
    // 第五轮：额度可能是 0，而原因是四段没交齐。界面必须把这句话说出来，
    // 否则他只看到「还剩 0 天」——那是个没有解释的数字。
    final missing = (w['depositMissing'] as String?)?.trim() ?? '';
    final done = (w['depositDone'] as num?)?.toInt() ?? 0;
    final total = (w['depositTotal'] as num?)?.toInt() ?? 4;
    return ListPageCard(
      children: [
        ListPageRow(
          icon: AppIcons.taskBlocked,
          iconColor: Theme.of(context).colorScheme.primary,
          title: l10n.phoneWallTitle,
          subtitle: locked && why.isNotEmpty ? l10n.phoneWallLocked(why) : l10n.phoneWallUnlocked,
          subtitleMaxLines: 3,
        ),
        ListPageRow(
          icon: AppIcons.queued,
          title: l10n.phoneWallQuota(days),
          subtitle: missing.isNotEmpty
              ? '先记 0：今天四段还没交齐（$done/$total），还缺$missing。点这里去交'
              : changed ? l10n.phoneWallTodayChanged : l10n.phoneWallTodaySame,
          subtitleMaxLines: 3,
          chevron: missing.isNotEmpty,
          onTap: missing.isEmpty ? null : () => _pushDeposit(),
        ),
      ],
    );
  }

  /// 额度被卡住时，从这里直接进四段提交页——不然「为什么是 0」只能靠猜。
  Future<void> _pushDeposit() async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const DepositPage()));
    if (mounted) await _load();
  }
}

class _GeminiSessionCard extends StatefulWidget {
  const _GeminiSessionCard();

  @override
  State<_GeminiSessionCard> createState() => _GeminiSessionCardState();
}

class _GeminiSessionCardState extends State<_GeminiSessionCard> {
  final ProactiveChannel _channel = ProactiveChannel();
  Map<String, Object?>? _session;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final sessions = await _channel.agentSessions();
    if (!mounted) return;
    setState(() => _session = sessions.geminiSession);
  }

  int get _minutesLeft {
    final until = (_session?['until'] as num?)?.toInt() ?? 0;
    if (until <= 0) return 0;
    final left = ((until - DateTime.now().millisecondsSinceEpoch) / 60000).ceil();
    return left < 0 ? 0 : left;
  }

  Future<void> _end() async {
    setState(() => _busy = true);
    final r = await _channel.geminiSession(action: 'end');
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (r != null) _session = null;
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(r == null ? context.l10n.phoneGeminiFailed : context.l10n.phoneGeminiEnded)),
    );
  }

  Future<void> _start() async {
    final plan = await showDialog<_GeminiPlan>(context: context, builder: (_) => const _GeminiStartDialog());
    if (plan == null || !mounted) return;
    setState(() => _busy = true);
    final r = await _channel.geminiSession(action: 'start', topic: plan.topic, minutes: plan.minutes, mode: plan.mode);
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (r != null) _session = r;
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(r == null ? context.l10n.phoneGeminiFailed : context.l10n.phoneGeminiStarted)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final s = _session;
    final topic = (s?['topic'] as String?)?.trim() ?? '';
    return ListPageCard(
      children: [
        ListPageRow(
          icon: AppIcons.chat,
          iconColor: Theme.of(context).colorScheme.primary,
          title: l10n.phoneGeminiTitle,
          subtitle: s == null
              ? l10n.phoneGeminiHint
              : l10n.phoneGeminiRunning(topic.isEmpty ? l10n.phoneGeminiEmptyTopic : topic, _minutesLeft),
          subtitleMaxLines: 3,
          onTap: _busy ? null : () => s == null ? _start() : _end(),
        ),
      ],
    );
  }
}

class _GeminiPlan {
  const _GeminiPlan(this.topic, this.minutes, this.mode);

  final String topic;
  final int minutes;
  final String mode;
}

class _GeminiStartDialog extends StatefulWidget {
  const _GeminiStartDialog();

  @override
  State<_GeminiStartDialog> createState() => _GeminiStartDialogState();
}

class _GeminiStartDialogState extends State<_GeminiStartDialog> {
  final _topic = TextEditingController();
  int _minutes = 30;
  String _mode = 'study';

  @override
  void dispose() {
    _topic.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return AlertDialog(
      title: Text(l10n.phoneGeminiTitle),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _topic,
              autofocus: true,
              decoration: InputDecoration(hintText: l10n.phoneGeminiTopic),
            ),
            const SizedBox(height: 16),
            Text(l10n.phoneGeminiMinutes, style: Theme.of(context).textTheme.labelLarge),
            Wrap(
              spacing: 8,
              children: [
                for (final m in const [15, 30, 45, 60])
                  ChoiceChip(
                    label: Text('$m'),
                    selected: _minutes == m,
                    onSelected: (_) => setState(() => _minutes = m),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Text(l10n.phoneGeminiMode, style: Theme.of(context).textTheme.labelLarge),
            Wrap(
              spacing: 8,
              children: [
                for (final (mode, label) in [
                  ('study', l10n.phoneGeminiStudy),
                  ('think', l10n.phoneGeminiThink),
                  ('interrogate', l10n.phoneGeminiInterrogate),
                ])
                  ChoiceChip(
                    label: Text(label),
                    selected: _mode == mode,
                    onSelected: (_) => setState(() => _mode = mode),
                  ),
              ],
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.commonCancel)),
        FilledButton(
          onPressed: () => Navigator.pop(context, _GeminiPlan(_topic.text.trim(), _minutes, _mode)),
          child: Text(l10n.phoneGeminiStart),
        ),
      ],
    );
  }
}
