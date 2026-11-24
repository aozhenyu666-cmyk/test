import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'app_picker_dialog.dart';
import 'plan_modality_page.dart';
import 'usage_analytics_page.dart';

part 'locks_balance_sections.dart';
part 'locks_balance_dialogs.dart';

/// 锁与余额总控页：
/// 1) 当前生效锁与验收锁强度（P0–P6 裁决 + 逾期严锁警示）；
/// 2) 手机可用娱乐余额大盘（结转 / 刷题 / 工作 + 三档分时花销规则 + 花余额 / 3m 急事应急通道）；
/// 3) 全系统 6 只手与规则全景（P0–P6）；
/// 4) 最近系统 & 双鱼设局轨迹（只读）。
class LocksBalancePage extends StatefulWidget {
  const LocksBalancePage({super.key, this.channel, this.loopChannel});

  final ProactiveChannel? channel;
  final LoopChannel? loopChannel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel, LoopChannel? loopChannel}) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => LocksBalancePage(channel: channel, loopChannel: loopChannel)),
      );

  @override
  State<LocksBalancePage> createState() => _LocksBalancePageState();
}

class _LocksBalancePageState extends State<LocksBalancePage> {
  static const _defaultWhitelistPresets = <LoopApp>[
    LoopApp('md.obsidian', 'Obsidian'),
    LoopApp('com.tencent.mm', '微信'),
    LoopApp('com.google.android.apps.bard', 'Gemini'),
    LoopApp('com.tencent.mobileqq', 'QQ'),
  ];

  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final LoopChannel _loop = widget.loopChannel ?? LoopChannel();

  bool _loading = true;
  bool _busy = false;
  String? _banner;

  Map<String, dynamic> _today = const {};
  Map<String, dynamic>? _seals;
  Map<String, Object?>? _wall;
  bool? _lockShadow;
  List<String>? _defaultAllowOverride;
  final Map<String, String> _knownLabels = {
    'md.obsidian': 'Obsidian',
    'com.tencent.mm': '微信',
    'com.google.android.apps.bard': 'Gemini',
    'com.tencent.mobileqq': 'QQ',
  };

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    if (mounted && _today.isEmpty && _seals == null) {
      setState(() => _loading = true);
    }
    final primary = <Future<void>>[];

    final sealsFuture = _channel.sealsOp(const {'action': 'locks_overview'}).then((r) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (r != null) _seals = _asMap(r);
      });
    }).catchError((_) {
      if (mounted && _loading) setState(() => _loading = false);
    });
    primary.add(sealsFuture);

    final todayFuture = _channel.todayStatus().then((r) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (r != null) _today = _asMap(r);
      });
    }).catchError((_) {
      if (mounted && _loading) setState(() => _loading = false);
    });
    primary.add(todayFuture);

    unawaited(
      _channel.wallStatus().then((r) {
        if (!mounted || r == null) return;
        setState(() => _wall = r);
      }).catchError((_) {}),
    );
    unawaited(
      _channel.lockShadow().then((r) {
        if (!mounted || r == null) return;
        setState(() => _lockShadow = r);
      }).catchError((_) {}),
    );

    await Future.any(primary);
  }

  Map<String, dynamic> _asMap(Object? raw) {
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return {
        for (final e in raw.entries)
          if (e.key is String) e.key as String: e.value,
      };
    }
    return const {};
  }

  List<Map<String, dynamic>> _asListOfMaps(Object? raw) {
    if (raw is! List) return const [];
    return [
      for (final item in raw)
        if (item is Map) _asMap(item),
    ];
  }

  int _asInt(Object? raw, [int fallback = 0]) => raw is num ? raw.round() : fallback;

  String _fallbackWindowNow() {
    final now = DateTime.now();
    final min = now.hour * 60 + now.minute;
    if (min >= 12 * 60 && min < 14 * 60) return 'lunch';
    if (min >= 18 * 60 && min < 23 * 60) return 'evening';
    if (min < 7 * 60 || min >= 23 * 60) return 'night';
    return 'daytime';
  }

  String _windowLabelOf(String win) => switch (win) {
        'lunch' => '午休自由时段（12:00–14:00 不占白天槽）',
        'evening' => '晚间自由时段（18:00 起温和封顶 120m）',
        'night' => '深夜睡眠时段（23:00–07:00 锁定）',
        _ => '白天工作时段（零散上限 30m）',
      };

  Map<String, dynamic> _resolveOverview() {
    final fromSeals = _asMap(_seals?['locksOverview']);
    if (fromSeals.isNotEmpty) return fromSeals;
    return _asMap(_today['locksOverview']);
  }

  Map<String, dynamic> _resolveActiveLock(Map<String, dynamic> overview) {
    final ov = _asMap(overview['activeLock']);
    if (ov.isNotEmpty) return ov;

    final locking = _asMap(_seals?['locking']);
    if (locking.isNotEmpty) {
      return {
        'active': true,
        'priority': 'P3',
        'source': 'CONTRACT_OVERDUE_STRICT',
        'label': '验收点逾期严锁',
        'why': '「${locking['title'] ?? '今日验收点'}」已到点未过保底',
        'unlockHow': '交付验收或过保底（不可花零散余额绕过）',
        'verifyOverdue': true,
      };
    }

    final arbiter = _asMap(_today['lockArbiter']);
    if (arbiter.isNotEmpty) {
      final prio = '${arbiter['priority'] ?? 'P6'}';
      final src = '${arbiter['source'] ?? 'AUTONOMY_RELAXED'}';
      return {
        ...arbiter,
        'verifyOverdue': arbiter['verifyOverdue'] == true ||
            prio == 'P3' ||
            src == 'CONTRACT_OVERDUE_STRICT',
      };
    }

    final wallLocked = _wall?['locked'] == true;
    return {
      'active': wallLocked,
      'priority': wallLocked ? 'P4' : 'P6',
      'source': wallLocked ? 'BOUT_STRICT' : 'AUTONOMY_RELAXED',
      'label': wallLocked ? '专心局 / 门禁护航中' : '自主自治 · 正常放行',
      'why': '${_wall?['why'] ?? (wallLocked ? '当前处于专注保护时段' : '暂无高优先级强锁生效')}',
      'unlockHow': wallLocked ? '完成本局验收 / 花挣来余额 / 锁屏刷题临放' : '保持节奏即可',
      'verifyOverdue': false,
    };
  }

  Map<String, dynamic> _resolveWallet(Map<String, dynamic> overview, bool verifyOverdue) {
    final w = _asMap(overview['wallet']);
    if (w.isNotEmpty) return w;

    final ledger = TimeLedgerView.fromWire(_today['timeLedger']);
    final availableMin = ledger?.funLeft ?? 0;
    final eveningSpentMin = ledger?.funUsed ?? 0;
    final win = _fallbackWindowNow();
    final hasLedger = ledger != null;
    final canSpendNow = !verifyOverdue && (!hasLedger || availableMin >= 5);
    final spendBlockReason = verifyOverdue
        ? '当前存在逾期验收点，须先交付过保底，不可用零散余额绕过。'
        : (hasLedger && availableMin < 5 ? '当前可用娱乐余额不足 5 分钟，先刷题或完成一局挣取额度。' : '');

    return {
      'availableMin': availableMin,
      'carryInMin': ledger?.carry ?? 0,
      'earnedBankMin': ledger?.earnedBank ?? 0,
      'earnedBankCap': 60,
      'earnedWorkMin': ledger?.earnedBout ?? 0,
      'earnedTotalMin': ledger?.earned ?? 0,
      'penaltyMin': ledger?.penalty ?? 0,
      'daytimeSpentMin': 0,
      'daytimeCapMin': 30,
      'daytimeRemainingMin': 30,
      'eveningSpentMin': eveningSpentMin,
      'eveningCapMin': 120,
      'eveningRemainingMin': (120 - eveningSpentMin).clamp(0, 120),
      'currentWindow': win,
      'currentWindowLabel': _windowLabelOf(win),
      'floorPassed': false,
      'canSpendNow': canSpendNow,
      'spendBlockReason': spendBlockReason,
      'nextFreeWindowText': '12:00–14:00 午休或 18:00 后',
      'activeSpendPass': null,
      'lockShadow': _lockShadow ?? false,
      '_synthesized': true,
    };
  }

  List<Map<String, dynamic>> _resolveAllLocks(Map<String, dynamic> overview, Map<String, dynamic> activeLock) {
    final list = _asListOfMaps(overview['allLocks']);
    if (list.isNotEmpty) return list;

    final activePrio = '${activeLock['priority'] ?? ''}';
    final isActive = activeLock['active'] == true;
    final defaults = <Map<String, dynamic>>[
      {
        'id': 'EMERGENCY_SUSPEND',
        'priority': 'P0',
        'name': '急事 3 分钟应急通道',
        'summary': '最高优先级：短暂挂起严格锁（3–10 分钟），电话/短信/支付/导航永不锁',
        'unlockHow': '到时自动回位或主动撤销',
      },
      {
        'id': 'NIGHT_CURFEW',
        'priority': 'P1',
        'name': '深夜睡眠 & 晚间上限锁',
        'summary': '23:00–07:00 睡眠守护，或 18:00 后娱乐用满 120m 温和封顶',
        'unlockHow': '次日 07:00 自动解除',
      },
      {
        'id': 'GEMINI_LIVE_CELL',
        'priority': 'P2',
        'name': 'Gemini 连麦舱',
        'summary': '进入 Gemini 语音/深度共脑舱，仅放行共脑必需应用',
        'unlockHow': '完成复盘讨论或舱时结束',
      },
      {
        'id': 'CONTRACT_OVERDUE_STRICT',
        'priority': 'P3',
        'name': '契约 / 封印逾期严锁',
        'summary': '验收点或契约到点未过保底，禁止花零散余额绕过',
        'unlockHow': '交付证据过保底或写理由豁免',
      },
      {
        'id': 'TAKEOVER_STRICT',
        'priority': 'P3.5',
        'name': '强制接管回位锁',
        'summary': '偏航或连续告警触发的强接管，拉回主线',
        'unlockHow': '完成规定回位动作或刷题解锁',
      },
      {
        'id': 'BOUT_STRICT',
        'priority': 'P4',
        'name': '专心局 / 默认工作窗验收锁',
        'summary': '专注局进行中或处于工作时段默认验收，只放常用白名单 App',
        'unlockHow': '收工验收过关、花挣来余额或刷题临放',
      },
      {
        'id': 'CONTRACT_FLEX',
        'priority': 'P5',
        'name': '契约柔性护航 / 外挂租约',
        'summary': '契约进行中或合法放风窗口期内柔性观察',
        'unlockHow': '到期自动结算或通过检查点',
      },
      {
        'id': 'AUTONOMY_RELAXED',
        'priority': 'P6',
        'name': '余额门槛 & 行为雷达 / 双鱼临时封禁',
        'summary': '娱乐余额归零、行为雷达预警或鱼丸/鲸澜临时限制特定娱乐 App',
        'unlockHow': '刷题/过局挣取余额或等待双鱼解封',
      },
    ];
    return [
      for (final d in defaults)
        {
          ...d,
          'active': isActive && d['priority'] == activePrio,
        },
    ];
  }

  StatusTone _toneForPriority(String priority, bool active) {
    if (!active) return StatusTone.idle;
    return switch (priority) {
      'P0' => StatusTone.busy,
      'P1' || 'P5' => StatusTone.ok,
      'P2' || 'P3' || 'P3.5' || 'P4' => StatusTone.error,
      _ => StatusTone.warn,
    };
  }

  Future<void> _handleSpendTap(Map<String, dynamic> wallet, bool verifyOverdue) async {
    if (_busy) return;
    final synthesized = wallet['_synthesized'] == true;
    final canSpendNow = wallet['canSpendNow'] != false;
    final blockReason = '${wallet['spendBlockReason'] ?? ''}'.trim();
    if (verifyOverdue || (!synthesized && !canSpendNow)) {
      final msg = blockReason.isNotEmpty
          ? blockReason
          : '逾期严锁：当前不可花零散余额绕过，须先交付验收或保底。';
      setState(() => _banner = msg);
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
      return;
    }

    final out = await showDialog<(int, String)>(
      context: context,
      builder: (_) => const _SpendBalanceDialog(),
    );
    if (out == null || !mounted) return;
    setState(() => _busy = true);
    final res = await _channel.sealsOp({
      'action': 'spend',
      'minutes': out.$1,
      'reason': out.$2,
    });
    if (res?['ok'] == true) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh();
    if (!mounted) return;
    final ok = res?['ok'] == true;
    final msg = '${res?['text'] ?? (res == null ? '暂未连上伴侣引擎，无法花费余额' : (ok ? '已扣减 ${out.$1} 分钟娱乐余额' : '未通过：${res['error'] ?? '不符合花费条件'}'))}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _handleToggleDayWall(bool enabled) async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.sealsOp({
      'action': 'toggle_day_wall',
      'enabled': enabled,
    });
    if (res != null && res['ok'] != false) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh();
    if (!mounted) return;
    final ok = res != null && res['ok'] != false;
    final msg = ok
        ? '${res['text'] ?? (enabled ? '已开启工作时段默认防分心锁' : '已关闭工作时段默认防分心锁')}'
        : '${res?['text'] ?? '切换默认防分心锁失败，请确认引擎在线'}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _handleStrictOff() async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.sealsOp({'action': 'strict_off'});
    if (res != null && res['ok'] != false) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh();
    if (!mounted) return;
    final ok = res != null && res['ok'] != false;
    final msg = ok
        ? '${res['text'] ?? '已撤回当前手动严格锁'}'
        : '${res?['text'] ?? '当前存在硬规则锁，无法直接撤回'}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _handleEmergencyPass() async {
    if (_busy) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const ValueKey('locks-emergency-dialog'),
        title: const Text('启用 3 分钟急事应急通道？'),
        content: const Text(
          '仅用于接电话、取件、突发回复等真实急事：将记录 1 次每日应急解除额度，并暂时挂起严格锁 3 分钟，3 分钟后自动恢复守护。',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('算了')),
          FilledButton(
            key: const ValueKey('locks-emergency-confirm'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('确认开启 3 分钟'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _busy = true);
    final ok = await _channel.emergencySuspendStrict(minutes: 3);
    if (ok) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh();
    if (!mounted) return;
    final msg = ok ? '⚡ 已启用 3 分钟急事应急通道，请处理完后尽快回位' : '未能启用应急通道（请检查引擎是否在线）';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _openSubmitDeliveryDialog(Map<String, dynamic> block) async {
    if (_busy) return;
    final id = '${block['id'] ?? ''}'.trim();
    if (id.isEmpty) return;
    final summary = await showDialog<String>(
      context: context,
      builder: (_) => _SubmitDeliveryDialog(
        title: '${block['title'] ?? '验收时间块'}',
        deliverable: '${block['deliverable'] ?? '真实产出或证据说明'}',
        initialText: '${block['submissionText'] ?? ''}',
      ),
    );
    if (summary == null || !mounted) return;
    setState(() => _busy = true);
    final res = await _channel.planContractOp({
      'action': 'submit_delivery',
      'blockId': id,
      'summary': summary,
    });
    if (res != null && res['ok'] == true) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh();
    if (!mounted) return;
    final ok = res != null && res['ok'] == true;
    final msg = ok
        ? '已提交「${block['title'] ?? id}」交付，可点击「通知主管终审」或切到 Gemini 完成验证解锁'
        : '${res?['text'] ?? '提交交付失败，请确认引擎在线'}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _requestBossJudge(Map<String, dynamic> block) async {
    if (_busy) return;
    final id = '${block['id'] ?? ''}'.trim();
    if (id.isEmpty) return;
    setState(() => _busy = true);
    final res = await _channel.wallBossChat({
      'action': 'request_judge',
      'blockId': id,
    });
    if (res != null && res['ok'] != false) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh();
    if (!mounted) return;
    final ok = res != null && res['ok'] != false;
    final msg = ok
        ? '${res['text'] ?? '已通知主管质询室开展冷血终审，结果将自动同步解锁'}'
        : '${res?['text'] ?? '暂无法发起主管终审（请先在「墙/主管质询室」绑定第 3 独立会话）'}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  List<String> _resolveDefaultAllow(Map<String, dynamic> overview) {
    if (_defaultAllowOverride != null && _defaultAllowOverride!.isNotEmpty) {
      return _defaultAllowOverride!;
    }
    final fromToday = _today['defaultAllow'];
    if (fromToday is List && fromToday.isNotEmpty) {
      return [for (final p in fromToday) if ('$p'.trim().isNotEmpty) '$p'.trim()];
    }
    final eff = _asMap(overview['effectiveDecision']);
    final allowList = _asListOfMaps(eff['allow']);
    if (allowList.isNotEmpty) {
      for (final item in allowList) {
        final pkg = '${item['pkg'] ?? ''}'.trim();
        final label = '${item['label'] ?? ''}'.trim();
        if (pkg.isNotEmpty && label.isNotEmpty) _knownLabels[pkg] = label;
      }
      return [
        for (final item in allowList)
          if ('${item['pkg'] ?? ''}'.trim().isNotEmpty) '${item['pkg']}'.trim(),
      ];
    }
    return const ['md.obsidian', 'com.tencent.mm'];
  }

  String _allowLabelOf(String pkg) => _knownLabels[pkg] ?? pkg;

  Future<void> _handleEditDefaultAllow(Map<String, dynamic> overview) async {
    if (_busy) return;
    final current = _resolveDefaultAllow(overview);
    final picked = await showInstalledAppsPicker(
      context,
      title: '调整手机默认专注白名单',
      loop: _loop,
      presets: _defaultWhitelistPresets,
      presetsLabel: '常用工具 / 沟通应用快捷勾选',
      initialSelected: current,
      allowEmpty: false,
      emptyError: '至少保留一个默认白名单应用',
      confirmLabel: '保存并持久化',
    );
    if (picked == null || !mounted) return;
    for (final a in picked) {
      _knownLabels[a.package] = a.label;
    }
    final pkgs = picked.map((a) => a.package).toList();
    setState(() => _busy = true);
    final res = await _channel.setDefaultAllow(pkgs);
    if (!mounted) return;
    if (res != null && res['ok'] != false) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    final serverAllowRaw = res?['defaultAllow'] ?? res?['allow'];
    final serverAllow = serverAllowRaw is List
        ? [for (final p in serverAllowRaw) if ('$p'.trim().isNotEmpty) '$p'.trim()]
        : const <String>[];
    final rejectedRaw = res?['rejectedGames'];
    final rejected = rejectedRaw is List
        ? [for (final g in rejectedRaw) if ('$g'.trim().isNotEmpty) _allowLabelOf('$g'.trim())]
        : const <String>[];
    final toast = '${res?['toast'] ?? ''}'.trim();
    if (res != null && res['ok'] != false) {
      _defaultAllowOverride = serverAllow.isNotEmpty ? serverAllow : pkgs;
    }
    await _refresh();
    if (!mounted) return;
    final String msg;
    if (res == null) {
      msg = '引擎暂未响应，默认白名单未持久化';
    } else if (res['ok'] == false) {
      final err = toast.isNotEmpty ? toast : '${res['error'] ?? '引擎拒绝修改'}';
      msg = rejected.isNotEmpty ? '$err（已拦截纯游戏：${rejected.join('、')}）' : err;
    } else {
      final count = (_defaultAllowOverride ?? pkgs).length;
      final base = toast.isNotEmpty ? toast : '已持久化手机默认白名单（$count 个应用）';
      msg = rejected.isNotEmpty ? '$base · 已拦截纯游戏：${rejected.join('、')}' : base;
    }
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _handleReverseGeminiLive() async {
    if (_busy) return;
    setState(() => _busy = true);
    final capRes = await _channel.activateReverseCapsule(mode: 'gemini_only', minutes: 15);
    final sparkRes = await _channel.superSparkOp(const {'action': 'trigger', 'modeHint': 'contrast_push'});
    final spark = sparkRes?['spark'] is Map
        ? Map<String, dynamic>.from(sparkRes!['spark'] as Map)
        : (sparkRes ?? const <String, dynamic>{});
    final topic = '${spark['geminiTopic'] ?? spark['title'] ?? '反锁进 Gemini 共脑 15m'}'.trim();
    final brief = '${spark['geminiBrief'] ?? spark['ask'] ?? '已开启 Gemini 单开舱 15 分钟，请直接开考或推进手头核心题/交付'}'.trim();
    final voiceLine = '${spark['voiceLine'] ?? '已锁定其他应用 15 分钟，只留 Gemini 与你直接开战'}'.trim();
    final autoClickLive = spark['autoClickLive'] is bool ? spark['autoClickLive'] == true : true;
    unawaited(_channel.tickNow().catchError((_) => false));
    final launched = await _channel.triggerGeminiAutoLive(
      topic: topic,
      brief: brief,
      voiceLine: voiceLine,
      mode: 'instant',
      autoClickLive: autoClickLive,
    );
    await _refresh();
    if (!mounted) return;
    final capMsg = '${capRes?['toast'] ?? (capRes?['ok'] == true ? '已启用 Gemini 单开舱（15m）' : '单开舱已请求')}';
    final msg = launched ? '$capMsg · 已灌料拉起 Gemini Live' : '$capMsg · 已准备灌料简报';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _handleReverseQuizCapsule() async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.activateReverseCapsule(mode: 'quiz_only', minutes: 20);
    if (res != null && res['ok'] != false) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh();
    if (!mounted) return;
    final ok = res != null && res['ok'] != false;
    final msg = ok
        ? '${res['toast'] ?? '已启用刷题单开舱（20m）：仅保留考公刷题/笔记应用'}'
        : '${res?['toast'] ?? res?['error'] ?? '开启刷题单开舱失败，请确认引擎在线'}';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _handleCommuteAudioSpark() async {
    if (_busy) return;
    setState(() => _busy = true);
    final sparkRes = await _channel.superSparkOp(const {'action': 'trigger', 'modeHint': 'commute_audio'});
    final spark = sparkRes?['spark'] is Map
        ? Map<String, dynamic>.from(sparkRes!['spark'] as Map)
        : (sparkRes ?? const <String, dynamic>{});
    final topic = '${spark['geminiTopic'] ?? spark['title'] ?? '骑车/做饭 · 语音抽背'}'.trim();
    final brief = '${spark['geminiBrief'] ?? spark['ask'] ?? '当前处于骑车/做饭无纸笔场景，请直接口语快问快答抽背高频考点'}'.trim();
    final voiceLine = '${spark['voiceLine'] ?? '语音抽背已就位，直接开口答题，不要客套'}'.trim();
    final launched = await _channel.triggerGeminiAutoLive(
      topic: topic,
      brief: brief,
      voiceLine: voiceLine,
      mode: 'instant',
      autoClickLive: true,
    );
    if (!mounted) return;
    final msg = launched ? '⚡ 已拉起语音抽背并自动进 Gemini Live：$topic' : '语音抽背简报已就绪：$topic';
    setState(() {
      _busy = false;
      _banner = msg;
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final scheme = theme.colorScheme;

    final overview = _resolveOverview();
    final activeLock = _resolveActiveLock(overview);
    final verifyOverdue = activeLock['verifyOverdue'] == true ||
        '${activeLock['priority'] ?? ''}' == 'P3' ||
        '${activeLock['source'] ?? ''}' == 'CONTRACT_OVERDUE_STRICT';
    final wallet = _resolveWallet(overview, verifyOverdue);
    final allLocks = _resolveAllLocks(overview, activeLock);
    final recentActions = _asListOfMaps(overview['recentActions']);

    final active = activeLock['active'] == true;
    final priority = '${activeLock['priority'] ?? 'P6'}';
    final source = '${activeLock['source'] ?? 'AUTONOMY_RELAXED'}';
    final label = '${activeLock['label'] ?? (active ? '严格锁护航中' : '自主自治')}';
    final why = '${activeLock['why'] ?? '由统一锁裁决器实时计算当前优先级'}';
    final unlockHow = '${activeLock['unlockHow'] ?? '完成当前验收或等待放行窗口'}';

    final availableMin = _asInt(wallet['availableMin']);
    final carryInMin = _asInt(wallet['carryInMin']);
    final earnedBankMin = _asInt(wallet['earnedBankMin']);
    final earnedBankCap = _asInt(wallet['earnedBankCap'], 60).clamp(1, 600);
    final earnedWorkMin = _asInt(wallet['earnedWorkMin']);
    final penaltyMin = _asInt(wallet['penaltyMin']);
    final daytimeSpentMin = _asInt(wallet['daytimeSpentMin']);
    final daytimeCapMin = _asInt(wallet['daytimeCapMin'], 30).clamp(1, 300);
    final daytimeRemainingMin = _asInt(wallet['daytimeRemainingMin'], (daytimeCapMin - daytimeSpentMin).clamp(0, daytimeCapMin));
    final eveningSpentMin = _asInt(wallet['eveningSpentMin']);
    final eveningCapMin = _asInt(wallet['eveningCapMin'], 120).clamp(1, 600);
    final eveningRemainingMin = _asInt(wallet['eveningRemainingMin'], (eveningCapMin - eveningSpentMin).clamp(0, eveningCapMin));
    final currentWindow = '${wallet['currentWindow'] ?? 'daytime'}';
    final currentWindowLabel = '${wallet['currentWindowLabel'] ?? _windowLabelOf(currentWindow)}';
    final floorPassed = wallet['floorPassed'] == true;
    final nextFreeWindowText = '${wallet['nextFreeWindowText'] ?? '12:00–14:00 午休或 18:00 后'}';
    final lockShadow = wallet['lockShadow'] == true || _lockShadow == true;

    final activeTone = _toneForPriority(priority, active);

    return AppPage.list(
      title: '锁与余额总控',
      subtitle: const Text('6 只手统一裁决 · 分时余额大盘 · 双鱼设局轨迹'),
      actions: [
        GlassIconButton(
          icon: AppIcons.chart,
          tooltip: 'App 使用时长统计（独立页）',
          onPressed: () => unawaited(UsageAnalyticsPage.open(context, channel: _channel)),
        ),
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新锁与余额',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        if (_banner != null) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: GlassSurface(
              tier: GlassTier.pill,
              borderRadius: AppShape.radiusMd,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                child: Row(
                  children: [
                    const AnimatedStatusDot(tone: StatusTone.ok, size: 7),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(_banner!, style: theme.textTheme.bodySmall?.copyWith(color: colors.info)),
                    ),
                    GestureDetector(
                      onTap: () => setState(() => _banner = null),
                      child: Icon(AppIcons.close, size: 16, color: colors.textTertiary),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],

        if (_loading && _today.isEmpty && _seals == null)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 48),
            child: Center(child: CircularProgressIndicator()),
          )
        else ...[
          // 板块 -1 · 白名单快捷调整与反向单开舱（填平发呆洼地）
          _buildTopWhitelistAndCapsuleCard(
            context,
            theme: theme,
            colors: colors,
            scheme: scheme,
            overview: overview,
          ),

          // 板块 0 · 此刻哪只手在发力 · 锁机探针
          _buildLockProbeCard(
            context,
            theme: theme,
            colors: colors,
            scheme: scheme,
            overview: overview,
            activeLock: activeLock,
            verifyOverdue: verifyOverdue,
          ),

          // 板块 0.5 · 验收锁中枢（同步计划模态验收块 & 主管硬指令）
          _buildVerifyCenterCard(
            context,
            theme: theme,
            colors: colors,
            scheme: scheme,
            overview: overview,
          ),

          // 板块 1 · 当前生效锁与验收锁强度
          OverviewCard(
            key: const ValueKey('locks-active-card'),
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            leading: OverviewGlyph(active ? AppIcons.lockActive : AppIcons.unlock, tint: active ? 3 : 6),
            title: Row(
              children: [
                Expanded(child: Text('$priority · $label', maxLines: 1, overflow: TextOverflow.ellipsis)),
                const SizedBox(width: 8),
                StatusChip(tone: activeTone, label: source),
              ],
            ),
            subtitle: Text(why, maxLines: 2, overflow: TextOverflow.ellipsis),
            footer: OverviewFooter(
              tone: activeTone,
              text: '解锁路径：$unlockHow',
            ),
          ),

          if (verifyOverdue)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Container(
                key: const ValueKey('locks-overdue-warning'),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: colors.dangerContainer,
                  borderRadius: AppShape.radiusLg,
                  border: Border.all(color: colors.danger.withValues(alpha: 0.35)),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(AppIcons.warning, size: 20, color: colors.danger),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '逾期严锁：不可花零散余额绕过，须先交付',
                            style: theme.textTheme.titleSmall?.copyWith(
                              color: colors.onDangerContainer,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            '到点未过保底已提高验收锁强度。请先提交验收过保底；确有突发急事请走下方 3 分钟应急通道。',
                            style: theme.textTheme.bodySmall?.copyWith(color: colors.onDangerContainer),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),

          if (lockShadow)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: GlassSurface(
                tier: GlassTier.pill,
                borderRadius: AppShape.radiusMd,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  child: Row(
                    children: [
                      Icon(AppIcons.info, size: 16, color: colors.info),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '当前处于测试模式（影子锁开启）：只提示拦截原因，不落真锁',
                          style: theme.textTheme.bodySmall?.copyWith(color: colors.info),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),

          // 板块 2 · 手机可用娱乐余额大盘
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: GlassSurface(
              key: const ValueKey('locks-wallet-card'),
              tier: GlassTier.card,
              borderRadius: AppShape.radiusLg,
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        const OverviewGlyph(AppIcons.balance, tint: 4, size: 38),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('手机可用娱乐余额', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                              const SizedBox(height: 2),
                              Text(
                                '$availableMin 分钟',
                                key: const ValueKey('locks-available-min'),
                                style: AppType.tabular(
                                  theme.textTheme.headlineMedium!.copyWith(
                                    fontWeight: FontWeight.w700,
                                    color: availableMin > 0 ? scheme.primary : colors.danger,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        StatusChip(
                          tone: currentWindow == 'night'
                              ? StatusTone.error
                              : (currentWindow == 'daytime' ? StatusTone.warn : StatusTone.ok),
                          label: switch (currentWindow) {
                            'lunch' => '午休自由窗',
                            'evening' => '晚间自由窗',
                            'night' => '深夜锁',
                            _ => '白天零散窗',
                          },
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      '$currentWindowLabel · 自由花销：$nextFreeWindowText',
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        _MiniBalanceMetric(label: '昨日结转', value: '${carryInMin}m', accent: colors.info),
                        const SizedBox(width: 8),
                        _MiniBalanceMetric(label: '今日刷题', value: '$earnedBankMin/${earnedBankCap}m', accent: colors.success),
                        const SizedBox(width: 8),
                        _MiniBalanceMetric(
                          label: penaltyMin > 0 ? '工作/扣罚' : '今日工作',
                          value: penaltyMin > 0 ? '+$earnedWorkMin/-${penaltyMin}m' : '${earnedWorkMin}m',
                          accent: penaltyMin > 0 ? colors.warning : scheme.primary,
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Text('分时花销规则（防白天碎片刷完 + 晚间温和封顶）', style: theme.textTheme.labelLarge),
                    const SizedBox(height: 6),
                    _WindowRuleMeter(
                      title: '① 白天工作零散槽位',
                      badgeText: floorPassed ? '已过保底·不限' : '$daytimeSpentMin / ${daytimeCapMin}m',
                      progress: floorPassed ? 0.0 : (daytimeSpentMin / daytimeCapMin).clamp(0.0, 1.0),
                      accent: floorPassed ? colors.success : (daytimeRemainingMin <= 5 ? colors.warning : scheme.primary),
                      subtitle: floorPassed
                          ? '今日保底已过，白天零散 30m 限制已自动解除'
                          : '白天零散上限 30m（剩 ${daytimeRemainingMin}m），过了今日保底自动解除',
                    ),
                    const SizedBox(height: 6),
                    _WindowRuleMeter(
                      title: '② 午休自由时段（12:00–14:00）',
                      badgeText: currentWindow == 'lunch' ? '此刻自由' : '不占白天 30m',
                      progress: currentWindow == 'lunch' ? 1.0 : 0.0,
                      accent: colors.success,
                      subtitle: '同晚间同等自由花余额（不占白天工作 30m 槽位）',
                    ),
                    const SizedBox(height: 6),
                    _WindowRuleMeter(
                      title: '③ 晚间自由时段（18:00 起）',
                      badgeText: '$eveningSpentMin / ${eveningCapMin}m',
                      progress: (eveningSpentMin / eveningCapMin).clamp(0.0, 1.0),
                      accent: eveningRemainingMin <= 15 ? colors.warning : colors.info,
                      subtitle: '18:00 后温和封顶 ${eveningCapMin}m（还剩 ${eveningRemainingMin}m），用完防熬夜刷到凌晨',
                    ),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        Expanded(
                          child: FilledButton.icon(
                            key: const ValueKey('locks-spend-btn'),
                            style: FilledButton.styleFrom(shape: AppShape.card),
                            onPressed: _busy ? null : () => unawaited(_handleSpendTap(wallet, verifyOverdue)),
                            icon: const Icon(AppIcons.play, size: 17),
                            label: const Text('花余额放松'),
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: OutlinedButton.icon(
                            key: const ValueKey('locks-emergency-btn'),
                            style: OutlinedButton.styleFrom(shape: AppShape.card),
                            onPressed: _busy ? null : () => unawaited(_handleEmergencyPass()),
                            icon: const Icon(AppIcons.unlock, size: 17),
                            label: const Text('急事 3 分钟通道'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),

          // 板块 3 · 全系统 6 只手与规则全景（P0–P6）
          ListPageCard(
            key: const ValueKey('locks-all-card'),
            title: '全系统 6 只手与规则全景（P0–P6）',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            children: [
              for (final item in allLocks)
                () {
                  final isOn = item['active'] == true;
                  final prio = '${item['priority'] ?? 'P6'}';
                  return ListPageRow(
                    key: ValueKey('lock-item-$prio'),
                    icon: isOn ? AppIcons.lockActive : AppIcons.lock,
                    iconColor: isOn ? colors.danger : colors.textTertiary,
                    title: '$prio · ${item['name'] ?? item['id'] ?? ''}',
                    subtitle: '${item['summary'] ?? ''} · 解锁：${item['unlockHow'] ?? '按规则解除'}',
                    subtitleMaxLines: 3,
                    badge: StatusChip(
                      tone: isOn ? StatusTone.error : StatusTone.idle,
                      label: isOn ? '生效中' : '未触发',
                    ),
                  );
                }(),
            ],
          ),

          // 板块 4 · 最近系统 & 双鱼设局轨迹（只读）
          ListPageCard(
            key: const ValueKey('locks-recent-card'),
            title: '最近系统 & 双鱼设局轨迹（只读）',
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: recentActions.isEmpty
                ? const [
                    ListPageRow(
                      icon: AppIcons.history,
                      title: '今日暂无设局、封禁或花余额轨迹',
                      subtitle: '鱼丸 / 鲸澜 / 行为雷达限制特定 App 或开启专心局后，会透明记录于此',
                      subtitleMaxLines: 2,
                    ),
                  ]
                : [
                    for (final act in recentActions.take(20))
                      ListPageRow(
                        icon: AppIcons.history,
                        iconColor: scheme.primary,
                        title: '${act['actor'] ?? '系统'} · ${act['action'] ?? '动作'}${'${act['target'] ?? ''}'.isNotEmpty ? '（${act['target']}）' : ''}',
                        subtitle: '${act['reason'] ?? ''}',
                        subtitleMaxLines: 2,
                        meta: '${act['hm'] ?? ''}',
                      ),
                  ],
          ),

          ListPageCard(
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            children: [
              ListPageRow(
                key: const ValueKey('locks-open-analytics-row'),
                icon: AppIcons.chart,
                iconColor: colors.info,
                title: 'App 使用时长统计（独立页）',
                subtitle: '查看 24h 时间切片、App 用时排行、90 天趋势与晚间总报',
                subtitleMaxLines: 2,
                chevron: true,
                onTap: () => unawaited(UsageAnalyticsPage.open(context, channel: _channel)),
              ),
            ],
          ),
        ],
      ],
    );
  }

  List<Map<String, dynamic>> _resolveSixHands(
    Map<String, dynamic> overview,
    Map<String, dynamic> activeLock,
    bool dayWorkWallEnabled,
  ) {
    final raw = _asListOfMaps(overview['hands']);
    if (raw.isNotEmpty) return raw;
    final src = '${activeLock['source'] ?? 'AUTONOMY_RELAXED'}';
    final isAct = activeLock['active'] == true;
    return [
      {
        'id': 'EMERGENCY_SUSPEND',
        'name': '急事 3 分钟通行证',
        'priority': 'P0',
        'active': isAct && src == 'EMERGENCY_SUSPEND',
        'isWinner': isAct && src == 'EMERGENCY_SUSPEND',
        'remainingMin': 0,
        'canToggle': false,
        'duty': '职责：突发急事 3 分钟全放行（扣每日额度）',
        'summary': '待命 · 急事时申请 3 分钟全放行',
      },
      {
        'id': 'NIGHT_CURFEW',
        'name': '深夜睡眠 & 晚间上限锁',
        'priority': 'P1',
        'active': isAct && src == 'NIGHT_CURFEW',
        'isWinner': isAct && src == 'NIGHT_CURFEW',
        'remainingMin': 0,
        'canToggle': false,
        'duty': '职责：23:00–07:00 睡眠守护 & 晚间超限封禁',
        'summary': '00:30–07:00 深夜守护',
      },
      {
        'id': 'GEMINI_LIVE_CELL',
        'name': 'Gemini 连麦舱',
        'priority': 'P2',
        'active': isAct && src == 'GEMINI_LIVE_CELL',
        'isWinner': isAct && src == 'GEMINI_LIVE_CELL',
        'remainingMin': 0,
        'canToggle': false,
        'duty': '职责：Gemini 连麦共脑白名单收拢',
        'summary': '仅连麦时收拢为连麦白名单',
      },
      {
        'id': 'CONTRACT_OVERDUE_STRICT',
        'name': '验收锁（计划块/封印/主管指令）',
        'priority': 'P3',
        'active': isAct && (src == 'CONTRACT_OVERDUE_STRICT' || src == 'TAKEOVER_STRICT'),
        'isWinner': isAct && (src == 'CONTRACT_OVERDUE_STRICT' || src == 'TAKEOVER_STRICT'),
        'remainingMin': 0,
        'canToggle': false,
        'duty': '职责：计划验收块/封印/主管硬指令逾期 60s 严锁',
        'summary': '含计划块逾期、封印未清与主管硬指令严锁',
      },
      {
        'id': 'BOUT_STRICT',
        'name': '专注严格局（手动/预案）',
        'priority': 'P4',
        'active': isAct && src == 'BOUT_STRICT',
        'isWinner': isAct && src == 'BOUT_STRICT',
        'remainingMin': 0,
        'canToggle': isAct && src == 'BOUT_STRICT',
        'duty': '职责：手动严格局 & 计划块定时自动接管',
        'summary': '手动或预案专注严格局',
      },
      {
        'id': 'DAY_WORK_WALL',
        'name': '工作时段默认防分心锁（Option A）',
        'priority': 'P4',
        'active': dayWorkWallEnabled && !isAct,
        'isWinner': false,
        'remainingMin': 0,
        'canToggle': true,
        'duty': '职责：07:00–23:00 工作窗默认防分心护栏',
        'summary': dayWorkWallEnabled ? '07:00–23:00 自动守护（午休放风）' : '已手动关闭',
      },
    ];
  }


}






