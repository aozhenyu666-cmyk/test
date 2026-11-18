import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../plugins/native/native_model.dart' show NativePanel;
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
import 'intercept_page.dart';
import 'plan_modality_page.dart';

enum _DetailTab { queue, protocols, clock }
enum _HorizonTab { today, medium, long }

/// Today · 今日总揽工作台：
/// 首屏收拢「今日简报 + 此刻焦距（默认严格锁） + 今日主线清单（≤3项） + 4 枚核心动作胶囊」，
/// 并将全量队列 / 协议模板 / 内核高精度时钟收纳到右上角「系统明细」二级页签，兼顾简洁专注与零能力丢失。
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
  bool _chartsExpanded = false;
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
    _poller = Timer.periodic(const Duration(seconds: 5), (_) {
      if (mounted) unawaited(_refresh(silent: true));
    });
  }

  @override
  void dispose() {
    _poller?.cancel();
    _goalCtrl.dispose();
    _horizonCtrl.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool silent = false}) async {
    if (!silent && mounted) setState(() => _loading = true);
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

  String _labelOf(String pkg) => _appLabels[pkg] ?? pkg;

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
    await _refresh(silent: true);
    if (!mounted) return;
    final ok = res != null && res['ok'] != false;
    setState(() {
      _busy = false;
      _banner = ok
          ? (toast ?? '${res['say'] ?? res['toast'] ?? res['text'] ?? '已执行'}')
          : '操作未成功：${res?['error'] ?? res?['toast'] ?? res?['say'] ?? '引擎暂未就绪'}';
    });
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
    await _refresh(silent: true);
    if (!mounted) return;
    final ok = res != null && res['ok'] != false;
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
    setState(() {
      _allowDirty = true;
      _selectedAllow
        ..clear()
        ..addAll(picked.map((a) => a.package));
      for (final a in picked) {
        _appLabels[a.package] = a.label;
      }
      _banner = '已更新白名单预设（${picked.length} 个应用）';
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
    ctrl.dispose();
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

  Future<void> _openPromptScriptsSheet() async {
    final remote = _today['promptScripts'];
    final list = <Map<String, String>>[
      if (remote is List && remote.isNotEmpty)
        for (final x in remote)
          if (x is Map)
            {
              'category': '${x['category'] ?? ''}',
              'title': '${x['title'] ?? ''}',
              'prompt': '${x['prompt'] ?? ''}',
            }
      else
        ..._fallbackPromptScripts,
    ];
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('常用 AI 话术剧本表（免自己打字）'),
        content: SizedBox(
          width: 440,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  '点任意一张剧本卡即可直接复制，粘贴发给鱼丸、鲸澜或 Gemini；只需按需替换【方括号】里的课程/金额即可。',
                  style: Theme.of(ctx).textTheme.bodySmall,
                ),
                const SizedBox(height: 10),
                for (final item in list)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: GlassSurface(
                      tier: GlassTier.pill,
                      borderRadius: AppShape.radiusMd,
                      child: ListTile(
                        dense: true,
                        title: Text(item['title'] ?? '', style: Theme.of(ctx).textTheme.titleSmall),
                        subtitle: Text(
                          item['prompt'] ?? '',
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(ctx).textTheme.bodySmall,
                        ),
                        trailing: FilledButton.tonal(
                          onPressed: () async {
                            await Clipboard.setData(ClipboardData(text: item['prompt'] ?? ''));
                            if (ctx.mounted) Navigator.of(ctx).pop();
                            if (mounted) setState(() => _banner = '✓ 已复制「${item['title']}」，直接粘贴发给 AI 即可');
                          },
                          child: const Text('复制'),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('关闭')),
        ],
      ),
    );
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

  Future<void> _openCreateClockDialog() async {
    var kind = 'CYCLE_PHASE';
    final labelCtrl = TextEditingController(text: '专注/休息循环');
    final minCtrl = TextEditingController(text: '25');
    final breakCtrl = TextEditingController(text: '5');
    final cyclesCtrl = TextEditingController(text: '4');
    final cronCtrl = TextEditingController(text: '30 8 * * 1-5');
    final intervalCtrl = TextEditingController(text: '60');
    final urgent = ValueNotifier<bool>(true);

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('新建内核高精度时钟'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: 'CYCLE_PHASE', label: Text('循环')),
                    ButtonSegment(value: 'CRON', label: Text('Cron')),
                    ButtonSegment(value: 'FIXED_RATE', label: Text('高频')),
                    ButtonSegment(value: 'ONE_SHOT', label: Text('闹钟')),
                  ],
                  selected: {kind},
                  onSelectionChanged: (v) => setLocal(() => kind = v.first),
                ),
                const SizedBox(height: 10),
                TextField(controller: labelCtrl, decoration: const InputDecoration(labelText: '任务名称', isDense: true)),
                const SizedBox(height: 8),
                if (kind == 'CYCLE_PHASE') ...[
                  TextField(controller: minCtrl, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: '专注分钟', isDense: true)),
                  const SizedBox(height: 8),
                  TextField(controller: breakCtrl, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: '休息分钟', isDense: true)),
                  const SizedBox(height: 8),
                  TextField(controller: cyclesCtrl, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: '轮数（0=无限循环）', isDense: true)),
                ],
                if (kind == 'CRON')
                  TextField(controller: cronCtrl, decoration: const InputDecoration(labelText: 'Cron（分 时 日 月 周，北京时间）', isDense: true)),
                if (kind == 'FIXED_RATE')
                  TextField(controller: intervalCtrl, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: '固定周期（秒，防漂移锚定）', isDense: true)),
                if (kind == 'ONE_SHOT')
                  TextField(controller: minCtrl, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: '多少分钟后响', isDense: true)),
                const SizedBox(height: 8),
                ValueListenableBuilder<bool>(
                  valueListenable: urgent,
                  builder: (_, v, _) => CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    value: v,
                    onChanged: (nv) => urgent.value = nv ?? true,
                    title: const Text('强闹钟穿透省电唤醒'),
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('创建并锚定')),
          ],
        ),
      ),
    );

    if (confirmed == true) {
      final now = DateTime.now().millisecondsSinceEpoch;
      final label = labelCtrl.text.trim().isEmpty ? '未命名时钟' : labelCtrl.text.trim();
      final Map<String, Object?> spec = switch (kind) {
        'CYCLE_PHASE' => () {
            final fm = (int.tryParse(minCtrl.text) ?? 25).clamp(1, 180);
            final bm = (int.tryParse(breakCtrl.text) ?? 5).clamp(1, 60);
            final cy = (int.tryParse(cyclesCtrl.text) ?? 4).clamp(0, 50);
            return {
              'label': label,
              'kind': 'CYCLE_PHASE',
              'urgent': urgent.value,
              'maxRuns': cy,
              'phases': [
                {'name': '专注', 'durationMs': fm * 60000, 'strictLock': true, 'allowApps': _selectedAllow.toList()},
                {'name': '休息', 'durationMs': bm * 60000, 'strictLock': false, 'allowApps': <String>[]},
              ],
            };
          }(),
        'CRON' => {
            'label': label,
            'kind': 'CRON',
            'cronExpr': cronCtrl.text.trim(),
            'urgent': urgent.value,
          },
        'FIXED_RATE' => () {
            final sec = (int.tryParse(intervalCtrl.text) ?? 60).clamp(5, 86400);
            return {
              'label': label,
              'kind': 'FIXED_RATE',
              'intervalMs': sec * 1000,
              'anchorWall': now,
              'nextFireWall': now + sec * 1000,
              'urgent': urgent.value,
            };
          }(),
        _ => () {
            final m = (int.tryParse(minCtrl.text) ?? 15).clamp(1, 1440);
            return {
              'label': label,
              'kind': 'ONE_SHOT',
              'nextFireWall': now + m * 60000,
              'urgent': urgent.value,
            };
          }(),
      };
      setState(() => _busy = true);
      await _channel.clockUpsert(spec);
      await _refresh(silent: true);
      if (mounted) {
        setState(() {
          _busy = false;
          _banner = '已写入内核时钟并注册系统闹钟：$label';
        });
      }
    }
    labelCtrl.dispose();
    minCtrl.dispose();
    breakCtrl.dispose();
    cyclesCtrl.dispose();
    cronCtrl.dispose();
    intervalCtrl.dispose();
    urgent.dispose();
  }

  Future<void> _openSystemDetails({_DetailTab initialTab = _DetailTab.clock}) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => _SystemDetailsPage(
          initialTab: initialTab,
          queue: _fullQueue,
          protocols: _resolvedProtocols,
          selectedProtocol: _selectedProtocol,
          clocks: _clocks,
          formatCountdown: _formatCountdown,
          onSelectProtocol: (id) => setState(() {
            _selectedProtocol = id;
            _banner = '已选定默认专注协议：$id';
          }),
          onStartQueued: (item) => _sendBoutCommand({
            'action': 'open',
            'id': item['id'],
            'protocolId': item['protocolId'] ?? item['protocol'] ?? 'execute',
            'title': item['title'] ?? '队列开局',
            'strict': _strictMode,
          }),
          onControlClock: _controlClock,
          onCreateClock: _openCreateClockDialog,
        ),
      ),
    );
    if (mounted) unawaited(_refresh(silent: true));
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

  Widget _buildBriefCard(DshColors colors) {
    final brief = _today['brief'];
    final cur = _runningBout;
    final queue = _todayTasks;
    final defaultLine = cur != null
        ? '正在推进「${cur['title'] ?? '专注'}」${cur['strict'] == true ? '（严格锁开启）' : ''}'
        : (queue.isNotEmpty ? '今日主线 ${queue.length} 项待推进 · 下一项「${queue.first['title'] ?? '待开局'}」' : '今日节奏由你定 · 一句话即可开启严格专注');
    final briefLine = brief is Map && '${brief['line'] ?? ''}'.trim().isNotEmpty ? '${brief['line']}' : defaultLine;
    final focusM = brief is Map && brief['focusMin'] is num ? (brief['focusMin'] as num).toInt() : 0;
    final funM = brief is Map && brief['funMin'] is num ? (brief['funMin'] as num).toInt() : 0;
    final autonomy = _today['autonomy'] is Map ? _today['autonomy'] as Map : const {};
    final autoLvl = '${autonomy['level'] ?? 'L1'}';
    final autoName = '${autonomy['name'] ?? '契约自治'}';
    final relaxHint = '${autonomy['autoRewardLabel'] ?? ''}';
    final strictActive = cur != null && (cur['strict'] == true || cur['mode'] == 'strict');

    return OverviewCard(
      margin: const EdgeInsets.only(bottom: 14),
      leading: OverviewGlyph(AppIcons.today, tint: strictActive ? 1 : 0),
      title: const Text('Today · 今日总揽'),
      subtitle: Text(briefLine, maxLines: 2, overflow: TextOverflow.ellipsis),
      trailing: StatusChip(
        tone: strictActive ? StatusTone.error : (cur != null ? StatusTone.ok : StatusTone.idle),
        label: strictActive ? '严格锁中' : (cur != null ? '专注中' : '自律 $autoLvl'),
      ),
      footer: OverviewFooter(
        tone: autonomy['unlockedRelaxation'] == true ? StatusTone.ok : StatusTone.idle,
        text: '专注 ${focusM}m · 娱乐 ${funM}m · $autoLvl $autoName${relaxHint.isNotEmpty ? ' · $relaxHint' : ''}',
      ),
    );
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

  Widget _buildFocusHeroCard(DshColors colors) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final dark = theme.brightness == Brightness.dark;
    final cur = _runningBout;

    if (cur != null) {
      final title = '${cur['title'] ?? cur['task'] ?? cur['protocol'] ?? '专注进行中'}';
      final strict = cur['strict'] == true || cur['mode'] == 'strict';
      final endsAt = cur['until'] as num? ?? cur['endsAt'] as num?;
      final appsRaw = cur['allow'] ?? (cur['apps'] is Map ? (cur['apps'] as Map)['allow'] : null);
      final allow = appsRaw is List ? [for (final a in appsRaw) '$a'] : const <String>[];
      final ringColor = strict ? colors.danger : scheme.primary;
      final progress = _runningProgress(cur);

      return GlassSurface(
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  ProgressHalo(
                    value: progress,
                    size: 46,
                    strokeWidth: 3.5,
                    color: ringColor,
                    child: Icon(AppIcons.running, size: 18, color: ringColor),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                '此刻焦距 · 进行中',
                                style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary),
                              ),
                            ),
                            StatusChip(
                              tone: strict ? StatusTone.error : StatusTone.ok,
                              label: strict ? '严格锁' : '温和局',
                            ),
                          ],
                        ),
                        const SizedBox(height: 3),
                        Text(title, style: theme.textTheme.titleMedium, maxLines: 2, overflow: TextOverflow.ellipsis),
                        const SizedBox(height: 2),
                        Text(
                          '剩余 ${_formatCountdown(endsAt)} · 放行 ${allow.isEmpty ? '仅紧急工具' : allow.map(_labelOf).join('、')}',
                          style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Divider(height: 0.5, thickness: 0.5, color: scheme.outlineVariant.withValues(alpha: 0.45)),
              const SizedBox(height: 10),
              Text(
                '专注可用 App 直达舱（点击直达，无需回桌面）',
                style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
              ),
              const SizedBox(height: 6),
              Wrap(
                spacing: 8,
                runSpacing: 6,
                children: [
                  for (final pkg in allow)
                    CapsuleChoiceChip(
                      icon: AppIcons.play,
                      label: _labelOf(pkg),
                      selected: true,
                      onSelected: () => unawaited(_channel.launchApp(pkg)),
                    ),
                  CapsuleChoiceChip(
                    icon: AppIcons.add,
                    label: '调整白名单',
                    selected: false,
                    onSelected: _busy ? () {} : () => unawaited(_pickRunningAllowApps(allow)),
                  ),
                  CapsuleChoiceChip(
                    icon: AppIcons.unlock,
                    label: '紧急暂停 10m',
                    selected: false,
                    onSelected: _busy
                        ? () {}
                        : () async {
                            await _channel.emergencySuspendStrict(minutes: 10);
                            await _refresh(silent: true);
                            if (mounted) setState(() => _banner = '已紧急挂起严格锁 10 分钟，可安心调整设置或切换应用');
                          },
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(shape: AppShape.card),
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(builder: (_) => const BoutVoicePage()),
                    ),
                    icon: const Icon(AppIcons.voice, size: 18),
                    label: const Text('语音'),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(
                    style: OutlinedButton.styleFrom(shape: AppShape.card),
                    onPressed: _busy
                        ? null
                        : () => unawaited(_sendBoutCommand(
                              {'action': strict ? 'relax' : 'strict'},
                              toast: strict ? '已切为温和守护' : '已升级为严格锁模式',
                            )),
                    child: Text(strict ? '切温和' : '切严格'),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton.icon(
                      style: FilledButton.styleFrom(shape: AppShape.card),
                      onPressed: _busy ? null : () => unawaited(_sendBoutCommand({'action': 'close'}, toast: '已发起收工验收')),
                      icon: const Icon(AppIcons.done, size: 18),
                      label: const Text('收工验收'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    }

    final displayAllow = {
      for (final p in _whitelistPresets) p.package: p.label,
      for (final pkg in _selectedAllow) pkg: _labelOf(pkg),
    };
    final wellColor = dark ? Colors.white.withValues(alpha: 0.05) : Colors.white.withValues(alpha: 0.62);
    final wellBorder = scheme.outlineVariant.withValues(alpha: dark ? 0.25 : 0.45);

    return GlassSurface(
      tier: GlassTier.card,
      borderRadius: AppShape.radiusLg,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                AnimatedStatusDot(tone: _strictMode ? StatusTone.error : StatusTone.ok, size: 8),
                const SizedBox(width: 8),
                Expanded(
                  child: Text('此刻焦距 · 一句话开干', style: theme.textTheme.titleMedium),
                ),
                StatusChip(
                  tone: _strictMode ? StatusTone.error : StatusTone.idle,
                  label: _strictMode ? '默认严格锁' : '温和',
                  onTap: () => setState(() => _strictMode = !_strictMode),
                ),
              ],
            ),
            const SizedBox(height: 12),
            DecoratedBox(
              decoration: ShapeDecoration(
                color: wellColor,
                shape: AppShape.outlined(wellBorder, radius: AppShape.md, width: 0.8),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
                child: TextField(
                  key: const ValueKey('today-goal-input'),
                  controller: _goalCtrl,
                  decoration: const InputDecoration(
                    border: InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    fillColor: Colors.transparent,
                    filled: false,
                    labelText: '一句话定这一局要交付什么',
                    hintText: '例如：写完架构评审 / 过完 30 道题',
                    isDense: true,
                  ),
                  onSubmitted: (_) => _busy ? null : unawaited(_startOneLineFocus()),
                ),
              ),
            ),
            const SizedBox(height: 8),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (final p in _quickFocusPresets) ...[
                    CapsuleChoiceChip(
                      icon: AppIcons.play,
                      label: p.chip,
                      selected: _goalCtrl.text.trim() == p.goal,
                      onSelected: () => setState(() {
                        _goalCtrl.text = p.goal;
                        _selectedProtocol = p.protocolId;
                        _selectedMinutes = p.minutes;
                        _strictMode = p.strict;
                        _allowDirty = true;
                        _selectedAllow
                          ..clear()
                          ..addAll(p.allow);
                        _banner = '已载入预设「${p.chip}」，直接点下方开启即可';
                      }),
                    ),
                    const SizedBox(width: 6),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 10),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (final (id, name, _) in _resolvedProtocols) ...[
                    CapsuleChoiceChip(
                      label: name,
                      selected: _selectedProtocol == id,
                      onSelected: () => setState(() => _selectedProtocol = id),
                    ),
                    const SizedBox(width: 6),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 10),
            SegmentedPill<int>(
              height: 34,
              segments: const [
                PillSegment(25, '25 分钟'),
                PillSegment(45, '45 分钟'),
                PillSegment(60, '60 分钟'),
                PillSegment(90, '90 分钟'),
              ],
              selected: const [25, 45, 60, 90].contains(_selectedMinutes) ? _selectedMinutes : 45,
              onChanged: (m) => setState(() => _selectedMinutes = m),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                for (final e in displayAllow.entries)
                  CapsuleChoiceChip(
                    label: e.value,
                    selected: _selectedAllow.contains(e.key),
                    onSelected: () => setState(() {
                      _allowDirty = true;
                      if (_selectedAllow.contains(e.key)) {
                        _selectedAllow.remove(e.key);
                      } else {
                        _selectedAllow.add(e.key);
                      }
                    }),
                  ),
                CapsuleChoiceChip(
                  key: const ValueKey('today-pick-allow-btn'),
                  icon: AppIcons.add,
                  label: '选更多白名单',
                  selected: false,
                  onSelected: _busy ? () {} : () => unawaited(_pickIdleAllowApps()),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Divider(height: 0.5, thickness: 0.5, color: scheme.outlineVariant.withValues(alpha: 0.4)),
            Material(
              type: MaterialType.transparency,
              child: InkWell(
                borderRadius: AppShape.radiusSm,
                onTap: () => setState(() => _strictMode = !_strictMode),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          '严格锁机（非白名单直接拦截，禁止半途放松）',
                          style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Switch.adaptive(
                        value: _strictMode,
                        onChanged: (v) => setState(() => _strictMode = v),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 4),
            FilledButton.icon(
              key: const ValueKey('today-start-btn'),
              style: FilledButton.styleFrom(
                shape: AppShape.card,
                minimumSize: const Size.fromHeight(44),
              ),
              onPressed: _busy ? null : () => unawaited(_startOneLineFocus()),
              icon: const Icon(AppIcons.play, size: 18),
              label: Text(_strictMode ? '立即开启严格专注' : '开启温和专注'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTodayTasksCard(DshColors colors) {
    final tasks = _todayTasks;
    final totalCount = (_today['allQueueCount'] as num?)?.toInt() ?? _fullQueue.length;

    return ListPageCard(
      title: '今日主线清单（${tasks.length}/3）',
      children: [
        ListPageRow(
          icon: AppIcons.add,
          iconColor: colors.info,
          title: '添加一条今日主线任务',
          subtitle: '继承当前白名单与默认验收标准，也可让鱼丸/鲸澜在对话里代加',
          onTap: _busy ? null : () => unawaited(_addQuickTask()),
        ),
        if (tasks.isEmpty)
          const ListPageRow(
            icon: AppIcons.queued,
            title: '主线清单空着',
            subtitle: '可直接点上方添加，或在「此刻焦距」填一句话直接开干',
          )
        else
          for (var i = 0; i < tasks.length; i++)
            Builder(builder: (_) {
              final item = tasks[i];
              final id = '${item['id'] ?? ''}';
              final title = '${item['title'] ?? item['protocolId'] ?? '待推进'}';
              final budget = item['budget'] ?? item['minutes'] ?? 45;
              return ListPageRow(
                icon: AppIcons.queued,
                title: title,
                subtitle: '$budget 分钟 · ${item['protocolId'] ?? 'execute'}',
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      tooltip: '立刻开干',
                      icon: const Icon(AppIcons.play),
                      onPressed: _busy
                          ? null
                          : () => unawaited(_sendBoutCommand({
                                'action': 'open',
                                if (id.isNotEmpty) 'id': id,
                                'protocolId': item['protocolId'] ?? 'execute',
                                'title': title,
                                'strict': _strictMode,
                              })),
                    ),
                    IconButton(
                      tooltip: '移除',
                      icon: const Icon(AppIcons.delete),
                      onPressed: _busy
                          ? null
                          : () async {
                              await _channel.todayTask(action: 'remove', id: id.isEmpty ? null : id, index: i, title: title);
                              await _refresh(silent: true);
                            },
                    ),
                  ],
                ),
              );
            }),
        if (totalCount > 3)
          ListPageRow(
            icon: AppIcons.allFeatures,
            title: '查看全部排队任务（共 $totalCount 项）',
            onTap: () => unawaited(_openSystemDetails(initialTab: _DetailTab.queue)),
          ),
      ],
    );
  }

  Widget _buildHorizonsAndChartsSection(DshColors colors) {
    final theme = Theme.of(context);
    final horizons = _today['horizons'] is Map ? _today['horizons'] as Map : const {};
    final medList = horizons['medium'] is List
        ? [for (final x in horizons['medium'] as List) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];
    final longList = horizons['long'] is List
        ? [for (final x in horizons['long'] as List) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];

    final charts = _today['charts'] is Map ? _today['charts'] as Map : const {};
    final buckets = charts['buckets'] is Map ? charts['buckets'] as Map : const {};
    final mainlineMin = (buckets['mainlineMin'] as num?)?.toInt() ?? 0;
    final videoMin = (buckets['videoStudyMin'] as num?)?.toInt() ?? 0;
    final tinkerMin = (buckets['toolTinkerMin'] as num?)?.toInt() ?? 0;
    final funMin = (buckets['funMin'] as num?)?.toInt() ?? 0;
    final tinkerWarn = buckets['tinkerWarning'] == true;
    final sumMin = (mainlineMin + videoMin + tinkerMin + funMin).clamp(1, 100000);

    final topApps = charts['topApps'] is List
        ? [for (final x in charts['topApps'] as List) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];
    final completed = charts['completedItems'] is List
        ? [for (final x in charts['completedItems'] as List) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];
    final trend = charts['sevenDayTrend'] is List
        ? [for (final x in charts['sevenDayTrend'] as List) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];

    final activeList = _horizonTab == _HorizonTab.long ? longList : medList;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 1. 自由三层主线切换（今日 / 中期阶段 / 长期主航道）
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('三层主线脉络（自由周期 · 一键拆入今日）', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                SegmentedPill<_HorizonTab>(
                  segments: const [
                    PillSegment(_HorizonTab.today, '今日作战'),
                    PillSegment(_HorizonTab.medium, '中期战役'),
                    PillSegment(_HorizonTab.long, '长期主航道'),
                  ],
                  selected: _horizonTab,
                  onChanged: (next) => setState(() => _horizonTab = next),
                ),
                if (_horizonTab == _HorizonTab.today) ...[
                  const SizedBox(height: 10),
                  _buildTodayTasksCard(colors),
                ] else ...[
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _horizonCtrl,
                          decoration: InputDecoration(
                            hintText: _horizonTab == _HorizonTab.long ? '新增长期方向（如：拿下省考上岸 / 目标就业岗）' : '新增阶段战役（如：资料分析 200 题通关）',
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      FilledButton.tonal(
                        onPressed: _busy
                            ? null
                            : () async {
                                final t = _horizonCtrl.text.trim();
                                if (t.isEmpty) return;
                                _horizonCtrl.clear();
                                await _channel.horizonsOp({
                                  'action': 'upsert',
                                  'horizon': _horizonTab == _HorizonTab.long ? 'long' : 'medium',
                                  'title': t,
                                });
                                await _refresh(silent: true);
                              },
                        child: const Text('新增'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  if (activeList.isEmpty)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      child: Text(
                        _horizonTab == _HorizonTab.long ? '暂无长期方向 · 一句话写下终局目标，防止在工具里空转。' : '暂无中期战役 · 建一个 3~14 天硬项目，再往下拆入今日。',
                        style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                      ),
                    )
                  else
                    for (final item in activeList)
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: GlassSurface(
                          tier: GlassTier.pill,
                          borderRadius: AppShape.radiusMd,
                          child: ListTile(
                            dense: true,
                            title: Text('${item['title']}', style: theme.textTheme.titleSmall),
                            subtitle: Text('${item['spanHint'] ?? ''} · 进度 ${item['doneSteps'] ?? 0}/${item['targetSteps'] ?? 5}'),
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                IconButton(
                                  tooltip: '进度 +1',
                                  icon: const Icon(AppIcons.success, size: 18),
                                  onPressed: () async {
                                    await _channel.horizonsOp({
                                      'action': 'upsert',
                                      'horizon': _horizonTab == _HorizonTab.long ? 'long' : 'medium',
                                      'id': item['id'],
                                      'bumpStep': true,
                                    });
                                    await _refresh(silent: true);
                                  },
                                ),
                                TextButton(
                                  onPressed: () async {
                                    await _channel.horizonsOp({'action': 'push_to_today', 'id': item['id']});
                                    await _refresh(silent: true);
                                    if (mounted) setState(() => _banner = '已将「${item['title']}」推入今日主线队列');
                                  },
                                  child: const Text('推入今日'),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                ],
              ],
            ),
          ),
        ),
        const SizedBox(height: 14),

        // 2. 今日时间投向图表 & 近 7 日主线趋势 & 实绩总览表
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
                    Expanded(child: Text('今日到底干了什么 · 投入与实绩总览', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700))),
                    if (tinkerWarn) const StatusChip(tone: StatusTone.warn, label: '折腾工具偏高'),
                  ],
                ),
                const SizedBox(height: 10),
                ClipRRect(
                  borderRadius: BorderRadius.circular(999),
                  child: SizedBox(
                    height: 10,
                    child: Row(
                      children: [
                        if (mainlineMin > 0) Expanded(flex: mainlineMin, child: Container(color: colors.success)),
                        if (videoMin > 0) Expanded(flex: videoMin, child: Container(color: theme.colorScheme.primary)),
                        if (tinkerMin > 0) Expanded(flex: tinkerMin, child: Container(color: colors.warning)),
                        if (funMin > 0) Expanded(flex: funMin, child: Container(color: colors.danger)),
                        if (mainlineMin + videoMin + tinkerMin + funMin == 0)
                          Expanded(flex: 1, child: Container(color: theme.colorScheme.outlineVariant.withValues(alpha: 0.4))),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 10,
                  runSpacing: 4,
                  children: [
                    Text('● 主线攻坚 ${mainlineMin}m', style: theme.textTheme.bodySmall?.copyWith(color: colors.success)),
                    Text('● 视频学习 ${videoMin}m', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.primary)),
                    Text('● 折腾工具 ${tinkerMin}m', style: theme.textTheme.bodySmall?.copyWith(color: colors.warning)),
                    Text('● 纯娱乐 ${funMin}m（占比 ${((funMin * 100) / sumMin).round()}%）', style: theme.textTheme.bodySmall?.copyWith(color: colors.danger)),
                  ],
                ),
                if (trend.isNotEmpty || completed.isNotEmpty || topApps.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                      style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(0, 30), tapTargetSize: MaterialTapTargetSize.shrinkWrap),
                      onPressed: () => setState(() => _chartsExpanded = !_chartsExpanded),
                      icon: Icon(_chartsExpanded ? AppIcons.collapse : AppIcons.expand, size: 16),
                      label: Text(_chartsExpanded ? '收起详细图表与排行' : '展开明细（7日趋势 · 战果 ${completed.length} · App排行）'),
                    ),
                  ),
                ],
                if (_chartsExpanded && trend.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  Text('近 7 日有效主线专注趋势', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                  const SizedBox(height: 6),
                  SizedBox(
                    height: 64,
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        for (final dayItem in trend)
                          Expanded(
                            child: Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 3),
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.end,
                                children: [
                                  Container(
                                    height: ((((dayItem['focusMinutes'] as num?)?.toDouble() ?? 0) / 180.0).clamp(0.1, 1.0)) * 38,
                                    decoration: BoxDecoration(
                                      color: ((dayItem['deviations'] as num?)?.toInt() ?? 0) > 1 ? colors.warning : theme.colorScheme.primary.withValues(alpha: 0.8),
                                      borderRadius: BorderRadius.circular(5),
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text('${dayItem['day'] ?? ''}', style: const TextStyle(fontSize: 10)),
                                ],
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
                if (_chartsExpanded && completed.isNotEmpty) ...[
                  const Divider(height: 18),
                  Text('今日已完结战果 (${completed.length})', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                  const SizedBox(height: 4),
                  for (final row in completed.take(4))
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      child: Row(
                        children: [
                          Icon(row['passed'] == true ? AppIcons.success : AppIcons.warning, size: 14, color: row['passed'] == true ? colors.success : colors.warning),
                          const SizedBox(width: 6),
                          Expanded(child: Text('${row['title']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall)),
                          if (((row['minutes'] as num?)?.toInt() ?? 0) > 0)
                            Text('${row['minutes']}m', style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary)),
                        ],
                      ),
                    ),
                ],
                if (_chartsExpanded && topApps.isNotEmpty) ...[
                  const Divider(height: 18),
                  Text('今日 App 用时排行', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                  const SizedBox(height: 4),
                  for (final app in topApps.take(4))
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      child: Row(
                        children: [
                          Expanded(child: Text('${app['label'] ?? app['app']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall)),
                          Text('${app['minutes']} 分钟', style: theme.textTheme.bodySmall?.copyWith(color: app['fun'] == true ? colors.danger : colors.textTertiary)),
                        ],
                      ),
                    ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget? _buildActiveContractCard(DshColors colors) {
    final contract = _today['contract'] is Map ? Map<String, dynamic>.from(_today['contract'] as Map) : null;
    if (contract == null) return null;
    final cps = contract['checkpoints'] is List
        ? [for (final x in contract['checkpoints'] as List) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];
    final pending = cps.where((c) => c['status'] != 'passed').toList();
    if (pending.isEmpty) return null;
    final first = pending.first;
    final grace = _today['graceWarning'] is Map ? _today['graceWarning'] as Map : null;
    final escalated = contract['strictEscalated'] == true;
    final theme = Theme.of(context);
    final statusTone = escalated
        ? StatusTone.error
        : (grace != null ? StatusTone.warn : StatusTone.ok);
    final statusLabel = escalated
        ? '逾期严格锁中'
        : (grace != null ? '缓冲剩 ${grace['remainSec']}s' : '契约护航中');

    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: GlassSurface(
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Row(
            children: [
              Icon(
                escalated ? AppIcons.lock : (grace != null ? AppIcons.warning : AppIcons.planMode),
                size: 20,
                color: escalated ? colors.danger : (grace != null ? colors.warning : theme.colorScheme.primary),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            '契约：${contract['title']}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                          ),
                        ),
                        const SizedBox(width: 6),
                        StatusChip(tone: statusTone, label: statusLabel),
                      ],
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '下一验收点：${first['title']} · 到点 ${first['dueAt'] is num && (first['dueAt'] as num) > 0 ? _formatCountdown(first['dueAt'] as num) : ''}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              FilledButton.tonal(
                onPressed: () => unawaited(PlanModalityPage.open(context, channel: _channel, loopChannel: _loop)),
                child: const Text('去验收'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildActionCapsules(DshColors colors) {
    return TileGrid(
      columns: 2,
      spacing: 12,
      padding: EdgeInsets.zero,
      children: [
        FeatureCard(
          key: const ValueKey('today-capsule-plan-modality'),
          icon: AppIcons.planMode,
          tint: 4,
          badge: '契约验收',
          title: 'AI 计划模态',
          subtitle: '查 App · 设验收点 · 1m 转严锁',
          onTap: () => unawaited(PlanModalityPage.open(context, channel: _channel, loopChannel: _loop)),
        ),
        FeatureCard(
          key: const ValueKey('today-capsule-bank'),
          icon: AppIcons.skill,
          tint: 2,
          badge: '10题开5分',
          title: '备考题库中心',
          subtitle: '导入真题 · 刷题解锁时长',
          onTap: () => unawaited(BankManagerPage.open(context, channel: _channel)),
        ),
        FeatureCard(
          key: const ValueKey('today-capsule-dice'),
          icon: AppIcons.overlayBall,
          tint: 5,
          badge: '64dp 立体',
          title: '摇一骰',
          subtitle: '触发 3D 大骰子微干预',
          onTap: _busy ? null : () => unawaited(_rollDice()),
        ),
        FeatureCard(
          key: const ValueKey('today-capsule-voice'),
          icon: AppIcons.voice,
          tint: 3,
          badge: '连麦',
          title: '语音 / Gemini',
          subtitle: '实时语音与姐妹过招',
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => const BoutVoicePage()),
          ),
        ),
        FeatureCard(
          key: const ValueKey('today-capsule-intercept'),
          icon: AppIcons.verified,
          tint: 1,
          badge: '护栏',
          title: '锁机与白名单',
          subtitle: '应用增删 / 恢复严格锁',
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => InterceptPage(loopChannel: _loop)),
          ),
        ),
        FeatureCard(
          key: const ValueKey('today-capsule-hub'),
          icon: AppIcons.settings,
          tint: 6,
          badge: '中枢',
          title: '全部后台',
          subtitle: '20 项中枢与工具箱',
          onTap: () => unawaited(CompanionHubPage.open(context, channel: _channel)),
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
        tooltip: '常用 AI 话术剧本（点即复制）',
        icon: AppIcons.quote,
        onPressed: _busy ? null : () => unawaited(_openPromptScriptsSheet()),
      ),
      GlassIconButton(
        tooltip: '跨 AI 交接上下文预览',
        icon: AppIcons.copy,
        onPressed: _busy ? null : () => unawaited(_copyHandoff()),
      ),
      GlassIconButton(
        tooltip: '系统明细（队列 / 协议库 / 时钟核）',
        icon: AppIcons.allFeatures,
        onPressed: () => unawaited(_openSystemDetails()),
      ),
      if (widget.nativePanel != null)
        GlassIconButton(
          tooltip: '插件原生视图',
          icon: AppIcons.preview,
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => NativePanelPage(panel: widget.nativePanel!, sessionId: widget.sessionId),
            ),
          ),
        ),
      GlassIconButton(
        tooltip: '刷新状态',
        icon: AppIcons.refresh,
        onPressed: () => unawaited(_refresh()),
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

    final sections = <Widget>[
      ?bannerWidget,
      if (_loading)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 40),
          child: Center(child: CircularProgressIndicator()),
        )
      else ...[
        _buildBriefCard(colors),
        _buildFocusHeroCard(colors),
        ?_buildActiveContractCard(colors),
        const SizedBox(height: 16),
        _buildHorizonsAndChartsSection(colors),
        const SizedBox(height: 16),
        _buildActionCapsules(colors),
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
                                  'Mist Glass 主控台 · 一句话聚焦、白名单与日常主线',
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
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 28),
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
      subtitle: const Text('一句开启严格专注，收拢主线、六骰干预与后台总控'),
      actions: headerActions,
      children: sections,
    );
  }
}

/// 二级工程明细页：收纳完整队列、协议库与内核高精度时钟，延续 DSH Go 雾蓝玻璃语言。
class _SystemDetailsPage extends StatefulWidget {
  const _SystemDetailsPage({
    required this.initialTab,
    required this.queue,
    required this.protocols,
    required this.selectedProtocol,
    required this.clocks,
    required this.formatCountdown,
    required this.onSelectProtocol,
    required this.onStartQueued,
    required this.onControlClock,
    required this.onCreateClock,
  });

  final _DetailTab initialTab;
  final List<Map<String, dynamic>> queue;
  final List<(String id, String name, String desc)> protocols;
  final String selectedProtocol;
  final List<Map<String, dynamic>> clocks;
  final String Function(num? wallMs) formatCountdown;
  final ValueChanged<String> onSelectProtocol;
  final Future<void> Function(Map<String, dynamic> item) onStartQueued;
  final Future<void> Function(String id, String op) onControlClock;
  final Future<void> Function() onCreateClock;

  @override
  State<_SystemDetailsPage> createState() => _SystemDetailsPageState();
}

class _SystemDetailsPageState extends State<_SystemDetailsPage> {
  late _DetailTab _tab = widget.initialTab;
  late String _selectedProtocol = widget.selectedProtocol;

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    return AppPage.list(
      title: '系统明细',
      subtitle: const Text('全量排局队列、内置专注协议模板与内核高精度时钟'),
      children: [
        SegmentedPill<_DetailTab>(
          segments: const [
            PillSegment(_DetailTab.queue, '全量队列'),
            PillSegment(_DetailTab.protocols, '协议库'),
            PillSegment(_DetailTab.clock, '时钟核'),
          ],
          selected: _tab,
          onChanged: (next) => setState(() => _tab = next),
        ),
        const SizedBox(height: 14),
          if (_tab == _DetailTab.queue)
            ListPageCard(
              title: '今日排局队列（${widget.queue.length}）',
              children: widget.queue.isEmpty
                  ? const [
                      ListPageRow(
                        icon: AppIcons.queued,
                        title: '队列目前空着',
                        subtitle: '可在 Today 主页直接添加主线任务或一句话开干',
                      ),
                    ]
                  : [
                      for (final item in widget.queue)
                        ListPageRow(
                          icon: AppIcons.queued,
                          title: '${item['title'] ?? item['protocolId'] ?? '待开局'}',
                          subtitle: '${item['budget'] ?? item['minutes'] ?? 45} 分钟 · ${item['protocolId'] ?? 'execute'}',
                          trailing: IconButton(
                            icon: const Icon(AppIcons.play),
                            onPressed: () {
                              Navigator.of(context).pop();
                              unawaited(widget.onStartQueued(item));
                            },
                          ),
                        ),
                    ],
            )
          else if (_tab == _DetailTab.protocols)
            ListPageCard(
              title: '内置协议模板',
              children: [
                for (final (id, name, desc) in widget.protocols)
                  ListPageRow(
                    icon: AppIcons.files,
                    iconColor: _selectedProtocol == id ? colors.success : null,
                    title: name,
                    subtitle: desc,
                    badge: _selectedProtocol == id ? const StatusChip(tone: StatusTone.ok, label: '已选') : null,
                    onTap: () {
                      setState(() => _selectedProtocol = id);
                      widget.onSelectProtocol(id);
                      Navigator.of(context).pop();
                    },
                  ),
              ],
            )
          else
            ListPageCard(
              title: '内核高精度时钟 (${widget.clocks.length})',
              children: [
                ListPageRow(
                  icon: AppIcons.add,
                  iconColor: colors.info,
                  title: '新建循环番茄 / Cron / 秒级高频闹钟',
                  subtitle: '直接锚定系统 AlarmManager，息屏 Doze 可穿刺唤醒',
                  onTap: () => unawaited(widget.onCreateClock()),
                ),
                if (widget.clocks.isEmpty)
                  const ListPageRow(
                    icon: AppIcons.queued,
                    title: '暂无挂起的时钟任务',
                    subtitle: '也可以直接对鱼丸或鲸澜说「设一个 25/5 循环番茄」',
                  ),
                for (final task in widget.clocks)
                  Builder(builder: (_) {
                    final id = '${task['id'] ?? ''}';
                    final label = '${task['label'] ?? id}';
                    final kind = '${task['kind'] ?? 'ONE_SHOT'}';
                    final paused = task['paused'] == true;
                    final enabled = task['enabled'] != false;
                    final nextWall = task['nextFireWall'] as num?;
                    return ListPageRow(
                      icon: paused ? AppIcons.pause : AppIcons.queued,
                      iconColor: paused ? colors.warning : (enabled ? colors.success : colors.textTertiary),
                      title: label,
                      subtitle: '$kind · ${paused ? '已暂停' : widget.formatCountdown(nextWall)}',
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (kind == 'CYCLE_PHASE')
                            IconButton(
                              tooltip: '跳过本段',
                              icon: const Icon(AppIcons.play),
                              onPressed: () => unawaited(widget.onControlClock(id, 'skip')),
                            ),
                          IconButton(
                            tooltip: paused ? '继续' : '暂停',
                            icon: Icon(paused ? AppIcons.play : AppIcons.pause),
                            onPressed: () => unawaited(widget.onControlClock(id, paused ? 'resume' : 'pause')),
                          ),
                          IconButton(
                            tooltip: '删除',
                            icon: const Icon(AppIcons.delete),
                            onPressed: () => unawaited(widget.onControlClock(id, 'delete')),
                          ),
                        ],
                      ),
                    );
                  }),
              ],
            ),
      ],
    );
  }
}
