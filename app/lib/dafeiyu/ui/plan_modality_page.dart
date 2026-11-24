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
import 'plan_hub_page.dart';
import 'plan_page.dart';
import 'seal_page.dart';
import 'strategy_horizons_page.dart';
import '../../ui/common/dispose_after_route.dart';

part 'plan_modality_prepare.dart';
part 'plan_modality_build.dart';
part 'plan_modality_watch.dart';
part 'plan_modality_dialogs.dart';

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
  Map<String, dynamic>? _planModal;
  String _planConfirmedDay = '';
  String _serverDay = '';
  int? _localArmedVerifyCount;
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

  void _mutate(VoidCallback fn) {
    if (mounted) setState(fn);
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
      final rawModal = res?['planModal'];
      if (rawModal is Map) {
        _planModal = Map<String, dynamic>.from(rawModal);
      } else if (res != null && res.containsKey('planModal')) {
        _planModal = null;
      }
      final dayStr = '${dayPlan?['day'] ?? res?['day'] ?? ''}'.trim();
      if (dayStr.isNotEmpty) {
        _serverDay = dayStr;
      }
      if (dayPlan != null && dayPlan.containsKey('planConfirmedDay')) {
        _planConfirmedDay = '${dayPlan['planConfirmedDay'] ?? ''}'.trim();
        _localArmedVerifyCount = null;
      }
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

  int _asInt(Object? raw, int fallback) => raw is num ? raw.round() : fallback;

  String _slotOf(int startMin) {
    if (startMin < 720) return 'morning';
    if (startMin < 840) return 'lunch';
    if (startMin < 1080) return 'afternoon';
    return 'evening';
  }

  String _hm(int min) {
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

  Future<void> _setCustodyPolicy({
    bool? autoKickoffEnabled,
    bool? adaptiveSplitEnabled,
    bool? mandatoryPlanModalEnabled,
  }) async {
    final cur = Map<String, dynamic>.from(_custodyPolicy ?? const {});
    final nextKick = autoKickoffEnabled ?? (cur['autoKickoffEnabled'] == true);
    final nextAdapt = adaptiveSplitEnabled ?? (cur['adaptiveSplitEnabled'] != false);
    final nextMandatory = mandatoryPlanModalEnabled ?? (cur['mandatoryPlanModalEnabled'] != false);
    setState(() {
      _custodyPolicy = {
        ...cur,
        'autoKickoffEnabled': nextKick,
        'adaptiveSplitEnabled': nextAdapt,
        'mandatoryPlanModalEnabled': nextMandatory,
      };
      _banner =
          '✓ 已更新托管哨兵策略（09:15/14:45 哨兵 ${nextKick ? '开' : '关'} · 打回/逾期缩块 ${nextAdapt ? '开' : '关'} · 强制计划模态 ${nextMandatory ? '开' : '关'}）';
    });
    final res = await _channel.planContractOp({
      'action': 'set_custody_policy',
      'autoKickoffEnabled': nextKick,
      'adaptiveSplitEnabled': nextAdapt,
      'mandatoryPlanModalEnabled': nextMandatory,
    }).catchError((_) => null);
    if (mounted && res?['dayPlan'] is Map) {
      final dp = Map<String, dynamic>.from(res!['dayPlan'] as Map);
      if (dp['custodyPolicy'] is Map) {
        setState(() => _custodyPolicy = Map<String, dynamic>.from(dp['custodyPolicy'] as Map));
      }
    }
    unawaited(_channel.tickNow().catchError((_) => false));
  }

  String _todayYmd() {
    final now = DateTime.now();
    String two(int v) => v.toString().padLeft(2, '0');
    return '${now.year}-${two(now.month)}-${two(now.day)}';
  }

  ({bool armed, int count}) _verifyLockArmedState() {
    final expectedDay = _serverDay.isNotEmpty ? _serverDay : _todayYmd();
    final blockVerifyCount = _dayBlocks.where((b) {
      if (b['isBreak'] == true || b['status'] == 'skipped') return false;
      final mode = '${b['lockMode'] ?? ''}';
      final vStatus = '${b['verifyStatus'] ?? ''}';
      return mode == 'verify' ||
          vStatus == 'armed' ||
          vStatus == 'submitted' ||
          vStatus == 'overdue_locked' ||
          vStatus == 'rejected' ||
          b['lockedByBoss'] == true;
    }).length;
    final summaryCount = _asInt(_verifySummary?['totalVerify'], 0);
    final count = math.max(math.max(blockVerifyCount, summaryCount), _localArmedVerifyCount ?? 0);
    final confirmedToday = _planConfirmedDay.isNotEmpty && _planConfirmedDay == expectedDay;
    final armed = _planModal?['active'] == true
        ? false
        : (confirmedToday || blockVerifyCount > 0 || summaryCount > 0 || (_localArmedVerifyCount ?? 0) > 0);
    return (armed: armed, count: count);
  }

  void _applyPlanModalMutationResult(Map<String, dynamic> res) {
    _hasLocalBlockEdits = false;
    _planModal = null;
    final dayPlan = res['dayPlan'] is Map ? Map<String, dynamic>.from(res['dayPlan'] as Map) : null;
    final dayStr = '${dayPlan?['day'] ?? res['day'] ?? ''}'.trim();
    if (dayStr.isNotEmpty) _serverDay = dayStr;
    final confDay = '${dayPlan?['planConfirmedDay'] ?? res['planConfirmedDay'] ?? ''}'.trim();
    _planConfirmedDay = confDay.isNotEmpty ? confDay : (_serverDay.isNotEmpty ? _serverDay : _todayYmd());
    final rawBlocks = res['blocks'] ?? dayPlan?['dayBlocks'];
    final blocks = _mapList(rawBlocks);
    if (blocks.isNotEmpty) {
      _dayBlocks = _sortBlocks(blocks);
    }
    final rawVerify = res['verifySummary'] ?? dayPlan?['verifySummary'];
    if (rawVerify is Map) {
      _verifySummary = Map<String, dynamic>.from(rawVerify);
    }
    final armedRaw = res['armedVerifyCount'];
    if (armedRaw is num) {
      _localArmedVerifyCount = armedRaw.toInt();
    } else if (blocks.isNotEmpty) {
      final vCount = blocks.where((b) => b['isBreak'] != true && '${b['lockMode'] ?? ''}' == 'verify').length;
      if (vCount > 0) _localArmedVerifyCount = vCount;
    }
  }

  Future<void> _confirmPlanModalLock() async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.planContractOp({'action': 'confirm_plan_modal'});
    if (!mounted) return;
    final ok = res != null && res['ok'] != false;
    if (ok) {
      setState(() => _applyPlanModalMutationResult(res));
      unawaited(_channel.tickNow().catchError((_) => false));
      await _refresh(silent: true);
      if (!mounted) return;
    }
    final armed = _verifyLockArmedState();
    final msg = ok
        ? '${res['reply'] ?? res['toast'] ?? '✓ 已确认今日草案并激活验收锁（${armed.count} 块）'}'
        : '激活验收锁失败：${res?['error'] ?? res?['text'] ?? '引擎暂未就绪'}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _negotiatePlanModalLock() async {
    if (_busy) return;
    final ctrl = TextEditingController();
    String? err;
    final text = await showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          key: const ValueKey('plan-negotiate-modal-dialog'),
          shape: AppShape.dialog,
          title: const Text('立即沟通构建今日计划'),
          content: SizedBox(
            width: 390,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    '写下今天打算推进的科目、时段和时长，引擎将立即解析为验收时间块并武装今日验收锁。',
                    style: TextStyle(fontSize: 12),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      CapsuleChoiceChip(
                        label: '例：判断推理40m + 申论45m',
                        selected: false,
                        onSelected: () => setDialog(() {
                          ctrl.text = '下午判断推理 40m，晚上申论复盘 45m';
                          err = null;
                        }),
                      ),
                      CapsuleChoiceChip(
                        label: '例：资料分析45m + 错题25m',
                        selected: false,
                        onSelected: () => setDialog(() {
                          ctrl.text = '上午资料分析 45m，下午错题复盘 25m';
                          err = null;
                        }),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    key: const ValueKey('plan-negotiate-modal-input'),
                    controller: ctrl,
                    autofocus: true,
                    minLines: 2,
                    maxLines: 4,
                    decoration: const InputDecoration(
                      labelText: '今日计划描述',
                      hintText: '如：下午判断推理 40m，晚上申论复盘 45m',
                      border: OutlineInputBorder(),
                    ),
                    onChanged: (_) {
                      if (err != null) setDialog(() => err = null);
                    },
                  ),
                  if (err != null) ...[
                    const SizedBox(height: 6),
                    Text(err!, style: TextStyle(color: DshColors.of(ctx).danger, fontSize: 12)),
                  ],
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('取消')),
            FilledButton(
              key: const ValueKey('plan-negotiate-modal-confirm'),
              onPressed: () {
                final v = ctrl.text.trim();
                if (v.isEmpty) {
                  setDialog(() => err = '请先输入今日计划内容');
                  return;
                }
                Navigator.of(ctx).pop(v);
              },
              child: const Text('生成并武装验收锁'),
            ),
          ],
        ),
      ),
    );
    disposeAfterRoute(ctrl);
    if (text == null || text.isEmpty || !mounted) return;
    setState(() => _busy = true);
    final res = await _channel.planContractOp({
      'action': 'negotiate_plan_modal',
      'text': text,
    });
    if (!mounted) return;
    final ok = res != null && res['ok'] != false;
    if (ok) {
      setState(() => _applyPlanModalMutationResult(res));
      unawaited(_channel.tickNow().catchError((_) => false));
      await _refresh(silent: true);
      if (!mounted) return;
    }
    final armed = _verifyLockArmedState();
    final msg = ok
        ? '${res['reply'] ?? res['toast'] ?? '✓ 已生成今日计划并武装验收锁（${armed.count} 块）'}'
        : '构建计划失败：${res?['error'] ?? res?['text'] ?? '引擎暂未就绪'}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
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

  String _energyLabel(String level) => switch (level) {
        'low' => '低电量保底',
        'high' => '满血冲刺',
        _ => '标准推进',
      };

  String _lockModeLabel(String mode) => switch (mode) {
        'gentle' => '温和',
        'verify' => '验收锁',
        _ => '严格',
      };

  StatusTone _lockTone(String mode) => switch (mode) {
        'gentle' => StatusTone.ok,
        'verify' => StatusTone.error,
        _ => StatusTone.warn,
      };

  (StatusTone, String) _blockStatusChip(String status) => switch (status) {
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
        PopupMenuButton<String>(
          key: const ValueKey('plan-more-views-btn'),
          tooltip: '更多计划视图（三层战略 / 封印 / 目录）',
          icon: const Icon(AppIcons.more),
          onSelected: (value) {
            switch (value) {
              case 'horizons':
                unawaited(StrategyHorizonsPage.open(context, channel: _channel));
              case 'seals':
                unawaited(SealPage.open(context, channel: _channel));
              case 'hub':
                unawaited(PlanHubPage.open(context, channel: _channel, loopChannel: _loop));
            }
          },
          itemBuilder: (ctx) => const [
            PopupMenuItem(value: 'horizons', child: Text('三层战略作战室（长期 / 中期）')),
            PopupMenuItem(value: 'seals', child: Text('封印与验收点明细')),
            PopupMenuItem(value: 'hub', child: Text('全部计划目录')),
          ],
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
        _buildVerifyModalStatusCard(theme, colors),
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

}
