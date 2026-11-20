import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/capsule_chip.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'app_picker_dialog.dart';
import 'plan_page.dart';
import '../../ui/common/dispose_after_route.dart';

enum _PlanTab { contract, schedule, audit }

/// DSH Go V4 · AI 协同计划模态专属页
/// 特性：
/// 1) 情景探测：核对本机现有 App 是否足以支撑目标（如看视频学习 + 笔记提炼）；
/// 2) 结构化契约 + 实际验收点：支持“看视频压缩提炼后由 AI 高强度追问验收”；
/// 3) 柔性护栏 + 1 分钟缓冲自动转严格锁：约定期间不乱打断，逾期 60s 未通过自动严格锁定；
/// 4) 内置「红旗防骗扫描」与「反沉没成本/主航道对齐」实战认知 Skill 体检入口。
class PlanModalityPage extends StatefulWidget {
  const PlanModalityPage({super.key, this.channel, this.loopChannel});

  final ProactiveChannel? channel;
  final LoopChannel? loopChannel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel, LoopChannel? loopChannel}) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => PlanModalityPage(channel: channel, loopChannel: loopChannel),
        ),
      );

  @override
  State<PlanModalityPage> createState() => _PlanModalityPageState();
}

class _PlanModalityPageState extends State<PlanModalityPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final LoopChannel _loop = widget.loopChannel ?? LoopChannel();

  final _titleCtrl = TextEditingController();
  final _scenarioCtrl = TextEditingController();
  final _cpTitleCtrl = TextEditingController();
  final _deliverableCtrl = TextEditingController(text: '提交压缩因果提炼并完成高强度追问');
  final _auditInputCtrl = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  _PlanTab _tab = _PlanTab.contract;
  int _minutes = 45;
  String _verifyMode = 'video_extract';
  final Set<String> _allowed = {'md.obsidian'};
  Map<String, dynamic>? _contract;
  Map<String, dynamic>? _graceWarning;
  Map<String, dynamic>? _appProbe;
  List<Map<String, dynamic>> _remotePresets = const [];
  String? _banner;
  Timer? _poll;

  static const _fallbackPresets = <Map<String, dynamic>>[
    {
      'id': 'video_extract_study',
      'shortLabel': '📺 网课提炼 45m',
      'title': '看网课并提炼 1 张三段因果卡',
      'scenario': '用 B站/网课 App 听课，在 Obsidian 里提炼三段因果链并过高强度追问',
      'checkpointTitle': '提交视频压缩提炼与 2 句因果追问',
      'mode': 'video_extract',
      'minutes': 45,
      'expectedDeliverable': '提交 [触发→机制→行动] 因果链，逾期 1 分钟自动严锁视频 App',
      'allowedApps': ['md.obsidian', 'tv.danmaku.bili', 'com.tencent.mm'],
      'copyPrompt': '我要开启「网课提炼契约（45分钟）」，用 B站 看课并在 Obsidian 提炼。请调 plan_check_apps 与 plan_set_contract 立下 45 分钟检查点（逾期缓冲 1 分钟转严锁），我看完发你高强度追问验收。',
    },
    {
      'id': 'exam_drill_sprint',
      'shortLabel': '📝 考公刷题 60m',
      'title': '考公行测/申论专项刷题与错题归档',
      'scenario': '集中刷 1 套真题并整理 3 道错题因果原因，支持导入锁屏题库',
      'checkpointTitle': '完成本轮套题并提炼 3 道核心错题原因',
      'mode': 'standard',
      'minutes': 60,
      'expectedDeliverable': '提交本轮题量、正确率与 3 条错因规则',
      'allowedApps': ['md.obsidian', 'com.tencent.mm'],
      'copyPrompt': '我要开始「考公真题冲刺（60分钟）」。请调 plan_set_contract 帮我立下 60 分钟契约，放行 Obsidian/微信，到点缓冲 1 分钟没交「题量+正确率+3条错因」就自动转严锁。',
    },
    {
      'id': 'job_resume_apply',
      'shortLabel': '💼 简历投递 45m',
      'title': '简历项目打磨与定向岗位投递',
      'scenario': '优化简历量化亮点并核对岗位 JD 完成真实投递',
      'checkpointTitle': '提交简历改动要点与本轮投递家数',
      'mode': 'standard',
      'minutes': 45,
      'expectedDeliverable': '至少优化 1 处量化成果表述并实际投递/沟通 ≥3 家',
      'allowedApps': ['md.obsidian', 'cn.wps.moffice_eng', 'com.tencent.mm'],
      'copyPrompt': '我要做「就业简历优化与投递（45分钟）」。请调 plan_set_contract 立契约：放行 Obsidian/WPS/微信，45 分钟后验收修改结果与投递记录。',
    },
    {
      'id': 'rest_recover_contract',
      'shortLabel': '☕ 合法放风 20m',
      'title': '合法休息放风（到点自动收心回位）',
      'scenario': '高强度专注后合法休息 20 分钟，到期确认收心并写下第一动作',
      'checkpointTitle': '确认休息结束并报备下一轮第一动作',
      'mode': 'standard',
      'minutes': 20,
      'expectedDeliverable': '一句话写明接下来 45 分钟唯一动作',
      'allowedApps': ['md.obsidian', 'com.tencent.mm'],
      'copyPrompt': '我刚完成一轮工作，申请「合法休息 20 分钟」。请调 plan_set_contract 定 20 分钟检查点，到点缓冲 1 分钟没回来说下一轮干嘛就自动严锁回工位。',
    },
  ];

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => unawaited(_refresh(silent: true)));
  }

  @override
  void dispose() {
    _poll?.cancel();
    _titleCtrl.dispose();
    _scenarioCtrl.dispose();
    _cpTitleCtrl.dispose();
    _deliverableCtrl.dispose();
    _auditInputCtrl.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool silent = false}) async {
    final res = await _channel.planContractOp();
    if (!mounted) return;
    setState(() {
      if (!silent) _loading = false;
      _contract = res?['contract'] is Map ? Map<String, dynamic>.from(res!['contract'] as Map) : null;
      _graceWarning = res?['graceWarning'] is Map ? Map<String, dynamic>.from(res!['graceWarning'] as Map) : null;
      if (res?['presets'] is List) {
        _remotePresets = [
          for (final x in res!['presets'] as List)
            if (x is Map) Map<String, dynamic>.from(x),
        ];
      }
    });
  }

  void _applyPreset(Map<String, dynamic> preset) {
    final minsRaw = preset['minutes'];
    final mins = minsRaw is num ? minsRaw.toInt() : 45;
    final allowRaw = preset['allowedApps'];
    setState(() {
      _titleCtrl.text = '${preset['title'] ?? ''}';
      _scenarioCtrl.text = '${preset['scenario'] ?? ''}';
      _cpTitleCtrl.text = '${preset['checkpointTitle'] ?? preset['title'] ?? ''}';
      _deliverableCtrl.text = '${preset['expectedDeliverable'] ?? ''}';
      _verifyMode = preset['mode'] == 'video_extract' ? 'video_extract' : 'standard';
      _minutes = const [20, 45, 60, 90].contains(mins) ? mins : 45;
      if (allowRaw is List && allowRaw.isNotEmpty) {
        _allowed
          ..clear()
          ..addAll(allowRaw.map((e) => '$e').where((s) => s.isNotEmpty));
      }
      _banner = '✓ 已套用模板「${preset['shortLabel'] ?? preset['title']}」——直接点下方按钮开跑，无需手写';
    });
  }

  Future<void> _copyAiPlanPrompt([String? customPrompt]) async {
    final title = _titleCtrl.text.trim().isEmpty ? '当前学习与推进任务' : _titleCtrl.text.trim();
    final scenario = _scenarioCtrl.text.trim();
    final deliv = _deliverableCtrl.text.trim();
    final prompt = customPrompt ??
        '我要进入 AI 计划模态：$title${scenario.isNotEmpty ? '（场景：$scenario）' : ''}。'
        '请先调 plan_check_apps 检查我手机里现有 App（当前打算放行 ${_allowed.join('、')}），'
        '再调 plan_set_contract 立下 $_minutes 分钟检查点（验收模式：${_verifyMode == 'video_extract' ? 'video_extract 视频提炼高强度追问' : 'standard 标准交付'}，'
        '验收标准：$deliv，到点缓冲 1 分钟未过自动严格锁）。';
    await Clipboard.setData(ClipboardData(text: prompt));
    if (!mounted) return;
    setState(() => _banner = '✓ 已复制标准计划指令到剪贴板，直接粘贴发给 鱼丸/鲸澜/Gemini 即可！');
  }

  Future<void> _probeApps() async {
    final sc = _scenarioCtrl.text.trim().isNotEmpty ? _scenarioCtrl.text.trim() : _titleCtrl.text.trim();
    if (sc.isEmpty) {
      setState(() => _banner = '请先填一句打算干什么（例如：看 B 站视频学资料分析并用 Obsidian 提炼）');
      return;
    }
    setState(() => _busy = true);
    final res = await _channel.planContractOp({
      'action': 'check_apps',
      'scenario': sc,
      'allowedApps': _allowed.toList(),
    });
    if (!mounted) return;
    setState(() {
      _busy = false;
      _appProbe = res;
      final rec = res?['recommendedAllow'];
      if (rec is List) {
        for (final item in rec) {
          if (item is Map && item['app'] is String) _allowed.add('${item['app']}');
        }
      }
      _banner = res?['sufficient'] == true
          ? '✓ 现有 App 检查通过，已自动填入推荐白名单'
          : '提示：${(res?['gaps'] as List?)?.join('；') ?? '请核对需要放行的 App'}';
    });
  }

  Future<void> _pickApps() async {
    final picked = await showInstalledAppsPicker(
      context,
      title: '选择本契约期间允许使用的 App',
      loop: _loop,
      initialSelected: _allowed.toList(),
    );
    if (picked == null || !mounted) return;
    setState(() {
      _allowed
        ..clear()
        ..addAll(picked.map((a) => a.package));
    });
  }

  Future<void> _createContract() async {
    final title = _titleCtrl.text.trim();
    final cpTitle = _cpTitleCtrl.text.trim().isNotEmpty ? _cpTitleCtrl.text.trim() : title;
    if (title.isEmpty) {
      setState(() => _banner = '请填写计划契约标题（如：攻克申论第 2 套提炼）');
      return;
    }
    setState(() => _busy = true);
    final now = DateTime.now().millisecondsSinceEpoch;
    final res = await _channel.planContractOp({
      'action': 'set',
      'title': title,
      'scenario': _scenarioCtrl.text.trim(),
      'allowedApps': _allowed.toList(),
      'strictOnOverdue': true,
      'checkpoints': [
        {
          'id': 'cp-${now.toRadixString(36)}',
          'title': cpTitle,
          'mode': _verifyMode,
          'expectedDeliverable': _deliverableCtrl.text.trim(),
          'dueAt': now + _minutes * 60000,
          'graceMs': 60000,
        },
      ],
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true
          ? '✓ 契约已生效！$_minutes 分钟内柔性护航，逾期缓冲 1 分钟未验收自动转严格锁'
          : '创建失败：${res?['error'] ?? '引擎暂未就绪'}';
    });
  }

  Future<void> _openVerifySheet(Map<String, dynamic> cp) async {
    final sumCtrl = TextEditingController();
    final res = await showDialog<Map<String, dynamic>?>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('验收点：${cp['title']}'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                cp['mode'] == 'video_extract'
                    ? '把你看完视频/材料后由 AI 压缩提炼的因果链核心发进来（建议包含：因为/所以/机制/结论），系统会先检验因果结构是否敷衍，或直接与大肥鱼高强度对线通过！'
                    : '写下本次产出的物理证据与核心结论：',
                style: Theme.of(ctx).textTheme.bodySmall,
              ),
              const SizedBox(height: 10),
              TextField(
                controller: sumCtrl,
                minLines: 4,
                maxLines: 8,
                decoration: const InputDecoration(
                  labelText: '提交压缩提炼 / 成果结论',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () async {
              final sum = sumCtrl.text.trim();
              final msg = '以下是我围绕验收点「${cp['title']}」压出的提炼结论：\n${sum.isEmpty ? '【请补上你的提炼要点】' : sum}\n'
                  '请调统筹规划部+督办审计部，按底层因果和考场/面试反例高强度追问我 2 句，答对后调 plan_verify_checkpoint(aiConfirmed=true) 放行。';
              await Clipboard.setData(ClipboardData(text: msg));
              if (ctx.mounted) {
                ScaffoldMessenger.maybeOf(ctx)?.showSnackBar(const SnackBar(content: Text('已复制高强度对线指令，可发给 AI 验收')));
              }
            },
            child: const Text('复制发 AI 对线'),
          ),
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () async {
              final r = await _channel.planContractOp({
                'action': 'verify',
                'checkpointId': cp['id'],
                'summary': sumCtrl.text.trim(),
                'aiConfirmed': false,
              });
              if (ctx.mounted) Navigator.pop(ctx, r);
            },
            child: const Text('提交验收'),
          ),
        ],
      ),
    );
    disposeAfterRoute(sumCtrl);
    if (!mounted || res == null) return;
    await _refresh();
    if (!mounted) return;
    setState(() {
      if (res['passed'] == true) {
        _banner = '✓ 验收通过！已记入正面战绩并触发正向松绑 (autonomyLevel)';
      } else {
        final probes = (res['probes'] as List?)?.join('\n· ') ?? '${res['error'] ?? '内容缺少因果机制，请补充后再交'}';
        _banner = '进入高强度追问：\n· $probes';
      }
    });
  }

  Future<void> _runSkillAudit(String kind) async {
    final txt = _auditInputCtrl.text.trim();
    if (txt.isEmpty) {
      setState(() => _banner = '请先输入要核验的交易情况（如买课/私下付款）或准备折腾的新工具想法');
      return;
    }
    setState(() => _busy = true);
    final res = await _channel.skillsAuditOp({'kind': kind, 'text': txt, 'amount': 80});
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = '${res?['recommendation'] ?? res?['advice'] ?? res?['error'] ?? '体检完成'}';
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final contract = _contract;
    final cps = (contract?['checkpoints'] is List)
        ? [for (final x in (contract!['checkpoints'] as List)) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];

    return AppPage.list(
      title: '计划与契约台',
      subtitle: const Text('契约共创验收 · 全天排局时间表 · 红旗防骗与主线体检'),
      actions: [
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新契约状态',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        SegmentedPill<_PlanTab>(
          segments: const [
            PillSegment(_PlanTab.contract, '契约共创'),
            PillSegment(_PlanTab.schedule, '全天排局表'),
            PillSegment(_PlanTab.audit, '防骗与主线体检'),
          ],
          selected: _tab,
          onChanged: (next) => setState(() => _tab = next),
        ),
        const SizedBox(height: 12),
        if (_banner != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: GlassSurface(
              tier: GlassTier.card,
              borderRadius: AppShape.radiusMd,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(_banner!, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.primary)),
              ),
            ),
          ),
        if (_tab == _PlanTab.schedule)
          PlanPage(channel: _channel, loop: _loop, asTab: true)
        else if (_tab == _PlanTab.contract) ...[
          if (_graceWarning != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: GlassSurface(
                tier: GlassTier.card,
                borderRadius: AppShape.radiusMd,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Row(
                    children: [
                      Icon(AppIcons.warning, color: colors.warning),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          '⚠️ 验收点「${_graceWarning!['title']}」已到点！距自动转严格锁还剩 ${_graceWarning!['remainSec']} 秒',
                          style: theme.textTheme.bodyMedium?.copyWith(color: colors.warning, fontWeight: FontWeight.w700),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          if (contract != null) ...[
          OverviewCard(
            margin: const EdgeInsets.only(bottom: 14),
            leading: OverviewGlyph(AppIcons.planMode, tint: contract['strictEscalated'] == true ? 1 : 0),
            title: Text('${contract['title']}'),
            subtitle: Text('允许应用：${(contract['allowedApps'] as List?)?.join('、') ?? '默认'}'),
            trailing: StatusChip(
              tone: contract['strictEscalated'] == true ? StatusTone.error : StatusTone.ok,
              label: contract['strictEscalated'] == true ? '逾期已严格锁' : '契约护航中',
            ),
            footer: OverviewFooter(
              tone: StatusTone.idle,
              text: _loading ? '读取中…' : '到期未验收 → 1 分钟缓冲倒计时 → 自动开启严格锁',
            ),
          ),
          GlassSurface(
            tier: GlassTier.card,
            borderRadius: AppShape.radiusLg,
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(child: Text('实际验收点列表', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700))),
                      TextButton(
                        onPressed: _busy ? null : () async {
                          await _channel.planContractOp({'action': 'clear'});
                          await _refresh();
                        },
                        child: const Text('结束契约'),
                      ),
                    ],
                  ),
                  for (final cp in cps) ...[
                    const Divider(height: 16),
                    Row(
                      children: [
                        Icon(
                          cp['status'] == 'passed'
                              ? AppIcons.success
                              : (cp['status'] == 'overdue_locked' ? AppIcons.lock : AppIcons.goal),
                          size: 18,
                          color: cp['status'] == 'passed' ? colors.success : theme.colorScheme.primary,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('${cp['title']}', style: theme.textTheme.titleSmall),
                              Text(
                                '${cp['mode'] == 'video_extract' ? '视频提炼高强度验收' : '标准交付'} · ${cp['expectedDeliverable']}',
                                style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                              ),
                            ],
                          ),
                        ),
                        if (cp['status'] != 'passed')
                          FilledButton.tonal(
                            onPressed: () => unawaited(_openVerifySheet(cp)),
                            child: const Text('去验收'),
                          )
                        else
                          const StatusChip(tone: StatusTone.ok, label: '已通过'),
                      ],
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 14),
        ],
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('新建 / 共创计划模态契约（点预设模板免打字）', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      for (final p in (_remotePresets.isNotEmpty ? _remotePresets : _fallbackPresets)) ...[
                        CapsuleChoiceChip(
                          label: '${p['shortLabel'] ?? p['title']}',
                          selected: _titleCtrl.text.trim() == '${p['title']}',
                          onSelected: () => _applyPreset(p),
                        ),
                        const SizedBox(width: 6),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: _titleCtrl,
                  decoration: const InputDecoration(
                    labelText: '本段要打掉的目标（例如：看 B 站行测课并提炼 1 张因果卡）',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: _scenarioCtrl,
                  decoration: const InputDecoration(
                    labelText: '使用场景与工具（AI 将自动检查本机 App 是否够用）',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    CapsuleChoiceChip(
                      label: '🔍 检测本机 App 够不够',
                      icon: AppIcons.askAi,
                      selected: false,
                      onSelected: _busy ? () {} : () => unawaited(_probeApps()),
                    ),
                    CapsuleChoiceChip(
                      label: '已选 ${_allowed.length} 个可用 App',
                      icon: AppIcons.files,
                      selected: false,
                      onSelected: _busy ? () {} : () => unawaited(_pickApps()),
                    ),
                    CapsuleChoiceChip(
                      label: '复制发 AI 对齐',
                      icon: AppIcons.copy,
                      selected: false,
                      onSelected: () => unawaited(_copyAiPlanPrompt()),
                    ),
                  ],
                ),
                if (_appProbe != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      '检测结果：${(_appProbe!['gaps'] as List?)?.isNotEmpty == true ? (_appProbe!['gaps'] as List).join('；') : '现有 App 充足'}',
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    ),
                  ),
                const SizedBox(height: 10),
                Text('验收模式', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                const SizedBox(height: 6),
                SegmentedPill<String>(
                  segments: const [
                    PillSegment('video_extract', '视频提炼高强度验收'),
                    PillSegment('standard', '普通证据验收'),
                  ],
                  selected: _verifyMode,
                  onChanged: (v) => setState(() => _verifyMode = v),
                ),
                const SizedBox(height: 10),
                Text('缓冲期限（到点后 1 分钟仍未过关自动转严格锁）', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                const SizedBox(height: 6),
                SegmentedPill<int>(
                  segments: const [
                    PillSegment(20, '20m'),
                    PillSegment(45, '45m'),
                    PillSegment(60, '60m'),
                    PillSegment(90, '90m'),
                  ],
                  selected: const [20, 45, 60, 90].contains(_minutes) ? _minutes : 45,
                  onChanged: (v) => setState(() => _minutes = v),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: _deliverableCtrl,
                  decoration: const InputDecoration(
                    labelText: '验收标准（届时需提交什么才能过）',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  onPressed: _busy ? null : () => unawaited(_createContract()),
                  icon: const Icon(AppIcons.play, size: 18),
                  label: const Text('签下契约并开始（逾期 1 分钟自动严格锁）'),
                ),
              ],
            ),
          ),
        ),
        ] else
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('四部实战认知 Skill 快速体检（防骗 / 防偏航）', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 6),
                Text(
                  '付款买课遇到私聊定金、或者又想手搓新工具时，直接放进来扫一遍红旗与主线对齐度。',
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                ),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    CapsuleChoiceChip(
                      label: '例：私聊买课 80 元',
                      selected: false,
                      onSelected: () => setState(() {
                        _auditInputCtrl.text = '对方让私聊微信先付定金80元买资料包，说马上涨价且不退不换，刚注册小号';
                      }),
                    ),
                    CapsuleChoiceChip(
                      label: '例：想改写软件配置',
                      selected: false,
                      onSelected: () => setState(() {
                        _auditInputCtrl.text = '我想暂停刷题，花一下午重写一套新脚本和UI配置提示词';
                      }),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _auditInputCtrl,
                  minLines: 2,
                  maxLines: 4,
                  decoration: const InputDecoration(
                    labelText: '描述这笔消费/卖家特征，或你想开的新折腾计划',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _busy ? null : () => unawaited(_runSkillAudit('redflag')),
                        child: const Text('红旗防骗扫描'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: FilledButton.tonal(
                        onPressed: _busy ? null : () => unawaited(_runSkillAudit('mainline')),
                        child: const Text('反沉没主线审计'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
