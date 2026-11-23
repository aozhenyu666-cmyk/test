import 'dart:async';
import 'dart:math' as math;

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

enum _PlanTab { prepare, build, watch }

/// DSH Go V5 · 计划模态三态工作台（1. 准备 → 2. 构建 → 3. 观看）
/// 保留并融合：
/// 1) 准备态：精力档位三选一 + 昨日未完结一键移入 + 拉 Gemini 挑 1–3 件硬骨头 + 红旗防骗/反沉没主线体检；
/// 2) 构建态：上午/午休(12:00–14:00 放风)/下午/晚间时间盒 + 专注预估 vs 娱乐预算条 + 契约预设与全天排局表；
/// 3) 观看态：24h 垂直时间轴 + 当前时刻红线指示器 + 倒计时/偏航预警 + 一键开干/拆第一步/验收过关。
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
  final _carryInputCtrl = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  _PlanTab _tab = _PlanTab.prepare;
  int _minutes = 45;
  String _verifyMode = 'video_extract';
  final Set<String> _allowed = {'md.obsidian'};

  Map<String, dynamic>? _contract;
  Map<String, dynamic>? _graceWarning;
  Map<String, dynamic>? _appProbe;
  Map<String, dynamic>? _today;
  Map<String, dynamic>? _locksOverview;
  Map<String, dynamic>? _verifySummary;
  Map<String, dynamic>? _custody;
  Map<String, dynamic>? _custodyPolicy;
  List<Map<String, dynamic>> _adaptiveLog = const [];
  List<Map<String, dynamic>> _remotePresets = const [];
  List<LoopApp> _installedApps = const [];

  String _energyLevel = 'mid';
  List<Map<String, dynamic>> _carryoverItems = [];
  List<Map<String, dynamic>> _dayBlocks = [];
  bool _hasLocalBlockEdits = false;
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
      'expectedDeliverable': '至少修改 1 处量化成果表述并实际投递/沟通 ≥3 家',
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
    _dayBlocks = _defaultBlocksForEnergy(_energyLevel);
    unawaited(_refresh());
    unawaited(_loadApps());
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
    _carryInputCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadApps() async {
    try {
      final apps = await _loop.apps();
      if (mounted && apps.isNotEmpty) {
        setState(() => _installedApps = apps);
      }
    } catch (_) {}
  }

  List<Map<String, dynamic>> _mapList(Object? raw) => raw is List
      ? [for (final x in raw) if (x is Map) Map<String, dynamic>.from(x)]
      : const [];

  Future<void> _refresh({bool silent = false}) async {
    unawaited(_channel.todayStatus().then((t) {
      if (!mounted || t == null) return;
      setState(() => _today = t);
    }).catchError((_) {}));
    unawaited(_channel.sealsOp(const {'action': 'locks_overview'}).then((ov) {
      if (!mounted || ov == null) return;
      final rawOv = ov['locksOverview'] is Map ? Map<String, dynamic>.from(ov['locksOverview'] as Map) : Map<String, dynamic>.from(ov);
      if (rawOv.isEmpty) return;
      setState(() => _locksOverview = rawOv);
    }).catchError((_) {}));

    final res = await _channel.planContractOp();
    if (!mounted) return;
    setState(() {
      if (!silent) _loading = false;
      _contract = res?['contract'] is Map ? Map<String, dynamic>.from(res!['contract'] as Map) : null;
      _graceWarning = res?['graceWarning'] is Map ? Map<String, dynamic>.from(res!['graceWarning'] as Map) : null;
      if (res?['presets'] is List) {
        _remotePresets = _mapList(res!['presets']);
      }
      final dayPlan = res?['dayPlan'] is Map ? Map<String, dynamic>.from(res!['dayPlan'] as Map) : null;
      final rawVerify = res?['verifySummary'] ?? dayPlan?['verifySummary'];
      final rawVerifyMap = rawVerify is Map ? Map<String, dynamic>.from(rawVerify) : null;
      if (rawVerifyMap != null) {
        _verifySummary = rawVerifyMap;
      }
      final rawCustody = res?['custody'] ?? dayPlan?['custody'] ?? rawVerifyMap?['custody'];
      if (rawCustody is Map) {
        _custody = Map<String, dynamic>.from(rawCustody);
      } else if (res != null && (dayPlan != null || res.containsKey('custody'))) {
        _custody = null;
      }
      final rawPolicy = res?['custodyPolicy'] ?? dayPlan?['custodyPolicy'] ?? rawVerifyMap?['custodyPolicy'];
      if (rawPolicy is Map) {
        _custodyPolicy = Map<String, dynamic>.from(rawPolicy);
      }
      final rawAdaptive = res?['adaptiveLog'] ?? dayPlan?['adaptiveLog'];
      if (rawAdaptive is List) {
        _adaptiveLog = _mapList(rawAdaptive);
      }
      final serverEnergy = '${dayPlan?['energyLevel'] ?? res?['energyLevel'] ?? _contract?['energyLevel'] ?? ''}'.trim();
      if (const ['low', 'mid', 'high'].contains(serverEnergy) && !_hasLocalBlockEdits) {
        _energyLevel = serverEnergy;
      }
      final serverCarry = _mapList(dayPlan?['carryoverItems'] ?? res?['carryoverItems'] ?? _contract?['carryoverItems']);
      if (serverCarry.isNotEmpty && !_hasLocalBlockEdits) {
        _carryoverItems = serverCarry;
      }
      final serverBlocks = _mapList(dayPlan?['dayBlocks'] ?? res?['dayBlocks'] ?? _contract?['dayBlocks']);
      if (serverBlocks.isNotEmpty && !_hasLocalBlockEdits) {
        _dayBlocks = _sortBlocks(serverBlocks);
      } else if (_dayBlocks.isEmpty) {
        _dayBlocks = _defaultBlocksForEnergy(_energyLevel);
      }
    });
  }

  static int _asInt(Object? raw, int fallback) => raw is num ? raw.round() : fallback;

  static String _slotOf(int startMin) {
    if (startMin < 720) return 'morning';
    if (startMin < 840) return 'lunch';
    if (startMin < 1080) return 'afternoon';
    return 'evening';
  }

  static String _hm(int min) {
    final m = min.clamp(0, 1440);
    return '${(m ~/ 60).toString().padLeft(2, '0')}:${(m % 60).toString().padLeft(2, '0')}';
  }

  List<Map<String, dynamic>> _sortBlocks(List<Map<String, dynamic>> list) {
    final copy = [for (final b in list) Map<String, dynamic>.from(b)];
    copy.sort((a, b) {
      final sa = _asInt(a['startMin'], 540);
      final sb = _asInt(b['startMin'], 540);
      return sa != sb ? sa.compareTo(sb) : _asInt(a['endMin'], 585).compareTo(_asInt(b['endMin'], 585));
    });
    return copy;
  }

  List<Map<String, dynamic>> _defaultBlocksForEnergy(String level) {
    final lunch = <String, dynamic>{
      'id': 'blk-lunch-break',
      'slot': 'lunch',
      'startMin': 720,
      'endMin': 840,
      'title': '午休合法放风（12:00–14:00 自动免锁）',
      'deliverable': '14:00 准时回工位开启下午块',
      'lockMode': 'gentle',
      'allowedApps': ['md.obsidian', 'com.tencent.mm'],
      'status': 'pending',
      'isBreak': true,
    };
    final list = switch (level) {
      'low' => <Map<String, dynamic>>[
          {
            'id': 'blk-low-1',
            'slot': 'morning',
            'startMin': 570,
            'endMin': 600,
            'title': '上午最小启动块（保底破冰）',
            'deliverable': '写出今天唯一核心任务的文件开头或做 5 题',
            'lockMode': 'gentle',
            'allowedApps': ['md.obsidian', 'com.tencent.mm'],
            'status': 'pending',
            'isBreak': false,
          },
          lunch,
          {
            'id': 'blk-low-2',
            'slot': 'afternoon',
            'startMin': 900,
            'endMin': 935,
            'title': '下午稳态收尾块',
            'deliverable': '完成最小可验收进度并记录断点',
            'lockMode': 'gentle',
            'allowedApps': ['md.obsidian', 'com.tencent.mm'],
            'status': 'pending',
            'isBreak': false,
          },
        ],
      'high' => <Map<String, dynamic>>[
          {
            'id': 'blk-high-1',
            'slot': 'morning',
            'startMin': 525,
            'endMin': 585,
            'title': '晨间头号硬骨头（深水验收块）',
            'deliverable': '提交物理成果并通过高强度追问',
            'lockMode': 'verify',
            'allowedApps': ['md.obsidian'],
            'status': 'pending',
            'isBreak': false,
          },
          {
            'id': 'blk-high-2',
            'slot': 'morning',
            'startMin': 600,
            'endMin': 660,
            'title': '上午第二深水攻坚块',
            'deliverable': '完成整套训练或核心文稿段落',
            'lockMode': 'strict',
            'allowedApps': ['md.obsidian', 'com.tencent.mm'],
            'status': 'pending',
            'isBreak': false,
          },
          lunch,
          {
            'id': 'blk-high-3',
            'slot': 'afternoon',
            'startMin': 870,
            'endMin': 930,
            'title': '下午主线加速与输出',
            'deliverable': '提交物理交付物并核对验收点',
            'lockMode': 'verify',
            'allowedApps': ['md.obsidian', 'com.tencent.mm'],
            'status': 'pending',
            'isBreak': false,
          },
          {
            'id': 'blk-high-4',
            'slot': 'afternoon',
            'startMin': 960,
            'endMin': 1020,
            'title': '下午专项巩固与弱项清零',
            'deliverable': '解决 2 个卡点并归档笔记',
            'lockMode': 'strict',
            'allowedApps': ['md.obsidian'],
            'status': 'pending',
            'isBreak': false,
          },
          {
            'id': 'blk-high-5',
            'slot': 'evening',
            'startMin': 1170,
            'endMin': 1215,
            'title': '晚间知识卡压缩与明日封印',
            'deliverable': '压成题库卡并封好明日菜单',
            'lockMode': 'verify',
            'allowedApps': ['md.obsidian'],
            'status': 'pending',
            'isBreak': false,
          },
        ],
      _ => <Map<String, dynamic>>[
          {
            'id': 'blk-mid-1',
            'slot': 'morning',
            'startMin': 540,
            'endMin': 585,
            'title': '上午硬骨头攻坚（第一局）',
            'deliverable': '提交阶段性笔记/题量/代码产出',
            'lockMode': 'strict',
            'allowedApps': ['md.obsidian', 'com.tencent.mm'],
            'status': 'pending',
            'isBreak': false,
          },
          {
            'id': 'blk-mid-2',
            'slot': 'morning',
            'startMin': 600,
            'endMin': 645,
            'title': '上午主线推进（第二局）',
            'deliverable': '完成本块目标并记录断点',
            'lockMode': 'strict',
            'allowedApps': ['md.obsidian', 'com.tencent.mm'],
            'status': 'pending',
            'isBreak': false,
          },
          lunch,
          {
            'id': 'blk-mid-3',
            'slot': 'afternoon',
            'startMin': 870,
            'endMin': 915,
            'title': '下午专项深化块',
            'deliverable': '输出错因规则或岗位投递记录',
            'lockMode': 'strict',
            'allowedApps': ['md.obsidian', 'com.tencent.mm'],
            'status': 'pending',
            'isBreak': false,
          },
          {
            'id': 'blk-mid-4',
            'slot': 'evening',
            'startMin': 1170,
            'endMin': 1215,
            'title': '晚间复盘与验收收口',
            'deliverable': '压成 1 张因果卡或明日题库卡',
            'lockMode': 'verify',
            'allowedApps': ['md.obsidian'],
            'status': 'pending',
            'isBreak': false,
          },
        ],
    };
    return _sortBlocks(list);
  }

  int _funBudgetMin() {
    final locks = _today?['locksOverview'] is Map ? _today!['locksOverview'] as Map : const {};
    final wallet = locks['wallet'] is Map ? locks['wallet'] as Map : const {};
    if (wallet['availableMin'] is num) return (wallet['availableMin'] as num).round().clamp(0, 600);
    final tl = _today?['timeLedger'] is Map ? _today!['timeLedger'] as Map : const {};
    if (tl['funLeft'] is num) return (tl['funLeft'] as num).round().clamp(0, 600);
    return 30;
  }

  ({int focusMin, int doneMin, int lunchMin, int focusCount, int doneCount}) _dayStats() {
    var focusMin = 0;
    var doneMin = 0;
    var lunchMin = 0;
    var focusCount = 0;
    var doneCount = 0;
    for (final b in _dayBlocks) {
      final mins = math.max(0, _asInt(b['endMin'], 0) - _asInt(b['startMin'], 0));
      final isBreak = b['isBreak'] == true || (b['slot'] == 'lunch' && b['lockMode'] == 'gentle');
      if (isBreak) {
        if (b['status'] != 'skipped') lunchMin += mins;
        continue;
      }
      if (b['status'] == 'skipped') continue;
      focusCount += 1;
      focusMin += mins;
      if (b['status'] == 'done') {
        doneCount += 1;
        doneMin += mins;
      }
    }
    return (focusMin: focusMin, doneMin: doneMin, lunchMin: lunchMin, focusCount: focusCount, doneCount: doneCount);
  }

  Future<void> _selectEnergy(String level) async {
    setState(() {
      _energyLevel = level;
      _hasLocalBlockEdits = true;
    });
    unawaited(_channel.planContractOp({'action': 'set_energy', 'energyLevel': level}).catchError((_) => null));
    unawaited(_channel.tickNow().catchError((_) => false));
  }

  Future<void> _rebuildByEnergy() async {
    final built = _defaultBlocksForEnergy(_energyLevel);
    setState(() {
      _hasLocalBlockEdits = true;
      _dayBlocks = built;
      _banner = '✓ 已按「${_energyLabel(_energyLevel)}」重建今日时间盒（含 12:00–14:00 午休合法放风）';
    });
    unawaited(_channel.planContractOp({
      'action': 'generate_default',
      'energyLevel': _energyLevel,
      'dayBlocks': built,
    }).catchError((_) => null));
    unawaited(_channel.tickNow().catchError((_) => false));
  }

  Future<void> _setCustodyPolicy({bool? autoKickoffEnabled, bool? adaptiveSplitEnabled}) async {
    final cur = Map<String, dynamic>.from(_custodyPolicy ?? const {});
    final nextKick = autoKickoffEnabled ?? (cur['autoKickoffEnabled'] == true);
    final nextAdapt = adaptiveSplitEnabled ?? (cur['adaptiveSplitEnabled'] != false);
    setState(() {
      _custodyPolicy = {
        ...cur,
        'autoKickoffEnabled': nextKick,
        'adaptiveSplitEnabled': nextAdapt,
      };
      _banner = '✓ 已更新托管哨兵策略（09:15/14:45 哨兵 ${nextKick ? '开' : '关'} · 打回/逾期缩块 ${nextAdapt ? '开' : '关'}）';
    });
    final res = await _channel.planContractOp({
      'action': 'set_custody_policy',
      'autoKickoffEnabled': nextKick,
      'adaptiveSplitEnabled': nextAdapt,
    }).catchError((_) => null);
    if (mounted && res?['dayPlan'] is Map) {
      final dp = Map<String, dynamic>.from(res!['dayPlan'] as Map);
      if (dp['custodyPolicy'] is Map) {
        setState(() => _custodyPolicy = Map<String, dynamic>.from(dp['custodyPolicy'] as Map));
      }
    }
    unawaited(_channel.tickNow().catchError((_) => false));
  }

  Future<void> _openRebriefCustodyDialog() async {
    final reasonCtrl = TextEditingController();
    var downgradeLowEnergy = false;
    String? err;
    final out = await showDialog<(String, bool)>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          key: const ValueKey('plan-custody-rebrief-dialog'),
          shape: AppShape.dialog,
          title: const Text('受控改签（Re-brief）'),
          content: SizedBox(
            width: 380,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    '托管冲刺轨进行期间禁止自删或降级时间块。写清不少于 6 字的客观突发原因后，可重签为保底推进轨。',
                    style: TextStyle(fontSize: 12),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    key: const ValueKey('plan-custody-rebrief-reason'),
                    controller: reasonCtrl,
                    minLines: 2,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      labelText: '改签理由（至少 6 字）',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      const Expanded(child: Text('切为低电量 25m 保底轨')),
                      Switch(
                        value: downgradeLowEnergy,
                        onChanged: (v) => setLocal(() => downgradeLowEnergy = v),
                      ),
                    ],
                  ),
                  if (err != null) ...[
                    const SizedBox(height: 4),
                    Text(err!, style: TextStyle(color: DshColors.of(ctx).danger, fontSize: 12)),
                  ],
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            FilledButton(
              key: const ValueKey('plan-custody-rebrief-confirm'),
              onPressed: () {
                final r = reasonCtrl.text.trim();
                if (r.length < 6) {
                  setLocal(() => err = '请写清至少 6 个字的客观原因');
                  return;
                }
                Navigator.pop(ctx, (r, downgradeLowEnergy));
              },
              child: const Text('确认重签'),
            ),
          ],
        ),
      ),
    );
    disposeAfterRoute(reasonCtrl);
    if (out == null || !mounted) return;
    setState(() => _busy = true);
    final res = await _channel.planContractOp({
      'action': 'rebrief_custody',
      'reason': out.$1,
      'downgradeLowEnergy': out.$2,
    }).catchError((_) => null);
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (res?['ok'] == true) {
        _hasLocalBlockEdits = false;
        _banner = '✓ 已执行受控改签（Re-brief）并重签保底推进轨';
      } else {
        _banner = '${res?['text'] ?? res?['error'] ?? '受控改签失败'}';
      }
    });
    unawaited(_channel.tickNow().catchError((_) => false));
    await _refresh();
  }

  Widget _buildCustodyPolicyCard(ThemeData theme, DshColors colors) {
    final custody = _custody ?? const <String, dynamic>{};
    final custodyActive = custody['active'] == true;
    final autoKick = _custodyPolicy?['autoKickoffEnabled'] == true;
    final adaptiveOn = _custodyPolicy?['adaptiveSplitEnabled'] != false;
    final rebriefCount = _asInt(custody['rebriefCount'], 0);
    return GlassSurface(
      key: const ValueKey('plan-custody-policy-card'),
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    '路径 2 托管冲刺与自动哨兵',
                    style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
                StatusChip(
                  tone: custodyActive ? StatusTone.error : (autoKick ? StatusTone.ok : StatusTone.idle),
                  label: custodyActive
                      ? '托管中（至 ${custody['untilHm'] ?? '--:--'}）'
                      : (autoKick ? '哨兵值守' : '手动'),
                ),
              ],
            ),
            if (custodyActive) ...[
              const SizedBox(height: 10),
              Container(
                key: const ValueKey('plan-custody-active-banner'),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: colors.dangerContainer.withValues(alpha: 0.45),
                  borderRadius: AppShape.radiusMd,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      '托管冲刺轨护航中：「${custody['title'] ?? '冲刺轨'}」（至 ${custody['untilHm'] ?? '--:--'}${rebriefCount > 0 ? ' · 已改签 $rebriefCount 次' : ''}）',
                      style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '托管期间不可擅自删块、清空或降级为温和放行，仅可通过下方「受控改签」填写原因重排。',
                      style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                    ),
                    const SizedBox(height: 8),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: OutlinedButton.icon(
                        key: const ValueKey('plan-custody-rebrief-btn'),
                        onPressed: _busy ? null : () => unawaited(_openRebriefCustodyDialog()),
                        icon: const Icon(AppIcons.edit, size: 15),
                        label: const Text('受控改签 (Re-brief)'),
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('09:15 / 14:45 自动开局哨兵', style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
                      Text(
                        '当日尚无任何验收块时，自动钉入 25m 验收块并拉入昨日未完',
                        style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Switch(
                  key: const ValueKey('plan-auto-kickoff-switch'),
                  value: autoKick,
                  onChanged: _busy ? null : (v) => unawaited(_setCustodyPolicy(autoKickoffEnabled: v)),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('打回 / 逾期自动缩块 (2×25m)', style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
                      Text(
                        '验收被打回或 P3 逾期后，自动把下个大块拆成 2×25m 短块并收窄白名单${_adaptiveLog.isNotEmpty ? ' · 今日已触发 ${_adaptiveLog.length} 次' : ''}',
                        style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Switch(
                  key: const ValueKey('plan-adaptive-split-switch'),
                  value: adaptiveOn,
                  onChanged: _busy ? null : (v) => unawaited(_setCustodyPolicy(adaptiveSplitEnabled: v)),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _addCarryoverManual() {
    final title = _carryInputCtrl.text.trim();
    if (title.isEmpty) return;
    final id = 'carry-${DateTime.now().millisecondsSinceEpoch.toRadixString(36)}';
    setState(() {
      _hasLocalBlockEdits = true;
      _carryoverItems = [
        ..._carryoverItems,
        {
          'id': id,
          'title': title,
          'minutes': 45,
          'lockMode': 'strict',
          'deliverable': '补完昨日遗留交付',
          'allowedApps': ['md.obsidian', 'com.tencent.mm'],
          'moved': false,
        },
      ];
      _carryInputCtrl.clear();
    });
  }

  Future<void> _moveCarryoverIn(Map<String, dynamic> item) async {
    final id = '${item['id']}';
    final dur = _asInt(item['minutes'], 45).clamp(10, 180);
    final nonBreakMorning = _dayBlocks.where((b) => b['slot'] == 'morning' && b['isBreak'] != true).toList();
    var startMin = nonBreakMorning.isEmpty
        ? 540
        : (nonBreakMorning.map((b) => _asInt(b['endMin'], 585)).reduce(math.max) + 15);
    if (startMin >= 720 && startMin < 840) startMin = 870;
    final newBlock = <String, dynamic>{
      'id': 'blk-carry-${DateTime.now().millisecondsSinceEpoch.toRadixString(36)}',
      'slot': _slotOf(startMin),
      'startMin': startMin,
      'endMin': math.min(1440, startMin + dur),
      'title': '${item['title']}',
      'deliverable': '${item['deliverable'] ?? '补完昨日遗留并给出可验产出'}',
      'lockMode': '${item['lockMode'] ?? 'strict'}',
      'allowedApps': item['allowedApps'] is List ? List<String>.from(item['allowedApps'] as List) : ['md.obsidian', 'com.tencent.mm'],
      'status': 'pending',
      'isBreak': false,
    };
    setState(() {
      _hasLocalBlockEdits = true;
      _carryoverItems = [
        for (final c in _carryoverItems)
          if ('${c['id']}' == id) {...c, 'moved': true} else c,
      ];
      _dayBlocks = _sortBlocks([..._dayBlocks, newBlock]);
      _banner = '✓ 已将「${item['title']}」移入 ${_hm(startMin)}–${_hm(_asInt(newBlock['endMin'], startMin + dur))}';
    });
    unawaited(_channel.planContractOp({'action': 'move_carryover', 'carryoverId': id}).catchError((_) => null));
    unawaited(_channel.tickNow().catchError((_) => false));
  }

  String _buildHardTasksGeminiPrompt() {
    final stats = _dayStats();
    final unmoved = _carryoverItems.where((c) => c['moved'] != true).map((c) => '${c['title']}').toList();
    final horizons = _today?['horizons'] is Map ? _today!['horizons'] as Map : const {};
    final med = _mapList(horizons['medium']).map((m) => '${m['title'] ?? ''}').where((s) => s.isNotEmpty).take(3).toList();
    return [
      '【请直接帮我挑今天 1–3 件硬骨头，不要超过 3 件】',
      '- 今日精力档位：${_energyLabel(_energyLevel)}（$_energyLevel）',
      '- 今日娱乐余额预算：${_funBudgetMin()}m；当前已排专注：${stats.focusMin}m（${stats.focusCount} 块）',
      if (unmoved.isNotEmpty) '- 昨日未完结待选（${unmoved.length} 项）：${unmoved.join('；')}',
      if (med.isNotEmpty) '- 中期主线战役：${med.join('；')}',
      '要求：1) 从上述上下文挑 1–3 件最值钱的硬骨头；2) 每件写清放进哪个时段、持续多少分钟、用哪种锁（温和/严格/验收锁）、允许哪些 App、交什么物理交付物；3) 若精力是低电量保底，只排最小可启动一步。',
    ].join('\n');
  }

  Future<void> _askGeminiPickHardTasks() async {
    final prompt = _buildHardTasksGeminiPrompt();
    await Clipboard.setData(ClipboardData(text: prompt));
    final ok = await _channel.launchGeminiProactive(
      topic: '挑今天 1–3 件硬骨头',
      brief: prompt,
      mode: 'countdown_3s',
      preferVoice: true,
    );
    if (!mounted) return;
    setState(() {
      _banner = ok
          ? '⚡ 已唤起 Gemini 帮你挑今天 1–3 件硬骨头（上下文已装入剪贴板）'
          : '✓ 已打包今日精力与未完结上下文并复制，可直接粘贴给 Gemini 挑 1–3 件硬骨头';
    });
  }

  Future<void> _openBlockEditor([Map<String, dynamic>? existing]) async {
    final isNew = existing == null;
    final titleCtrl = TextEditingController(text: '${existing?['title'] ?? ''}');
    final delivCtrl = TextEditingController(text: '${existing?['deliverable'] ?? '提交可见物理产物或验收'}');
    var startMin = _asInt(existing?['startMin'], 9 * 60);
    var durationMin = existing != null
        ? math.max(10, _asInt(existing['endMin'], startMin + 45) - startMin)
        : 45;
    var lockMode = '${existing?['lockMode'] ?? (existing?['isBreak'] == true ? 'gentle' : 'strict')}';
    final allow = <String>{
      if (existing?['allowedApps'] is List)
        for (final x in existing!['allowedApps'] as List) '$x'
      else ...['md.obsidian', 'com.tencent.mm'],
    };
    final isBreak = existing?['isBreak'] == true;
    final isBossLocked = existing?['lockedByBoss'] == true || existing?['source'] == 'wall_boss';
    final isDone = existing?['status'] == 'done';
    if (isBossLocked && !isDone) lockMode = 'verify';

    final action = await showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          title: Text(isNew ? '新增时间块' : '编辑时间块'),
          content: SizedBox(
            width: 380,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (isBossLocked) ...[
                    Container(
                      padding: const EdgeInsets.all(10),
                      margin: const EdgeInsets.only(bottom: 10),
                      decoration: BoxDecoration(
                        color: DshColors.of(ctx).dangerContainer,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        '主管止损硬指令：不可删除、不可跳过，须交付过审解除验收锁',
                        style: Theme.of(ctx).textTheme.bodySmall?.copyWith(
                              color: DshColors.of(ctx).onDangerContainer,
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                    ),
                  ],
                  TextField(
                    key: const ValueKey('block-editor-title'),
                    controller: titleCtrl,
                    decoration: const InputDecoration(labelText: '时间块标题', border: OutlineInputBorder(), isDense: true),
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: () async {
                            final picked = await showTimePicker(
                              context: ctx,
                              initialTime: TimeOfDay(hour: startMin ~/ 60, minute: startMin % 60),
                            );
                            if (picked != null) {
                              setDialog(() => startMin = picked.hour * 60 + picked.minute);
                            }
                          },
                          icon: const Icon(AppIcons.time, size: 16),
                          label: Text('开始 ${_hm(startMin)}'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text('→ 结束 ${_hm(math.min(1440, startMin + durationMin))}'),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text('块时长（锁时长跟块走）：${durationMin}m', style: Theme.of(ctx).textTheme.labelMedium),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      for (final d in const [20, 30, 45, 60, 90, 120])
                        ChoiceChip(
                          label: Text('${d}m'),
                          selected: durationMin == d,
                          onSelected: (_) => setDialog(() => durationMin = d),
                        ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text('锁强度', style: Theme.of(ctx).textTheme.labelMedium),
                  const SizedBox(height: 6),
                  SegmentedPill<String>(
                    segments: const [
                      PillSegment('gentle', '温和'),
                      PillSegment('strict', '严格'),
                      PillSegment('verify', '验收锁'),
                    ],
                    selected: const ['gentle', 'strict', 'verify'].contains(lockMode) ? lockMode : 'strict',
                    onChanged: (v) {
                      if (isBossLocked && !isDone && v != 'verify') return;
                      setDialog(() => lockMode = v);
                    },
                  ),
                  if (lockMode == 'verify') ...[
                    const SizedBox(height: 6),
                    Text(
                      '验收锁规则：到开始时间自动 P4 接管；结束前未过审且超 60 秒宽限自动升级 P3 验收严锁（须交付过审解锁，不可花余额跳过）。',
                      style: Theme.of(ctx).textTheme.labelSmall?.copyWith(color: DshColors.of(ctx).textTertiary),
                    ),
                  ],
                  const SizedBox(height: 10),
                  TextField(
                    key: const ValueKey('block-editor-deliverable'),
                    controller: delivCtrl,
                    decoration: const InputDecoration(labelText: '到点交付物', border: OutlineInputBorder(), isDense: true),
                  ),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    onPressed: () async {
                      final picked = await showInstalledAppsPicker(
                        ctx,
                        title: '选择本时间块允许的 App',
                        loop: _loop,
                        initialSelected: allow.toList(),
                        allowEmpty: true,
                      );
                      if (picked != null) {
                        setDialog(() {
                          allow
                            ..clear()
                            ..addAll(picked.map((a) => a.package));
                        });
                      }
                    },
                    icon: const Icon(AppIcons.files, size: 16),
                    label: Text('允许 App（${allow.length} 个）：${allow.map(_appName).join('、')}'),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            if (!isNew && !isBossLocked)
              TextButton(
                onPressed: () => Navigator.pop(ctx, 'delete'),
                child: Text('删除', style: TextStyle(color: DshColors.of(ctx).danger)),
              ),
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            FilledButton(
              key: const ValueKey('block-editor-save'),
              onPressed: () {
                if (titleCtrl.text.trim().isEmpty) return;
                Navigator.pop(ctx, 'save');
              },
              child: const Text('保存时间块'),
            ),
          ],
        ),
      ),
    );

    final title = titleCtrl.text.trim();
    final deliverable = delivCtrl.text.trim();
    disposeAfterRoute(titleCtrl);
    disposeAfterRoute(delivCtrl);
    if (!mounted || action == null) return;

    if (action == 'delete' && existing != null) {
      final delId = '${existing['id']}';
      setState(() {
        _hasLocalBlockEdits = true;
        _dayBlocks = [for (final b in _dayBlocks) if ('${b['id']}' != delId) b];
        _banner = '✓ 已删除时间块「${existing['title']}」';
      });
      unawaited(_channel.planContractOp({'action': 'delete_block', 'blockId': delId}).catchError((_) => null));
      unawaited(_channel.tickNow().catchError((_) => false));
      return;
    }

    if (action == 'save' && title.isNotEmpty) {
      final id = '${existing?['id'] ?? 'blk-${DateTime.now().millisecondsSinceEpoch.toRadixString(36)}'}';
      final slot = _slotOf(startMin);
      final updated = <String, dynamic>{
        'id': id,
        'slot': slot,
        'startMin': startMin,
        'endMin': math.min(1440, startMin + durationMin),
        'title': title,
        'deliverable': deliverable.isEmpty ? '提交可见产出' : deliverable,
        'lockMode': lockMode,
        'allowedApps': allow.isEmpty ? ['md.obsidian'] : allow.toList(),
        'status': '${existing?['status'] ?? 'pending'}',
        'isBreak': isBreak || (slot == 'lunch' && lockMode == 'gentle' && RegExp(r'午休|放风|休息').hasMatch(title)),
        if (isBossLocked) 'lockedByBoss': true,
        if ('${existing?['source'] ?? ''}'.isNotEmpty) 'source': existing!['source'],
        if ('${existing?['verifyStatus'] ?? ''}'.isNotEmpty) 'verifyStatus': existing!['verifyStatus'],
        if ('${existing?['sealId'] ?? ''}'.isNotEmpty) 'sealId': existing!['sealId'],
        if ('${existing?['submissionText'] ?? ''}'.isNotEmpty) 'submissionText': existing!['submissionText'],
      };
      setState(() {
        _hasLocalBlockEdits = true;
        final list = [for (final b in _dayBlocks) if ('${b['id']}' != id) b, updated];
        _dayBlocks = _sortBlocks(list);
        _banner = '✓ 已保存时间块「$title」（${_hm(startMin)}–${_hm(_asInt(updated['endMin'], startMin + durationMin))} · ${_lockModeLabel(lockMode)}）';
      });
      unawaited(_channel.planContractOp({'action': 'upsert_block', 'block': updated}).catchError((_) => null));
      unawaited(_channel.tickNow().catchError((_) => false));
    }
  }

  Future<void> _startBlockNow(Map<String, dynamic> block) async {
    final id = '${block['id']}';
    final title = '${block['title']}';
    final dur = math.max(10, _asInt(block['endMin'], 0) - _asInt(block['startMin'], 0));
    final strict = '${block['lockMode']}' != 'gentle';
    final allow = block['allowedApps'] is List
        ? [for (final a in block['allowedApps'] as List) '$a']
        : const ['md.obsidian', 'com.tencent.mm'];

    setState(() {
      _hasLocalBlockEdits = true;
      _dayBlocks = [
        for (final b in _dayBlocks)
          if ('${b['id']}' == id)
            {...b, 'status': 'running'}
          else if (b['status'] == 'running')
            {...b, 'status': 'pending'}
          else
            b,
      ];
      _banner = '⚡ 已开干「$title」（${dur}m · ${_lockModeLabel('${block['lockMode']}')}）';
    });

    unawaited(_channel.planContractOp({'action': 'set_block_status', 'blockId': id, 'status': 'running'}).catchError((_) => null));
    final res = await _channel.todayStart(goal: title, minutes: dur, strict: strict, allow: allow);
    unawaited(_channel.tickNow().catchError((_) => false));
    if (!mounted) return;
    if (res?['ok'] == true) {
      setState(() => _banner = '⚡ 已锁定并开干「$title」（${dur}m），保持专注！');
    }
  }

  Future<void> _askGeminiFirstStep(Map<String, dynamic> block) async {
    final title = '${block['title']}';
    final deliv = '${block['deliverable'] ?? ''}';
    final prompt = [
      '【帮我拆这个时间块的第一步动作（2 分钟内能上手）】',
      '- 当前时间块：$title（${_hm(_asInt(block['startMin'], 540))}–${_hm(_asInt(block['endMin'], 585))}）',
      if (deliv.isNotEmpty) '- 到点交付物：$deliv',
      '- 锁强度：${_lockModeLabel('${block['lockMode']}')}',
      '别讲鼓励空话，直接给我一个 2 分钟内就能动手的具体动作（包含动词和打开哪个文件/工具）。',
    ].join('\n');
    await Clipboard.setData(ClipboardData(text: prompt));
    final ok = await _channel.launchGeminiProactive(
      topic: '拆解第一步：$title',
      brief: prompt,
      mode: 'countdown_3s',
      preferVoice: true,
    );
    if (!mounted) return;
    setState(() {
      _banner = ok ? '⚡ 已唤起 Gemini 拆解「$title」第一步' : '✓ 已复制「$title」第一步拆解请求，切去 Gemini 粘贴即可';
    });
  }

  Future<void> _verifyBlockDone(Map<String, dynamic> block) async {
    final id = '${block['id']}';
    final title = '${block['title']}';
    final needsHardVerify = block['lockMode'] == 'verify' || block['lockedByBoss'] == true || block['source'] == 'wall_boss';

    if (needsHardVerify) {
      final sumCtrl = TextEditingController(text: '${block['submissionText'] ?? ''}');
      var notifyBoss = true;
      String? errorText;
      final result = await showDialog<(String, bool)>(
        context: context,
        builder: (ctx) => StatefulBuilder(
          builder: (ctx, setDialog) => AlertDialog(
            title: Text('提交验收交付：$title'),
            content: SizedBox(
              width: 380,
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text('约定交付：${block['deliverable'] ?? '可见产出'}', style: Theme.of(ctx).textTheme.bodySmall),
                    const SizedBox(height: 10),
                    TextField(
                      key: const ValueKey('verify-delivery-input'),
                      controller: sumCtrl,
                      minLines: 3,
                      maxLines: 6,
                      decoration: const InputDecoration(
                        labelText: '真实物理产出 / 题量 / 文件增量（至少 12 字）',
                        border: OutlineInputBorder(),
                      ),
                    ),
                    if (errorText != null) ...[
                      const SizedBox(height: 6),
                      Text(errorText!, style: TextStyle(color: DshColors.of(ctx).danger, fontSize: 12)),
                    ],
                    const SizedBox(height: 6),
                    CheckboxListTile(
                      value: notifyBoss,
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      title: const Text('提交后通知主管冷血终审'),
                      onChanged: (v) => setDialog(() => notifyBoss = v ?? true),
                    ),
                  ],
                ),
              ),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
              FilledButton(
                onPressed: () {
                  final text = sumCtrl.text.trim();
                  if (text.length < 12) {
                    setDialog(() => errorText = '验收块交付至少 12 个字（当前 ${text.length} 字），严禁空口过关');
                    return;
                  }
                  Navigator.pop(ctx, (text, notifyBoss));
                },
                child: const Text('提交交付送审'),
              ),
            ],
          ),
        ),
      );
      disposeAfterRoute(sumCtrl);
      if (!mounted || result == null) return;

      final submitted = result.$1;
      final sendBoss = result.$2;
      setState(() {
        _hasLocalBlockEdits = true;
        _dayBlocks = [
          for (final b in _dayBlocks)
            if ('${b['id']}' == id)
              {...b, 'verifyStatus': 'submitted', 'submissionText': submitted}
            else
              b,
        ];
        _banner = '✓ 已提交「$title」交付，等待主管/Gemini 终审解锁';
      });
      await _channel.planContractOp({
        'action': 'submit_delivery',
        'blockId': id,
        'summary': submitted,
      });
      if (sendBoss) {
        unawaited(_channel.wallBossChat({'action': 'request_judge', 'blockId': id}).catchError((_) => null));
      }
      unawaited(_channel.tickNow().catchError((_) => false));
      await _refresh(silent: true);
      return;
    }

    final sumCtrl = TextEditingController();
    final submitted = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('验收过关：$title'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('约定交付：${block['deliverable'] ?? '可见产出'}', style: Theme.of(ctx).textTheme.bodySmall),
              const SizedBox(height: 10),
              TextField(
                controller: sumCtrl,
                minLines: 3,
                maxLines: 6,
                decoration: const InputDecoration(
                  labelText: '写下物理交付结果 / 核心因果结论',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, sumCtrl.text.trim()),
            child: const Text('确认验收过关'),
          ),
        ],
      ),
    );
    disposeAfterRoute(sumCtrl);
    if (!mounted || submitted == null) return;

    setState(() {
      _hasLocalBlockEdits = true;
      _dayBlocks = [
        for (final b in _dayBlocks)
          if ('${b['id']}' == id) {...b, 'status': 'done'} else b,
      ];
      _banner = '✓ 时间块「$title」已验收过关！';
    });
    unawaited(_channel.planContractOp({'action': 'set_block_status', 'blockId': id, 'status': 'done'}).catchError((_) => null));
    unawaited(_channel.tickNow().catchError((_) => false));
    final cpId = '${block['checkpointId'] ?? ''}'.trim();
    if (cpId.isNotEmpty) {
      await _channel.planContractOp({
        'action': 'verify',
        'checkpointId': cpId,
        'summary': submitted.isEmpty ? '已完成时间块「$title」交付' : submitted,
        'aiConfirmed': true,
      });
      await _refresh(silent: true);
    }
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
      'energyLevel': _energyLevel,
      'dayBlocks': _dayBlocks,
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
      if (res?['ok'] == true) _tab = _PlanTab.watch;
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

  String _appName(String pkg) {
    for (final a in _installedApps) {
      if (a.package == pkg) return a.label;
    }
    return switch (pkg) {
      'md.obsidian' => 'Obsidian',
      'com.tencent.mm' => '微信',
      'tv.danmaku.bili' => '哔哩哔哩',
      'cn.wps.moffice_eng' => 'WPS',
      _ => pkg,
    };
  }

  static String _energyLabel(String level) => switch (level) {
        'low' => '低电量保底',
        'high' => '满血冲刺',
        _ => '标准推进',
      };

  static String _lockModeLabel(String mode) => switch (mode) {
        'gentle' => '温和',
        'verify' => '验收锁',
        _ => '严格',
      };

  static StatusTone _lockTone(String mode) => switch (mode) {
        'gentle' => StatusTone.ok,
        'verify' => StatusTone.error,
        _ => StatusTone.warn,
      };

  static (StatusTone, String) _blockStatusChip(String status) => switch (status) {
        'running' => (StatusTone.busy, '进行中'),
        'done' => (StatusTone.ok, '已过关'),
        'skipped' => (StatusTone.idle, '已跳过'),
        _ => (StatusTone.idle, '待开干'),
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);

    return AppPage.list(
      title: '计划与契约台',
      subtitle: const Text('1. 准备（精力/结转）→ 2. 构建（时间盒）→ 3. 观看（24h 红线验收）'),
      actions: [
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新契约状态',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        SegmentedPill<_PlanTab>(
          key: const ValueKey('plan-stage-tabs'),
          segments: const [
            PillSegment(_PlanTab.prepare, '1. 准备'),
            PillSegment(_PlanTab.build, '2. 构建'),
            PillSegment(_PlanTab.watch, '3. 观看'),
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
        if (_tab == _PlanTab.prepare)
          ..._buildPrepareStage(theme, colors)
        else if (_tab == _PlanTab.build)
          ..._buildBuildStage(theme, colors)
        else
          ..._buildWatchStage(theme, colors),
      ],
    );
  }

  // --------------------------------------------------------------------------
  // 1. 准备态：精力三选一 + 拉 Gemini 挑硬骨头 + 昨日未完结一键移入 + 红旗体检
  // --------------------------------------------------------------------------
  List<Widget> _buildPrepareStage(ThemeData theme, DshColors colors) {
    final unmoved = _carryoverItems.where((c) => c['moved'] != true).toList();
    return [
      GlassSurface(
        key: const ValueKey('plan-energy-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(child: Text('今日精力档位', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700))),
                  StatusChip(tone: StatusTone.busy, label: _energyLabel(_energyLevel)),
                ],
              ),
              const SizedBox(height: 8),
              SegmentedPill<String>(
                key: const ValueKey('plan-energy-pill'),
                segments: const [
                  PillSegment('low', '低电量保底'),
                  PillSegment('mid', '标准'),
                  PillSegment('high', '冲刺'),
                ],
                selected: _energyLevel,
                onChanged: (v) => unawaited(_selectEnergy(v)),
              ),
              const SizedBox(height: 8),
              Text(
                switch (_energyLevel) {
                  'low' => '建议 2 个温和保底短块（30m），保留 12:00–14:00 午休放风，先求不断档。',
                  'high' => '建议 5 个 45–60m 深水块，启用严格/验收锁闭环交付。',
                  _ => '建议 4 个 45m 标准专注块 + 12:00–14:00 午休合法放风，稳步推进主线。',
                },
                style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
              ),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                key: const ValueKey('plan-apply-energy-template'),
                onPressed: () async {
                  await _rebuildByEnergy();
                  if (mounted) setState(() => _tab = _PlanTab.build);
                },
                icon: const Icon(AppIcons.planMode, size: 16),
                label: const Text('按当前精力生成时间盒并去构建'),
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 14),
      GlassSurface(
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const OverviewGlyph(AppIcons.askAi, tint: 0, size: 42),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('拉 Gemini 挑今天 1–3 件硬骨头', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 2),
                        Text(
                          '自动打包精力档位、昨日遗留与娱乐预算（${_funBudgetMin()}m）',
                          style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              FilledButton.icon(
                key: const ValueKey('plan-gemini-hard-tasks-btn'),
                onPressed: () => unawaited(_askGeminiPickHardTasks()),
                icon: const Icon(AppIcons.askAi, size: 16),
                label: const Text('拉 Gemini 挑今天 1–3 件硬骨头'),
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 14),
      GlassSurface(
        key: const ValueKey('plan-carryover-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(child: Text('昨日未完结 · 一键移入今日', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700))),
                  StatusChip(tone: unmoved.isEmpty ? StatusTone.ok : StatusTone.warn, label: '待移入 ${unmoved.length}'),
                ],
              ),
              const SizedBox(height: 8),
              if (_carryoverItems.isEmpty)
                Text('暂无昨日遗留项；也可在下方随手记一条待移入任务。', style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary))
              else
                for (final item in _carryoverItems) ...[
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '${item['title']}',
                                style: theme.textTheme.bodyMedium?.copyWith(
                                  decoration: item['moved'] == true ? TextDecoration.lineThrough : null,
                                  color: item['moved'] == true ? colors.textTertiary : null,
                                ),
                              ),
                              Text(
                                '${_asInt(item['minutes'], 45)}m · ${_lockModeLabel('${item['lockMode'] ?? 'strict'}')}',
                                style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                              ),
                            ],
                          ),
                        ),
                        if (item['moved'] == true)
                          const StatusChip(tone: StatusTone.ok, label: '已移入')
                        else
                          FilledButton.tonal(
                            key: ValueKey('carryover-move-${item['id']}'),
                            onPressed: () => unawaited(_moveCarryoverIn(item)),
                            child: const Text('一键移入'),
                          ),
                      ],
                    ),
                  ),
                ],
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _carryInputCtrl,
                      decoration: const InputDecoration(
                        hintText: '补一条昨日未完成硬任务…',
                        border: OutlineInputBorder(),
                        isDense: true,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(
                    onPressed: _addCarryoverManual,
                    child: const Text('加入待选'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 14),
      _buildCustodyPolicyCard(theme, colors),
      const SizedBox(height: 14),
      GlassSurface(
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('防骗与主线体检（四部认知 Skill 安检）', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
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
      const SizedBox(height: 24),
    ];
  }

  // --------------------------------------------------------------------------
  // 2. 构建态：专注预估 vs 娱乐预算条 + 4 时段可视化块 + 契约预设 + 全天排局表
  // --------------------------------------------------------------------------
  List<Widget> _buildBuildStage(ThemeData theme, DshColors colors) {
    final stats = _dayStats();
    final funBudget = _funBudgetMin();
    final ratioBase = math.max(1, stats.focusMin + funBudget);
    final slots = const [
      ('morning', '上午（06:00–12:00）'),
      ('lunch', '午休（12:00–14:00 · 合法放风标注）'),
      ('afternoon', '下午（14:00–18:00）'),
      ('evening', '晚间（18:00–24:00）'),
    ];

    return [
      GlassSurface(
        key: const ValueKey('plan-focus-vs-fun-bar'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '总专注预估 ${stats.focusMin}m vs 娱乐余额预算 ${funBudget}m',
                      style: AppType.tabular(theme.textTheme.titleSmall!.copyWith(fontWeight: FontWeight.w700)),
                    ),
                  ),
                  StatusChip(tone: StatusTone.ok, label: '午休放风 ${stats.lunchMin}m'),
                ],
              ),
              const SizedBox(height: 8),
              ClipRRect(
                borderRadius: BorderRadius.circular(999),
                child: SizedBox(
                  height: 10,
                  child: Row(
                    children: [
                      if (stats.focusMin > 0)
                        Expanded(flex: stats.focusMin, child: ColoredBox(color: colors.success)),
                      if (funBudget > 0)
                        Expanded(flex: funBudget, child: ColoredBox(color: colors.warning)),
                      if (stats.focusMin + funBudget == 0)
                        Expanded(child: ColoredBox(color: theme.colorScheme.outlineVariant.withValues(alpha: 0.4))),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '共 ${stats.focusCount} 个专注块（已完成 ${stats.doneCount} 块 / ${stats.doneMin}m） · 精力：${_energyLabel(_energyLevel)} · 总比例 ${(stats.focusMin / ratioBase * 100).round()}% 正事',
                style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const ValueKey('plan-rebuild-blocks'),
                      onPressed: () => unawaited(_rebuildByEnergy()),
                      icon: const Icon(AppIcons.refresh, size: 15),
                      label: const Text('按精力生成推荐块'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton.tonalIcon(
                      key: const ValueKey('plan-add-block-btn'),
                      onPressed: () => unawaited(_openBlockEditor()),
                      icon: const Icon(AppIcons.add, size: 15),
                      label: const Text('新增时间块'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 14),
      for (final (slotKey, slotTitle) in slots) ...[
        Padding(
          padding: const EdgeInsets.only(left: 4, bottom: 6),
          child: Text(
            slotTitle,
            style: theme.textTheme.labelLarge?.copyWith(
              fontWeight: FontWeight.w700,
              color: slotKey == 'lunch' ? colors.success : theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        ...() {
          final inSlot = _dayBlocks.where((b) => '${b['slot']}' == slotKey).toList();
          if (inSlot.isEmpty) {
            return [
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: GlassSurface(
                  tier: GlassTier.card,
                  borderRadius: AppShape.radiusMd,
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(
                      slotKey == 'lunch' ? '12:00–14:00 午休放风时段' : '此时段暂无块，可点上方「新增时间块」添加',
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    ),
                  ),
                ),
              ),
            ];
          }
          return [
            for (final blk in inSlot)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: GlassSurface(
                  key: ValueKey('plan-block-${blk['id']}'),
                  tier: GlassTier.card,
                  borderRadius: AppShape.radiusMd,
                  onTap: () => unawaited(_openBlockEditor(blk)),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            Text(
                              '${_hm(_asInt(blk['startMin'], 540))}–${_hm(_asInt(blk['endMin'], 585))}',
                              style: AppType.tabular(theme.textTheme.labelMedium!.copyWith(
                                fontWeight: FontWeight.w700,
                                color: theme.colorScheme.primary,
                              )),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                '${blk['title']}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                              ),
                            ),
                            if (blk['isBreak'] == true)
                              const StatusChip(tone: StatusTone.ok, label: '合法放风')
                            else
                              StatusChip(
                                tone: _lockTone('${blk['lockMode']}'),
                                label: _lockModeLabel('${blk['lockMode']}'),
                              ),
                          ],
                        ),
                        if (blk['lockedByBoss'] == true ||
                            const ['overdue_locked', 'submitted', 'rejected'].contains('${blk['verifyStatus'] ?? ''}')) ...[
                          const SizedBox(height: 4),
                          Wrap(
                            spacing: 6,
                            runSpacing: 4,
                            children: [
                              if (blk['lockedByBoss'] == true)
                                const StatusChip(tone: StatusTone.error, label: '主管硬指令'),
                              if (blk['verifyStatus'] == 'overdue_locked')
                                const StatusChip(tone: StatusTone.error, label: '逾期严锁'),
                              if (blk['verifyStatus'] == 'submitted')
                                const StatusChip(tone: StatusTone.warn, label: '待终审'),
                              if (blk['verifyStatus'] == 'rejected')
                                const StatusChip(tone: StatusTone.error, label: '已驳回'),
                            ],
                          ),
                        ],
                        const SizedBox(height: 6),
                        Text(
                          '交付：${blk['deliverable'] ?? '可见产出'} · 放行：${(blk['allowedApps'] is List ? (blk['allowedApps'] as List).map((e) => _appName('$e')).join('、') : '默认')}',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ];
        }(),
      ],
      const SizedBox(height: 6),
      GlassSurface(
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('契约立约与常用预设（到点缓冲 1 分钟未验收自动严格锁）', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
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
      const SizedBox(height: 16),
      Padding(
        padding: const EdgeInsets.only(left: 4, bottom: 8),
        child: Text('全天排局表（工作包明细）', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
      ),
      PlanPage(channel: _channel, loop: _loop, asTab: true),
      const SizedBox(height: 24),
    ];
  }

  // --------------------------------------------------------------------------
  // 3. 观看与执行态：24h 垂直时间轴 + 红线指示器 + 倒计时/偏航 + 一键开干/拆步/验收
  // --------------------------------------------------------------------------
  List<Widget> _buildWatchStage(ThemeData theme, DshColors colors) {
    final nowDt = DateTime.now();
    final nowMin = nowDt.hour * 60 + nowDt.minute;
    final contract = _contract;
    final cps = contract != null ? _mapList(contract['checkpoints']) : const <Map<String, dynamic>>[];

    final runningBlock = _dayBlocks.where((b) => b['status'] == 'running').firstOrNull;
    final currentSlotBlock = _dayBlocks
        .where((b) => b['status'] != 'skipped' && b['status'] != 'done' && nowMin >= _asInt(b['startMin'], 0) && nowMin < _asInt(b['endMin'], 0))
        .firstOrNull;
    final nextPendingBlock = _dayBlocks
        .where((b) => b['isBreak'] != true && b['status'] == 'pending')
        .firstOrNull;
    final focusBlock = runningBlock ?? currentSlotBlock ?? nextPendingBlock;

    final int remainMin;
    final bool isOffTrack;
    final String watchAlert;
    if (focusBlock != null) {
      final start = _asInt(focusBlock['startMin'], nowMin);
      final end = _asInt(focusBlock['endMin'], start + 45);
      remainMin = nowMin < end ? (end - nowMin) : 0;
      final latePending = focusBlock['isBreak'] != true && focusBlock['status'] == 'pending' && nowMin >= start + 5;
      isOffTrack = latePending || _graceWarning != null || contract?['strictEscalated'] == true;
      watchAlert = _graceWarning != null
          ? '⚠️ 契约即将转严格锁：还剩 ${_graceWarning!['remainSec']} 秒'
          : (latePending ? '偏航提醒：「${focusBlock['title']}」已过预定开干时间 ${nowMin - start} 分钟' : '');
    } else {
      remainMin = 0;
      isOffTrack = false;
      watchAlert = '';
    }

    const timelineHeight = 624.0; // 26dp / 小时 * 24 小时
    final redLineTop = ((nowMin / 1440.0) * timelineHeight).clamp(0.0, timelineHeight - 2);

    return [
      () {
        final ov = _locksOverview;
        final winnerId = '${ov?['winnerId'] ?? ''}'.trim();
        final hands = _mapList(ov?['hands']);
        final winner = winnerId.isEmpty
            ? null
            : hands.cast<Map<String, dynamic>?>().firstWhere(
                  (h) => h != null && ('${h['id'] ?? ''}' == winnerId || h['isWinner'] == true),
                  orElse: () => null,
                );
        final vCenter = ov?['verifyLockCenter'] is Map ? Map<String, dynamic>.from(ov!['verifyLockCenter'] as Map) : _verifySummary;
        final overdueCnt = _asInt(vCenter?['overdueCount'], 0);
        final pendingCnt = _asInt(vCenter?['pendingReviewCount'], 0);
        final passedCnt = _asInt(vCenter?['passedVerify'], 0);
        final totalCnt = _asInt(vCenter?['totalVerify'], 0);
        final custodyMap = vCenter?['custody'] is Map ? Map<String, dynamic>.from(vCenter!['custody'] as Map) : _custody;
        final custodyActive = custodyMap?['active'] == true;
        final adaptiveCnt = _asInt(vCenter?['adaptiveSplitsToday'], _adaptiveLog.length);
        final headline = winner != null
            ? '此刻锁源：${winner['priority'] ?? ''} · ${winner['name'] ?? winnerId}'
            : '此刻锁源：${ov == null ? '同步锁机探针中…' : '自由推进中（P6）'}';
        final subLine = [
          if (winner != null && '${winner['duty'] ?? ''}'.trim().isNotEmpty) '${winner['duty']}',
          if (totalCnt > 0 || overdueCnt > 0 || pendingCnt > 0)
            '验收块 $passedCnt/$totalCnt 通关 · 逾期严锁 $overdueCnt · 待终审 $pendingCnt',
          if (custodyActive) '托管冲刺：「${custodyMap?['title'] ?? '冲刺轨'}」（至 ${custodyMap?['untilHm'] ?? '--:--'}）',
          if (adaptiveCnt > 0) '自动缩块 ×$adaptiveCnt',
        ].join(' · ');
        return Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: GlassSurface(
            key: const ValueKey('watch-lock-attribution-bar'),
            tier: GlassTier.card,
            borderRadius: AppShape.radiusMd,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              child: Row(
                children: [
                  Icon(
                    overdueCnt > 0 ? AppIcons.lockActive : AppIcons.lock,
                    size: 18,
                    color: overdueCnt > 0 ? colors.danger : (winner != null ? colors.warning : colors.success),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          headline,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w700),
                        ),
                        if (subLine.isNotEmpty)
                          Text(
                            subLine,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      }(),
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
      if (focusBlock != null)
        GlassSurface(
          key: const ValueKey('watch-active-block-card'),
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(
                      focusBlock['status'] == 'running' ? AppIcons.running : AppIcons.time,
                      color: isOffTrack ? colors.warning : theme.colorScheme.primary,
                      size: 20,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '${focusBlock['title']}',
                        style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    StatusChip(
                      tone: _blockStatusChip('${focusBlock['status']}').$1,
                      label: _blockStatusChip('${focusBlock['status']}').$2,
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  '${_hm(_asInt(focusBlock['startMin'], 540))}–${_hm(_asInt(focusBlock['endMin'], 585))} · 距本块结束还剩 ${remainMin}m · ${_lockModeLabel('${focusBlock['lockMode']}')}',
                  style: AppType.tabular(theme.textTheme.bodySmall!.copyWith(color: colors.textTertiary)),
                ),
                if (watchAlert.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text(
                    watchAlert,
                    style: theme.textTheme.bodySmall?.copyWith(color: colors.warning, fontWeight: FontWeight.w700),
                  ),
                ],
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    FilledButton.icon(
                      key: const ValueKey('watch-start-block'),
                      onPressed: () => unawaited(_startBlockNow(focusBlock)),
                      icon: const Icon(AppIcons.play, size: 16),
                      label: const Text('开干此块'),
                    ),
                    OutlinedButton.icon(
                      key: const ValueKey('watch-gemini-step'),
                      onPressed: () => unawaited(_askGeminiFirstStep(focusBlock)),
                      icon: const Icon(AppIcons.askAi, size: 16),
                      label: const Text('叫 Gemini 拆第一步'),
                    ),
                    FilledButton.tonalIcon(
                      key: const ValueKey('watch-verify-block'),
                      onPressed: () => unawaited(_verifyBlockDone(focusBlock)),
                      icon: const Icon(AppIcons.verified, size: 16),
                      label: const Text('提交验收过关'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      const SizedBox(height: 14),
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
                      onPressed: _busy
                          ? null
                          : () async {
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
        key: const ValueKey('watch-24h-timeline'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(child: Text('24 小时垂直时间轴', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700))),
                  StatusChip(tone: StatusTone.error, label: '现在 ${_hm(nowMin)}'),
                ],
              ),
              const SizedBox(height: 10),
              SizedBox(
                height: timelineHeight,
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    for (var h = 0; h <= 24; h += 3)
                      Positioned(
                        top: (h / 24.0) * timelineHeight - (h == 24 ? 14 : 0),
                        left: 0,
                        right: 0,
                        child: Row(
                          children: [
                            SizedBox(
                              width: 44,
                              child: Text(
                                '${h.toString().padLeft(2, '0')}:00',
                                style: AppType.tabular(theme.textTheme.labelSmall!.copyWith(color: colors.textTertiary)),
                              ),
                            ),
                            Expanded(
                              child: Divider(
                                height: 1,
                                thickness: 0.6,
                                color: theme.colorScheme.outlineVariant.withValues(alpha: 0.45),
                              ),
                            ),
                          ],
                        ),
                      ),
                    for (final b in _dayBlocks)
                      () {
                        final start = _asInt(b['startMin'], 540).clamp(0, 1420);
                        final end = _asInt(b['endMin'], start + 45).clamp(start + 15, 1440);
                        final height = math.max(22.0, ((end - start) / 1440.0) * timelineHeight);
                        final top = ((start / 1440.0) * timelineHeight).clamp(0.0, math.max(0.0, timelineHeight - height)).toDouble();
                        final isBreak = b['isBreak'] == true;
                        final isRun = b['status'] == 'running';
                        final isDone = b['status'] == 'done';
                        final accent = isBreak
                            ? colors.success
                            : (isDone ? colors.info : (isRun ? theme.colorScheme.primary : colors.warning));
                        return Positioned(
                          top: top,
                          left: 50,
                          right: 4,
                          height: height,
                          child: Material(
                            type: MaterialType.transparency,
                            borderRadius: BorderRadius.circular(8),
                            clipBehavior: Clip.antiAlias,
                            child: InkWell(
                              onTap: () => unawaited(_openBlockEditor(b)),
                              child: Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                                decoration: BoxDecoration(
                                  color: accent.withValues(alpha: isRun ? 0.24 : 0.14),
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(color: accent.withValues(alpha: isRun ? 0.9 : 0.45)),
                                ),
                                child: Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        '${_hm(start)}–${_hm(end)} ${b['title']}',
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: theme.textTheme.labelSmall?.copyWith(fontWeight: FontWeight.w600),
                                      ),
                                    ),
                                    const SizedBox(width: 6),
                                    Text(
                                      isBreak ? '放风' : _blockStatusChip('${b['status']}').$2,
                                      style: theme.textTheme.labelSmall?.copyWith(color: accent, fontWeight: FontWeight.w700),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        );
                      }(),
                    Positioned(
                      key: const ValueKey('watch-now-redline'),
                      top: redLineTop,
                      left: 40,
                      right: 0,
                      child: Row(
                        children: [
                          Container(
                            width: 8,
                            height: 8,
                            decoration: BoxDecoration(color: colors.danger, shape: BoxShape.circle),
                          ),
                          Expanded(child: Container(height: 2, color: colors.danger)),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
      const SizedBox(height: 24),
    ];
  }
}
