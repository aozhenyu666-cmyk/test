import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../plugins/native/native_model.dart' show NativePanel;
import '../../ui/common/app_sheet.dart' show groupTitleStyle;
import '../../ui/common/capsule_chip.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/progress_halo.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/common/tile_grid.dart';
import '../../ui/plugins/native/native_panel.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/loop_channel.dart';
import '../channels/proactive_state.dart';
import 'app_picker_dialog.dart';
import 'bank_manager_page.dart';
import 'bout_voice_page.dart';
import 'companion_hub_page.dart';
import 'gemini_bridge_page.dart';
import 'horizon_dossier_page.dart';
import 'live_core_atlas_page.dart';
import 'locks_balance_page.dart';
import 'plan_hub_page.dart';
import 'plan_modality_page.dart';
import 'records_page.dart';
import 'strategy_horizons_page.dart';
import 'usage_analytics_page.dart';
import 'task_card.dart';
import 'today_cards.dart';
import '../../ui/common/dispose_after_route.dart';
import 'seal_page.dart';

part 'bouts_workspace_hero.dart';
part 'bouts_workspace_mainline.dart';
part 'bouts_workspace_details.dart';

enum _DetailTab { queue, protocols, clock }
enum _HorizonTab { today, medium, long }

/// Today · 今日总揽工作台：
/// 首屏收拢「今日简报 + 此刻焦距（默认严格锁） + 今日主线清单（≤3项） + 6 枚高频直达入口」，
/// 并将全量队列 / 协议模板 / 内核高精度时钟与剧本/交接收纳到右上角「更多工具」菜单，兼顾清晰直觉与零能力丢失。
class BoutsWorkspacePage extends StatefulWidget {
  const BoutsWorkspacePage({
    super.key,
    this.asTab = false,
    this.channel,
    this.loopChannel,
    this.nativePanel,
    this.sessionId,
  });

  final bool asTab;
  final ProactiveChannel? channel;
  final LoopChannel? loopChannel;
  final NativePanel? nativePanel;
  final String? sessionId;

  @override
  State<BoutsWorkspacePage> createState() => _BoutsWorkspacePageState();
}

class _BoutsWorkspacePageState extends State<BoutsWorkspacePage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final LoopChannel _loop = widget.loopChannel ?? LoopChannel();

  bool _loading = true;
  bool _busy = false;
  String? _banner;

  Map<String, dynamic> _today = const {};
  /// 今天的时间轴验收点（单独在后台读，慢了不挡局页）。
  Map<String, dynamic>? _seals;
  Map<String, dynamic> _bouts = const {};
  List<Map<String, dynamic>> _clocks = const [];
  Timer? _poller;

  // 一句话开干表单状态（默认开启严格锁）
  String _selectedProtocol = 'execute';
  int _selectedMinutes = 45;
  final Set<String> _selectedAllow = {'md.obsidian', 'com.tencent.mm'};
  final Map<String, String> _appLabels = {
    'md.obsidian': 'Obsidian',
    'com.tencent.mm': '微信',
    'com.google.android.apps.bard': 'Gemini',
    'com.tencent.mobileqq': 'QQ',
  };
  bool _allowDirty = false;
  bool _strictMode = true;
  _HorizonTab _horizonTab = _HorizonTab.today;
  final _goalCtrl = TextEditingController();
  final _horizonCtrl = TextEditingController();

  static const _quickFocusPresets = <({
    String chip,
    String goal,
    String protocolId,
    int minutes,
    bool strict,
    List<String> allow,
  })>[
    (
      chip: '考公刷题 60m',
      goal: '考公行测/申论专项刷题与错题复盘',
      protocolId: 'study',
      minutes: 60,
      strict: true,
      allow: ['md.obsidian', 'com.tencent.mm'],
    ),
    (
      chip: '网课提炼 45m',
      goal: '看网课视频并提炼 1 张因果卡',
      protocolId: 'study',
      minutes: 45,
      strict: true,
      allow: ['md.obsidian', 'tv.danmaku.bili', 'com.tencent.mm'],
    ),
    (
      chip: '简历投递 45m',
      goal: '修改简历量化亮点并定向投递',
      protocolId: 'execute',
      minutes: 45,
      strict: true,
      allow: ['md.obsidian', 'com.tencent.mm'],
    ),
    (
      chip: '合法放风 20m',
      goal: '合法休息放风 · 到点自动回位',
      protocolId: 'rest',
      minutes: 25,
      strict: false,
      allow: ['md.obsidian', 'com.tencent.mm'],
    ),
  ];

  static const _fallbackPromptScripts = <Map<String, String>>[
    {
      'category': '每日安排',
      'title': '1. 一早定今日三件事（免手填排局）',
      'prompt': '帮我定今天的主线计划，不用泛泛鼓励：我今天最重要的主航道是【考公/求职】，现有时间约【3】小时。请你直接调用规划工具帮我拆成 ≤3 件今天必打掉的局，配好允许的 App，并定好晚上验收交什么。',
    },
    {
      'category': '计划模态',
      'title': '2. 带目标去视频网站看课（1m 未交转严锁）',
      'prompt': '我要开启「看网课契约」：接下来看【课程名/B站视频】共【45】分钟，用 Obsidian 做提炼。请调 plan_check_apps 检查 App 并用 plan_set_contract 立下视频提炼检查点（到点缓冲 1 分钟未验收自动严格锁）。期间别频繁打扰，看完把提炼内容发你验收。',
    },
    {
      'category': '高强度验收',
      'title': '3. 把压好的视频笔记交给你狠狠追问验收',
      'prompt': '以下是我把刚看的课程/资料压缩后的三段因果提炼：【粘贴压缩内容】。请不要走过场夸我，按统筹规划部+督办审计部模式，针对底层因果和考场/面试反例高强度追问我 2 个核心问题；答到点子上再调 plan_verify_checkpoint(aiConfirmed=true) 通过。',
    },
    {
      'category': '防骗止损',
      'title': '4. 付款 / 买课 / 闲鱼交易前红旗安检',
      'prompt': '我要决定要不要付一笔钱：【描述商品/金额/对方是否私聊先款或不退不换】。请用信息收集部 audit_decision_redflag(kind="redflag") 给我跑红旗一票否决检查，有坑直接喊停！',
    },
    {
      'category': '防偏航',
      'title': '5. 想折腾工具前做反沉没主线审计',
      'prompt': '我突然想花时间折腾【想做的工具/新配置】。先用谋划思考部 audit_decision_redflag(kind="mainline") 对齐我的考公/就业主航道，算算沉没成本——如果是借磨刀逃避正事，直接喊停并指派最小可凑合动作！',
    },
    {
      'category': '题库解锁',
      'title': '6. 把杂乱真题一键洗进锁屏题库（10题开5分）',
      'prompt': '帮我把下面这段题目清洗整理后调 bank_import_questions 导入我的锁屏解锁题库：【粘贴题目与答案】',
    },
    {
      'category': '体系沉淀',
      'title': '7. 睡前 5 分钟压一张因果卡 + 查时间账本',
      'prompt': '请调 audit_decision_redflag(kind="causal")，结合我今天干的事和踩的坑，逼着我压成「触发场景 → 底层机制 → 下次唯一行动」1 张可复用卡片，并对照今日 App 用时分布定明早第一局。',
    },
  ];

  static const _whitelistPresets = <LoopApp>[
    LoopApp('md.obsidian', 'Obsidian'),
    LoopApp('com.tencent.mm', '微信'),
    LoopApp('com.google.android.apps.bard', 'Gemini'),
    LoopApp('com.tencent.mobileqq', 'QQ'),
  ];

  static const _fallbackProtocols = <(String id, String name, String desc)>[
    ('execute', '深度执行', '严格锁定非白名单 App，专心推进既定产物'),
    ('study', '啃书/做题', '只留笔记与资料工具，切出连刷立即拉回'),
    ('design', '方案推演', '配合鲸澜思维推演，允许 Obsidian + 查证工具'),
    ('rest', '合法休息', '计时放风时段，到点自动收紧回工位'),
    ('outing', '出门办事', '仅保留地图/通讯/支付，禁刷信息流'),
  ];

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    unawaited(_loadAppLabels());
    _poller = Timer.periodic(const Duration(seconds: 5), (_) {
      if (mounted) unawaited(_refresh(silent: true));
    });
  }

  /// 每次 setState 都跳一下：推出去的「局详情」页跟着重建，和局页永远是同一份状态。
  final ValueNotifier<int> _tick = ValueNotifier<int>(0);

  @override
  void setState(VoidCallback fn) {
    super.setState(fn);
    _tick.value++;
  }

  void _mutate(VoidCallback fn) {
    if (mounted) setState(fn);
  }

  @override
  void dispose() {
    _tick.dispose();
    _poller?.cancel();
    _goalCtrl.dispose();
    _horizonCtrl.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool silent = false}) async {
    if (!silent && mounted) setState(() => _loading = true);
    unawaited(_channel.sealsOp({'action': 'list'}).then((r) {
      if (mounted && r != null && r['ok'] == true) setState(() => _seals = r);
    }).catchError((_) {}));
    final results = await Future.wait([
      _channel.todayStatus().catchError((_) => null),
      _channel.boutsStatus().catchError((_) => null),
      _channel.clockList().catchError((_) => const <Map<String, dynamic>>[]),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      final t = results[0];
      if (t is Map<String, dynamic>) {
        _today = t;
        final defAllow = t['defaultAllow'];
        if (!_allowDirty && defAllow is List && defAllow.isNotEmpty) {
          _selectedAllow
            ..clear()
            ..addAll(defAllow.map((e) => '$e').where((s) => s.isNotEmpty));
        }
      }
      final b = results[1];
      if (b is Map<String, dynamic>) {
        _bouts = b;
        if (!silent && _banner != null && _banner!.startsWith('工作台暂未响应')) _banner = null;
      } else if (t is! Map<String, dynamic> && !silent) {
        _banner = '工作台暂未响应，下拉刷新重试或点击右上角查看诊断';
      }
      final c = results[2];
      if (c is List<Map<String, dynamic>>) _clocks = c;
    });
  }

  Map<String, dynamic>? get _runningBout {
    final r = _today['running'] ?? _bouts['running'] ?? _bouts['current'];
    if (r is Map<String, dynamic>) return r;
    if (r is Map) return Map<String, dynamic>.from(r);
    return null;
  }

  List<Map<String, dynamic>> get _fullQueue {
    final raw = _bouts['queue'] ?? _bouts['queued'];
    if (raw is! List) return const [];
    return [for (final e in raw) if (e is Map) Map<String, dynamic>.from(e)];
  }

  List<Map<String, dynamic>> get _todayTasks {
    final raw = _today['tasks'];
    if (raw is List && raw.isNotEmpty) {
      return [for (final e in raw.take(3)) if (e is Map) Map<String, dynamic>.from(e)];
    }
    return _fullQueue.take(3).toList();
  }

  /// 手机上读不到名字时的兜底（他常用的这几个），别让包名直接露在界面上。
  static const Map<String, String> _knownLabels = {
    'com.fenbi.android.servant': '粉笔',
    'com.baidu.netdisk': '百度网盘',
    'com.hpbr.bosszhipin': 'BOSS 直聘',
    'com.quark.browser': '夸克',
    'com.android.chrome': 'Chrome',
    'com.openai.chatgpt': 'ChatGPT',
    'com.anthropic.claude': 'Claude',
    'moe.nb4a': 'NekoBox',
  };

  String _labelOf(String pkg) {
    final known = _appLabels[pkg] ?? _knownLabels[pkg];
    if (known != null) return known;
    for (final p in _whitelistPresets) {
      if (p.package == pkg) return p.label;
    }
    return pkg;
  }

  /// 进页面读一次手机上装的 App 名字，放行列表就显示「粉笔」而不是包名。
  Future<void> _loadAppLabels() async {
    final apps = await _loop.apps();
    if (!mounted || apps.isEmpty) return;
    setState(() {
      for (final a in apps) {
        if (a.label.trim().isNotEmpty && a.label != a.package) _appLabels.putIfAbsent(a.package, () => a.label);
      }
    });
  }

  String _formatCountdown(num? wallMs) {
    if (wallMs == null || wallMs <= 0) return '--';
    final diffSec = ((wallMs.toInt() - DateTime.now().millisecondsSinceEpoch) / 1000).round();
    if (diffSec <= 0) return '即将触发';
    if (diffSec < 60) return '${diffSec}s 后';
    final mins = diffSec ~/ 60;
    if (mins < 60) return '${mins}m 后';
    return '${mins ~/ 60}h${mins % 60}m 后';
  }

  Future<void> _sendBoutCommand(Map<String, Object?> body, {String? toast}) async {
    setState(() {
      _busy = true;
      _banner = null;
    });
    final res = await _channel.boutsCommand(body);
    final ok = res != null && res['ok'] != false;
    if (ok) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh(silent: true);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = ok
          ? (toast ?? '${res['say'] ?? res['toast'] ?? res['text'] ?? '已执行'}')
          : '操作未成功：${res?['error'] ?? res?['toast'] ?? res?['say'] ?? '引擎暂未就绪'}';
    });
  }

  Future<int?> _pickCustomMinutesDialog({
    required String title,
    int initialMinutes = 45,
    bool showPresets = false,
  }) async {
    final ctrl = TextEditingController(text: '$initialMinutes');
    var selected = initialMinutes.clamp(10, 360);
    String? error;
    final picked = await showDialog<int>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          key: const ValueKey('bout-duration-dialog'),
          title: Text(title),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (showPresets) ...[
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    for (final m in const [25, 45, 90])
                      ChoiceChip(
                        label: Text('${m}m'),
                        selected: selected == m,
                        onSelected: (_) {
                          ctrl.text = '$m';
                          setLocal(() {
                            selected = m;
                            error = null;
                          });
                        },
                      ),
                  ],
                ),
                const SizedBox(height: 12),
              ],
              TextField(
                key: const ValueKey('bout-duration-custom-input'),
                controller: ctrl,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: const InputDecoration(
                  labelText: '自定义时长（10–360 分钟）',
                  suffixText: '分钟',
                ),
                onChanged: (raw) {
                  final v = int.tryParse(raw.trim());
                  if (v != null) selected = v;
                  if (error != null) setLocal(() => error = null);
                },
              ),
              if (error != null) ...[
                const SizedBox(height: 6),
                Text(error!, style: TextStyle(color: Theme.of(ctx).colorScheme.error, fontSize: 12)),
              ],
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('取消')),
            FilledButton(
              key: const ValueKey('bout-duration-confirm'),
              onPressed: () {
                final v = int.tryParse(ctrl.text.trim()) ?? selected;
                if (v < 10 || v > 360) {
                  setLocal(() => error = '时长需在 10–360 分钟之间');
                  return;
                }
                Navigator.of(ctx).pop(v);
              },
              child: const Text('确定'),
            ),
          ],
        ),
      ),
    );
    disposeAfterRoute(ctrl);
    return picked;
  }

  Future<void> _upgradeToStrictWithDuration() async {
    final mins = await _pickCustomMinutesDialog(
      title: '选择严格锁时长',
      initialMinutes: _selectedMinutes,
      showPresets: true,
    );
    if (mins == null || !mounted) return;
    setState(() => _selectedMinutes = mins);
    await _sendBoutCommand(
      {'action': 'strict', 'minutes': mins},
      toast: '已升级为严格锁模式（$mins 分钟）',
    );
  }

  Future<void> _startOneLineFocus() async {
    final goal = _goalCtrl.text.trim().isEmpty ? '深度专注' : _goalCtrl.text.trim();
    final allow = _selectedAllow.isEmpty ? const ['md.obsidian', 'com.tencent.mm'] : _selectedAllow.toList();
    setState(() {
      _busy = true;
      _banner = null;
    });
    Map<String, dynamic>? res = await _channel.todayStart(
      goal: goal,
      minutes: _selectedMinutes,
      strict: _strictMode,
      allow: allow,
      protocolId: _selectedProtocol,
    );
    // 兼容旧测试替身或未覆盖 todayStart 的通道实现：回落到 /bouts open
    res ??= await _channel.boutsCommand({
      'action': 'open',
      'protocolId': _selectedProtocol,
      'title': goal,
      'minutes': _selectedMinutes,
      'apps': {'need': allow, 'allow': allow},
      'strict': _strictMode,
    });
    final ok = res != null && res['ok'] != false;
    if (ok) {
      unawaited(_channel.tickNow().catchError((_) => false));
    }
    await _refresh(silent: true);
    if (!mounted) return;
    if (ok) _goalCtrl.clear();
    setState(() {
      _busy = false;
      _banner = ok
          ? '${_strictMode ? '已开启严格专注' : '已开启专注'}「$goal」· $_selectedMinutes 分钟'
          : '开启失败：${res?['toast'] ?? res?['error'] ?? '引擎暂未就绪'}';
    });
  }

  Future<void> _pickIdleAllowApps() async {
    final recentRaw = _today['recentApps'];
    final recent = recentRaw is List ? [for (final r in recentRaw) '$r'] : const <String>[];
    final picked = await showInstalledAppsPicker(
      context,
      title: '选择专注期间放行的白名单 App',
      loop: _loop,
      presets: _whitelistPresets,
      presetsLabel: '常用工具 / 沟通应用快捷勾选',
      initialSelected: _selectedAllow.toList(),
      recentPackages: recent,
      allowEmpty: false,
      emptyError: '严格模式下至少保留一个放行应用，避免误锁全机',
      confirmLabel: '确认',
    );
    if (picked == null || !mounted) return;
    final pkgs = picked.map((a) => a.package).toList();
    setState(() {
      _busy = true;
      _allowDirty = true;
      _selectedAllow
        ..clear()
        ..addAll(pkgs);
      for (final a in picked) {
        _appLabels[a.package] = a.label;
      }
    });
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
        ? [for (final g in rejectedRaw) if ('$g'.trim().isNotEmpty) _labelOf('$g'.trim())]
        : const <String>[];
    final toast = '${res?['toast'] ?? ''}'.trim();
    setState(() {
      _busy = false;
      if (serverAllow.isNotEmpty) {
        _selectedAllow
          ..clear()
          ..addAll(serverAllow);
      }
      if (res == null) {
        _banner = '已更新本局白名单（${pkgs.length} 个应用），但引擎未应答，暂未持久化默认白名单';
      } else if (res['ok'] == false) {
        final err = toast.isNotEmpty ? toast : '${res['error'] ?? '引擎暂未接受该白名单'}';
        _banner = rejected.isNotEmpty
            ? '白名单未保存：$err（已拦截纯游戏：${rejected.join('、')}）'
            : '白名单未保存：$err';
      } else {
        final baseMsg = toast.isNotEmpty ? toast : '已持久化手机默认专注白名单（${_selectedAllow.length} 个应用）';
        _banner = rejected.isNotEmpty
            ? '$baseMsg · 已拦截纯游戏：${rejected.join('、')}'
            : baseMsg;
      }
    });
  }

  Future<void> _pickRunningAllowApps(List<String> currentAllow) async {
    final picked = await showInstalledAppsPicker(
      context,
      title: '动态调整本局放行白名单',
      loop: _loop,
      presets: _whitelistPresets,
      presetsLabel: '常用工具 / 沟通应用快捷勾选',
      initialSelected: currentAllow,
      allowEmpty: false,
      emptyError: '本局白名单至少保留一个应用',
      confirmLabel: '确认',
    );
    if (picked == null || !mounted) return;
    for (final a in picked) {
      _appLabels[a.package] = a.label;
    }
    final pkgs = picked.map((e) => e.package).toList();
    setState(() {
      _allowDirty = true;
      _selectedAllow
        ..clear()
        ..addAll(pkgs);
    });
    await _sendBoutCommand(
      {'action': 'allow', 'allow': pkgs},
      toast: '本局白名单已更新为 ${picked.length} 个应用',
    );
  }

  Future<void> _addQuickTask() async {
    final ctrl = TextEditingController();
    var mins = 45;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('加入今日主线清单'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: ctrl,
                autofocus: true,
                decoration: const InputDecoration(
                  labelText: '要推进什么？（一句话）',
                  hintText: '例如：写完架构评审文档',
                ),
                onSubmitted: (_) => Navigator.of(ctx).pop(true),
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                children: [
                  for (final m in const [25, 45, 60, 90])
                    ChoiceChip(
                      label: Text('${m}m'),
                      selected: mins == m,
                      onSelected: (_) => setLocal(() => mins = m),
                    ),
                ],
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('加入主线')),
          ],
        ),
      ),
    );
    final title = ctrl.text.trim();
    disposeAfterRoute(ctrl);
    if (confirmed != true || title.isEmpty || !mounted) return;
    setState(() => _busy = true);
    final allow = _selectedAllow.isEmpty ? const ['md.obsidian', 'com.tencent.mm'] : _selectedAllow.toList();
    final res = await _channel.todayTask(action: 'add', title: title, minutes: mins, allow: allow);
    await _refresh(silent: true);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res != null && res['ok'] == true ? '${res['text'] ?? '已加入主线：$title'}' : '添加失败：${res?['text'] ?? '引擎暂未就绪'}';
    });
  }


  Future<void> _copyHandoff() async {
    setState(() => _busy = true);
    final res = await _channel.handoffPacket();
    if (!mounted) return;
    final md = '${res?['markdown'] ?? ''}'.trim();
    setState(() => _busy = false);
    if (md.isEmpty) {
      setState(() => _banner = '交接上下文暂未生成，请确认引擎已就绪');
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('跨 AI 交接上下文预览'),
        content: SizedBox(
          width: 420,
          child: SingleChildScrollView(child: SelectableText(md, style: Theme.of(ctx).textTheme.bodySmall)),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('关闭')),
          FilledButton.icon(
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: md));
              if (ctx.mounted) Navigator.of(ctx).pop();
              if (mounted) setState(() => _banner = '✓ 已复制跨 AI 交接上下文到剪贴板');
            },
            icon: const Icon(AppIcons.copy, size: 16),
            label: const Text('一键复制'),
          ),
        ],
      ),
    );
  }

  Future<void> _rollDice() async {
    final res = await _channel.rollDiceNow();
    if (!mounted) return;
    final d = res?['dice'];
    final n = res?['name'] ?? '';
    final a = res?['ask'] ?? '';
    setState(() {
      _banner = d != null ? '🎲 骰$d · $n：$a' : '已唤醒六骰思维微干预浮窗';
    });
  }

  Future<void> _controlClock(String id, String op) async {
    setState(() => _busy = true);
    await _channel.clockControl(id, op);
    await _refresh(silent: true);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = '时钟任务已更新（$op）';
    });
  }



  List<(String id, String name, String desc)> get _resolvedProtocols {
    final raw = _bouts['protocols'];
    if (raw is List && raw.isNotEmpty) {
      return [
        for (final p in raw)
          if (p is Map)
            ('${p['id'] ?? 'custom'}', '${p['name'] ?? p['id'] ?? '协议'}', '${p['desc'] ?? p['summary'] ?? ''}')
      ];
    }
    return _fallbackProtocols;
  }

  String _resolveBoutSealTitle(Map<String, dynamic> cur) {
    final direct = '${cur['sealTitle'] ?? ''}'.trim();
    if (direct.isNotEmpty) return direct;
    final sealId = '${cur['sealId'] ?? ''}'.trim();
    if (sealId.isEmpty) return '';
    final items = _seals?['items'];
    if (items is List) {
      for (final raw in items) {
        if (raw is Map && '${raw['id'] ?? ''}'.trim() == sealId) {
          final title = '${raw['title'] ?? ''}'.trim();
          if (title.isNotEmpty) return title;
        }
      }
    }
    return '';
  }

  double? _runningProgress(Map<String, dynamic> cur) {
    final endsAt = cur['until'] as num? ?? cur['endsAt'] as num?;
    if (endsAt == null) return null;
    final now = DateTime.now().millisecondsSinceEpoch;
    final left = (endsAt.toDouble() - now).clamp(0.0, 24 * 3600000.0);
    final startedAt = cur['startedAt'] as num? ?? cur['at'] as num?;
    if (startedAt != null && endsAt > startedAt) {
      final total = (endsAt.toDouble() - startedAt.toDouble()).clamp(60000.0, 24 * 3600000.0);
      return (1.0 - left / total).clamp(0.05, 1.0);
    }
    final budgetMin = (cur['minutes'] as num? ?? cur['budget'] as num?)?.toDouble();
    final total = ((budgetMin ?? _selectedMinutes.toDouble()) * 60000.0).clamp(60000.0, 24 * 3600000.0);
    return (1.0 - (left / total).clamp(0.0, 1.0)).clamp(0.05, 1.0);
  }











  Widget _buildActionCapsules(DshColors colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TileGrid(
          columns: 2,
          spacing: 12,
          padding: const EdgeInsets.symmetric(horizontal: 16),
          children: [
            FeatureCard(
              key: const ValueKey('today-capsule-plan'),
              icon: AppIcons.planMode,
              tint: 4,
              badge: '三态',
              title: '计划与托管',
              subtitle: '准备 · 时间盒 · 托管冲刺',
              onTap: () => unawaited(PlanModalityPage.open(context, channel: _channel, loopChannel: _loop)),
            ),
            FeatureCard(
              key: const ValueKey('today-capsule-locks'),
              icon: AppIcons.balance,
              tint: 5,
              badge: '6只手',
              title: '锁与余额',
              subtitle: '实时锁源 · 验收锁 · 余额',
              onTap: () => unawaited(LocksBalancePage.open(context, channel: _channel)),
            ),
            FeatureCard(
              key: const ValueKey('today-capsule-bank'),
              icon: AppIcons.skill,
              tint: 2,
              badge: '10题5分',
              title: '题库与复习',
              subtitle: '刷题挣余额 · FSRS 复习',
              onTap: () => unawaited(BankManagerPage.open(context, channel: _channel)),
            ),
            FeatureCard(
              key: const ValueKey('today-capsule-analytics'),
              icon: AppIcons.chart,
              tint: 1,
              badge: '趋势',
              title: '统计与复盘',
              subtitle: '24h 切片 · 90 天 · AI 总报',
              onTap: () => unawaited(UsageAnalyticsPage.open(context, channel: _channel)),
            ),
            FeatureCard(
              key: const ValueKey('today-capsule-bridge'),
              icon: AppIcons.link,
              tint: 3,
              badge: 'MCP',
              title: 'Gemini 桥',
              subtitle: '一键配桥 · 4 场景直呼',
              onTap: () => unawaited(
                Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => GeminiBridgePage(channel: _channel)),
                ),
              ),
            ),
            FeatureCard(
              key: const ValueKey('today-capsule-hub'),
              icon: AppIcons.settings,
              tint: 6,
              badge: '四组',
              title: '中枢设置',
              subtitle: '两条鱼 · 锁规则 · 数据 · 诊断',
              onTap: () => unawaited(CompanionHubPage.open(context, channel: _channel)),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Padding(
          key: const ValueKey('today-secondary-strip'),
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              ActionChip(
                key: const ValueKey('today-capsule-dice'),
                avatar: const Icon(AppIcons.overlayBall, size: 15),
                label: const Text('摇一骰'),
                onPressed: _busy ? null : () => unawaited(_rollDice()),
              ),
              ActionChip(
                key: const ValueKey('today-capsule-records'),
                avatar: const Icon(AppIcons.history, size: 15),
                label: const Text('执行记录'),
                onPressed: () => unawaited(RecordsPage.open(context)),
              ),
              ActionChip(
                key: const ValueKey('today-capsule-strategy'),
                avatar: const Icon(AppIcons.planMode, size: 15),
                label: const Text('三层战略'),
                onPressed: () => unawaited(StrategyHorizonsPage.open(context, channel: _channel)),
              ),
              ActionChip(
                key: const ValueKey('today-capsule-atlas'),
                avatar: const Icon(AppIcons.diagram, size: 15),
                label: const Text('图谱与Live'),
                onPressed: () => unawaited(LiveCoreAtlasPage.open(context, channel: _channel, loopChannel: _loop)),
              ),
            ],
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final theme = Theme.of(context);
    final topInset = MediaQuery.paddingOf(context).top;
    final headerActions = [
      GlassIconButton(
        tooltip: '刷新状态',
        icon: AppIcons.refresh,
        onPressed: () => unawaited(_refresh()),
      ),
      PopupMenuButton<String>(
        key: const ValueKey('today-more-tools-btn'),
        tooltip: '更多工具（剧本 / 交接 / 系统明细）',
        icon: const Icon(AppIcons.more),
        onSelected: (value) {
          switch (value) {
            case 'scripts':
              if (!_busy) unawaited(_openPromptScriptsSheet());
            case 'handoff':
              if (!_busy) unawaited(_copyHandoff());
            case 'details':
              unawaited(_openSystemDetails());
            case 'native':
              if (widget.nativePanel != null) {
                unawaited(
                  Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => NativePanelPage(panel: widget.nativePanel!, sessionId: widget.sessionId),
                    ),
                  ),
                );
              }
            case 'plan_hub':
              unawaited(PlanHubPage.open(context, channel: _channel, loopChannel: _loop));
            case 'atlas':
              unawaited(LiveCoreAtlasPage.open(context, channel: _channel, loopChannel: _loop));
          }
        },
        itemBuilder: (ctx) => [
          const PopupMenuItem(value: 'scripts', child: Text('常用 AI 话术剧本（点即复制）')),
          const PopupMenuItem(value: 'handoff', child: Text('跨 AI 交接上下文预览')),
          const PopupMenuItem(value: 'details', child: Text('系统明细（队列 / 协议库 / 时钟核）')),
          const PopupMenuItem(value: 'plan_hub', child: Text('全部计划目录（长线 / 封印 / 排程）')),
          const PopupMenuItem(value: 'atlas', child: Text('功能图谱与 Live 记录')),
          if (widget.nativePanel != null) const PopupMenuItem(value: 'native', child: Text('插件原生视图')),
        ],
      ),
    ];

    final bannerWidget = _banner == null
        ? null
        : Padding(
            padding: const EdgeInsets.only(bottom: 12),
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
                      child: Text(
                        _banner!,
                        style: theme.textTheme.bodySmall?.copyWith(color: colors.info),
                      ),
                    ),
                    GestureDetector(
                      onTap: () => setState(() => _banner = null),
                      child: Icon(AppIcons.close, size: 16, color: colors.textTertiary),
                    ),
                  ],
                ),
              ),
            ),
          );

    final timeLedger = TimeLedgerView.fromWire(_today['timeLedger']);
    final verdict = DailyVerdictView.fromWire(
      _today['verdict'],
      scoreRaw: _today['score'],
      scoreLabelRaw: _today['scoreLabel'],
    );

    Widget inset(Widget child, {double bottom = 0}) =>
        Padding(padding: EdgeInsets.fromLTRB(16, 0, 16, bottom), child: child);

    final sections = <Widget>[
      if (bannerWidget != null) inset(bannerWidget),
      LockReadinessCard(channel: _channel),
      inset(TaskCardPanel(channel: _channel)),
      if (_loading)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 40),
          child: Center(child: CircularProgressIndicator()),
        )
      else ...[
        inset(_buildFocusHeroCard(colors), bottom: 16),
        ?_buildActiveContractCard(colors),
        TodayUsageCard(
          today: _today,
          verdict: verdict,
          ledger: timeLedger,
          onTap: () => unawaited(UsageAnalyticsPage.open(context, channel: _channel)),
        ),
        _buildMainlineCard(colors),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 4, 24, 8),
          child: Semantics(header: true, child: Text('工具与后台', style: groupTitleStyle(context))),
        ),
        _buildActionCapsules(colors),
        const SizedBox(height: 16),
      ],
    ];

    if (widget.asTab) {
      return AnnotatedRegion<SystemUiOverlayStyle>(
        value: dshSystemOverlay(theme.brightness),
        child: AppBackdrop(
          child: Scaffold(
            backgroundColor: Colors.transparent,
            body: RefreshIndicator(
              onRefresh: _refresh,
              child: CustomScrollView(
                physics: AppMotion.scrollPhysics(context),
                slivers: [
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: EdgeInsets.fromLTRB(20, topInset + 14, 16, 12),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Today · 今日总揽', style: theme.textTheme.headlineMedium),
                                const SizedBox(height: 2),
                                Text(
                                  '一句话开局 · 使用情况 · 主线',
                                  style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                                ),
                              ],
                            ),
                          ),
                          Row(mainAxisSize: MainAxisSize.min, spacing: 8, children: headerActions),
                        ],
                      ),
                    ),
                  ),
                  SliverPadding(
                    padding: const EdgeInsets.fromLTRB(0, 4, 0, 28),
                    sliver: SliverList.list(children: sections),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    return AppPage.list(
      title: 'Today · 今日总揽',
      subtitle: const Text('一句话开局 · 使用情况 · 主线'),
      actions: headerActions,
      children: sections,
    );
  }
}


