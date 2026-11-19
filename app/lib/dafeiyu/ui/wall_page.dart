import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';
import '../channels/proactive_state.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/status_chip.dart';
import 'companion_hub_page.dart';
import 'bank_manager_page.dart';
import 'deposit_page.dart';
import 'plan_modality_page.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../dafeiyu_strings.dart';

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
  Map<String, dynamic>? _today;
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
    // 出口次数、题库设定从 /today 来；慢了也不挡墙本身的状态和记录，单独读。
    unawaited(_channel.todayStatus().then((t) {
      if (mounted && t != null) setState(() => _today = t);
    }).catchError((_) {}));
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
      title: DafeiyuStrings.fromL10n(l).wallTitle,
      subtitle: const Text('只收证据和申诉。墙不聊天，也不谈判。'),
      actions: [
        GlassIconButton(
          icon: AppIcons.plugin,
          tooltip: '大肥鱼伴侣中枢',
          onPressed: () => unawaited(CompanionHubPage.open(context)),
        ),
        GlassIconButton(icon: AppIcons.refresh, tooltip: DafeiyuStrings.fromL10n(l).wallRefresh, onPressed: () => unawaited(_refresh())),
      ],
      children: [
        _statusHero(l),
        if (_loading)
          const ListPageCard(children: [ListPageRow(icon: AppIcons.loading, title: '正在读墙的记录…')])
        else if (_failed)
          ListPageCard(
            children: [
              ListPageRow(
                icon: AppIcons.warning,
                title: DafeiyuStrings.fromL10n(l).wallThreadFailed.replaceAll('{reason}', _failedWhy),
                titleMaxLines: 3,
              ),
            ],
          )
        else if (_thread == null || _thread!.isEmpty)
          ListPageCard(
            title: '审查记录',
            children: [
              ListPageRow(icon: AppIcons.info, title: DafeiyuStrings.fromL10n(l).wallThreadEmpty, titleMaxLines: 3),
            ],
          )
        else
          ListPageCard(
            title: '审查记录 · ${_thread!.bubbles.length} 条（点判定可申诉）',
            children: [for (final b in _thread!.bubbles) _bubble(l, b)],
          ),
        const SizedBox(height: 20),
        _dueToday(),
        _wayOut(),
        const SizedBox(height: 20),
        _actions(l),
        const SizedBox(height: 20),
        ListPageCard(
          title: '验收',
          children: [
            ListPageRow(
              icon: AppIcons.planMode,
              title: '交视频提炼 / 高强度问答验收',
              subtitle: '在 AI 计划模态里提交总结、触发主线检查点',
              subtitleMaxLines: 2,
              chevron: true,
              onTap: () => unawaited(PlanModalityPage.open(context, channel: _channel)),
            ),
          ],
        ),
        const SizedBox(height: 24),
      ],
    );
  }

  /// 顶部状态：锁没锁（大字）、锁到几点、原因；下面一排今天的判定数和申诉额度。
  Widget _statusHero(L10n l) {
    final s = _status;
    final colors = DshColors.of(context);
    final theme = Theme.of(context);
    if (s == null) {
      return ListPageCard(
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
        children: [
          ListPageRow(icon: AppIcons.warning, title: DafeiyuStrings.fromL10n(l).wallStatusUnknown, titleMaxLines: 2),
        ],
      );
    }
    final locked = s['locked'] == true;
    final until = s['openUntil'] is num ? (s['openUntil'] as num).toInt() : 0;
    final why = s['why'] is String ? s['why'] as String : '';
    final missing = s['depositMissing'] is String ? s['depositMissing'] as String : '';
    final relaxRem = s['relaxRemaining'] is num ? (s['relaxRemaining'] as num).toInt() : null;
    final tone = locked ? colors.warning : colors.success;
    final t = _thread;
    final verdicts = (t?.bubbles ?? const <WallBubble>[]).where((b) => !b.isSubmit && !b.isRule).toList();
    final passN = verdicts.where((b) => b.pass == true).length;

    Widget stat(String label, String value, Color color) => Expanded(
          child: Column(
            children: [
              Text(value, style: AppType.tabular(theme.textTheme.titleLarge!.copyWith(fontWeight: FontWeight.w700, color: color))),
              const SizedBox(height: 2),
              Text(label, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
            ],
          ),
        );

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
      child: GlassSurface(
        key: const ValueKey('wall-status-hero'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 18, 16, 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  DecoratedBox(
                    decoration: ShapeDecoration(color: tone.withValues(alpha: 0.14), shape: const CircleBorder()),
                    child: SizedBox(
                      width: 52,
                      height: 52,
                      child: Icon(locked ? AppIcons.lock : AppIcons.unlock, size: 26, color: tone),
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          locked ? DafeiyuStrings.fromL10n(l).wallStatusLocked : DafeiyuStrings.fromL10n(l).wallStatusOpen,
                          style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700, color: tone),
                        ),
                        if (locked && until > 0)
                          Text(
                            DafeiyuStrings.fromL10n(l).wallStatusUntil.replaceAll('{time}', _hhmm(until)),
                            style: AppType.tabular(theme.textTheme.titleSmall!.copyWith(color: theme.colorScheme.onSurface)),
                          ),
                      ],
                    ),
                  ),
                  StatusChip(
                    tone: locked ? StatusTone.warn : StatusTone.ok,
                    label: locked ? '门禁拦截中' : '门禁已放行',
                  ),
                ],
              ),
              if (why.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text(
                  DafeiyuStrings.fromL10n(l).wallStatusWhy.replaceAll('{why}', why),
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodyMedium,
                ),
              ],
              if (missing.isNotEmpty || relaxRem != null) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    if (missing.isNotEmpty) StatusChip(tone: StatusTone.warn, label: '待补：$missing'),
                    if (relaxRem != null) StatusChip(tone: StatusTone.idle, label: '本周余放松额度 $relaxRem 天'),
                  ],
                ),
              ],
              if (t != null) ...[
                const SizedBox(height: 14),
                Divider(height: 1, thickness: 0.5, color: theme.colorScheme.outlineVariant.withValues(alpha: 0.45)),
                const SizedBox(height: 12),
                Row(
                  children: [
                    stat('判定', '${verdicts.length}', theme.colorScheme.onSurface),
                    stat('通过', '$passN', colors.success),
                    stat('没过', '${verdicts.length - passN}', colors.warning),
                    stat('余申诉', '${t.appealLeft}', colors.info),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  /// 今天要交给墙的：每日四部门（从中枢挪过来——它本来就是交给墙验的）。
  Widget _dueToday() {
    final colors = DshColors.of(context);
    final missing = _status?['depositMissing'] is String ? _status!['depositMissing'] as String : '';
    return ListPageCard(
      title: '今天要交的',
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
      children: [
        ListPageRow(
          key: const ValueKey('wall-deposit'),
          icon: AppIcons.edit,
          iconColor: missing.isEmpty ? colors.success : colors.warning,
          title: '每日四部门',
          subtitle: missing.isEmpty ? '信息收集 · 谋划思考 · 行为管理 · 统筹规划' : '还没交：$missing',
          subtitleMaxLines: 2,
          chevron: true,
          onTap: () async {
            await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const DepositPage()));
            if (mounted) unawaited(_refresh());
          },
        ),
      ],
    );
  }

  /// 出不去怎么办：所有解锁出口列在一处，今天还剩几次也在这里说清。
  /// 紧急解除、破窗、紧急通行、长按回 DSH 共用同一个每日次数（插件 passLedger.emergency 按 disarmId 去重计）。
  Widget _wayOut() {
    final colors = DshColors.of(context);
    final pl = _today?['passLedger'] is Map ? _today!['passLedger'] as Map : const {};
    final em = pl['emergency'] is Map ? pl['emergency'] as Map : const {};
    final cap = em['cap'] is num ? (em['cap'] as num).toInt() : (_today?['emergencyDisarmDailyCap'] is num ? (_today!['emergencyDisarmDailyCap'] as num).toInt() : 3);
    final left = em['left'] is num ? (em['left'] as num).toInt() : null;
    final bank = _today?['bankSummary'] is Map ? _today!['bankSummary'] as Map : const {};
    final need = bank['requiredCount'] is num ? (bank['requiredCount'] as num).toInt() : 10;
    final mins = bank['unlockMinutes'] is num ? (bank['unlockMinutes'] as num).toInt() : 5;
    return ListPageCard(
      key: const ValueKey('wall-way-out'),
      title: '出不去怎么办',
      children: [
        ListPageRow(
          icon: AppIcons.warning,
          iconColor: colors.danger,
          title: '紧急解除',
          subtitle: '下拉通知栏点「紧急解除」写一句理由，10 分钟不锁。破窗、长按回 DSH 也算在这几次里',
          subtitleMaxLines: 3,
          value: left == null ? '每天 $cap 次' : '剩 $left/$cap',
        ),
        ListPageRow(
          icon: AppIcons.done,
          iconColor: colors.success,
          title: '过一局验收',
          subtitle: '挣娱乐余额：按局长的 1/3（10–40 分钟），没花完明天接着用',
          subtitleMaxLines: 2,
        ),
        ListPageRow(
          icon: AppIcons.skill,
          iconColor: colors.info,
          title: '刷题',
          subtitle: '答对 $need 题挣 $mins 分钟余额',
          chevron: true,
          onTap: () => unawaited(BankManagerPage.open(context, channel: _channel)),
        ),
        const ListPageRow(
          icon: AppIcons.chat,
          title: '找鲸澜要通行证',
          subtitle: '在对话里说清楚要用哪个 App、做什么、多久；她判，给一段限时通行',
          subtitleMaxLines: 2,
        ),
        const ListPageRow(
          icon: AppIcons.history,
          title: '对判定不服',
          subtitle: '看下面「申诉」：只收新的物理证据',
        ),
      ],
    );
  }

  /// 一条记录：一行一条，完整原话，不截断；判定按过/没过上色，点判定发起申诉。
  Widget _bubble(L10n l, WallBubble b) {
    final colors = DshColors.of(context);
    if (b.isSubmit) {
      return ListPageRow(
        icon: AppIcons.edit,
        iconColor: colors.info,
        title: '${DafeiyuStrings.fromL10n(l).wallBubbleSubmit} · ${b.who}',
        subtitle: b.text,
        subtitleMaxLines: 20,
        value: _hhmm(b.at),
      );
    }
    if (b.isRule) {
      return ListPageRow(
        icon: AppIcons.verified,
        iconColor: Theme.of(context).colorScheme.primary,
        title: '${DafeiyuStrings.fromL10n(l).wallBubbleRule} · ${b.who}',
        subtitle: b.text,
        subtitleMaxLines: 20,
        value: _hhmm(b.at),
      );
    }
    return ListPageRow(
      icon: b.pass == true ? AppIcons.allowed : AppIcons.blocked,
      iconColor: b.pass == true ? colors.success : colors.warning,
      title: '${DafeiyuStrings.fromL10n(l).wallBubbleVerdict} · ${b.who}',
      subtitle: b.text,
      subtitleMaxLines: 24,
      value: _hhmm(b.at),
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
    final verdicts = (t?.bubbles ?? const <WallBubble>[])
        .where((b) => !b.isSubmit && !b.isRule && b.at > 0)
        .toList();
    final target = verdicts.isEmpty ? null : verdicts.last;
    return ListPageCard(
      title: DafeiyuStrings.fromL10n(l).wallActionAppeal,
      children: [
        ListPageRow(
          icon: AppIcons.send,
          title: DafeiyuStrings.fromL10n(l).wallActionSubmit,
          subtitle: '不在这里：对鱼说「交这个」，走 submit_to_wall。',
          subtitleMaxLines: 2,
          onTap: null,
        ),
        ListPageRow(
          icon: AppIcons.history,
          title: left > 0 ? DafeiyuStrings.fromL10n(l).wallAppealLeft.replaceAll('{left}', '$left') : DafeiyuStrings.fromL10n(l).wallAppealNone,
          subtitle: '申诉只收新的物理证据；判还是由墙算。',
          subtitleMaxLines: 2,
        ),
        ListPageRow(
          icon: AppIcons.edit,
          title: DafeiyuStrings.fromL10n(l).wallAppealTarget,
          subtitle: target == null ? '当前还没有可申诉的判定记录。' : '点击立即对最近一条判定发起申诉。',
          chevron: target != null,
          onTap: () {
            if (target == null) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('当前没有可申诉的判定记录。')),
              );
              return;
            }
            unawaited(_appeal(target));
          },
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
    if (code == 'appeal:no-quota') return DafeiyuStrings.fromL10n(l).wallAppealNone;
    if (code == 'appeal:no-new-evidence') return DafeiyuStrings.fromL10n(l).wallAppealNoEvidence;
    if (code == 'appeal:not-verdict') return DafeiyuStrings.fromL10n(l).wallAppealNoTarget;
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
      title: Text(DafeiyuStrings.fromL10n(l).wallActionAppeal),
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
                decoration: InputDecoration(labelText: DafeiyuStrings.fromL10n(l).wallAppealTarget, hintText: '这件事叫什么（可空）'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _evidence,
                autofocus: true,
                maxLines: 3,
                minLines: 2,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: DafeiyuStrings.fromL10n(l).wallAppealEvidence,
                  hintText: DafeiyuStrings.fromL10n(l).wallAppealEvidenceHint,
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
          child: Text(DafeiyuStrings.fromL10n(l).wallAppealSend),
        ),
      ],
    );
  }
}
