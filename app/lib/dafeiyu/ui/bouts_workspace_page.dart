import 'dart:async';

import 'package:flutter/material.dart';

import '../../plugins/native/native_model.dart' show NativePanel;
import '../../ui/plugins/native/native_panel.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'companion_hub_page.dart';

enum _WorkspaceTab { now, queue, protocols, clock }

/// 局总控工作台：雾面玻璃四页签（现在 / 队列 / 协议库 / 时钟与账），直接联动 `/bouts` 与原生 `KernelClockEngine`。
class BoutsWorkspacePage extends StatefulWidget {
  const BoutsWorkspacePage({
    super.key,
    this.asTab = false,
    this.channel,
    this.nativePanel,
    this.sessionId,
  });

  final bool asTab;
  final ProactiveChannel? channel;
  final NativePanel? nativePanel;
  final String? sessionId;

  @override
  State<BoutsWorkspacePage> createState() => _BoutsWorkspacePageState();
}

class _BoutsWorkspacePageState extends State<BoutsWorkspacePage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();

  _WorkspaceTab _tab = _WorkspaceTab.now;
  bool _loading = true;
  bool _busy = false;
  String? _banner;

  Map<String, dynamic> _bouts = const {};
  List<Map<String, dynamic>> _clocks = const [];
  Timer? _poller;

  // 快速开局表单状态
  String _selectedProtocol = 'execute';
  int _selectedMinutes = 45;
  final Set<String> _selectedAllow = {'md.obsidian'};
  bool _strictMode = true;
  final _goalCtrl = TextEditingController();

  static const _quickApps = <(String pkg, String label)>[
    ('md.obsidian', 'Obsidian'),
    ('com.tencent.mm', '微信'),
    ('tv.danmaku.bili', 'B站'),
    ('com.ss.android.ugc.aweme', '抖音'),
    ('com.xingin.xhs', '小红书'),
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
    super.dispose();
  }

  Future<void> _refresh({bool silent = false}) async {
    if (!silent && mounted) setState(() => _loading = true);
    final results = await Future.wait([
      _channel.boutsStatus().catchError((_) => null),
      _channel.clockList().catchError((_) => const <Map<String, dynamic>>[]),
    ]);
    if (!mounted) return;
    setState(() {
      _loading = false;
      final b = results[0];
      if (b is Map<String, dynamic>) {
        _bouts = b;
        if (!silent && _banner != null && _banner!.startsWith('局插件未响应')) _banner = null;
      } else if (!silent) {
        _banner = '局插件未响应，下拉刷新重试或前往诊断页重启引擎';
      }
      final c = results[1];
      if (c is List<Map<String, dynamic>>) _clocks = c;
    });
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
      _banner = ok ? (toast ?? '${res['say'] ?? res['text'] ?? '已执行'}') : '操作未成功：${res?['error'] ?? res?['say'] ?? '引擎暂未就绪'}';
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

  Map<String, dynamic>? get _runningBout {
    final r = _bouts['running'] ?? _bouts['current'];
    if (r is Map<String, dynamic>) return r;
    if (r is Map) return Map<String, dynamic>.from(r);
    return null;
  }

  List<Map<String, dynamic>> get _queueBouts {
    final raw = _bouts['queue'] ?? _bouts['queued'];
    if (raw is! List) return const [];
    return [for (final e in raw) if (e is Map) Map<String, dynamic>.from(e)];
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

  Widget _buildNowTab(DshColors colors) {
    final cur = _runningBout;
    if (cur != null) {
      final title = '${cur['title'] ?? cur['task'] ?? cur['protocol'] ?? '专注进行中'}';
      final strict = cur['strict'] == true || cur['mode'] == 'strict';
      final endsAt = cur['until'] as num? ?? cur['endsAt'] as num?;
      final appsRaw = cur['allow'] ?? (cur['apps'] is Map ? (cur['apps'] as Map)['allow'] : null);
      final allow = appsRaw is List ? [for (final a in appsRaw) '$a'] : const <String>[];
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ListPageCard(
            title: '正在进行的局',
            children: [
              ListPageRow(
                icon: AppIcons.running,
                iconColor: strict ? colors.danger : colors.success,
                title: title,
                subtitle: '剩余 ${_formatCountdown(endsAt)} · 允许：${allow.isEmpty ? '仅紧急工具' : allow.join('、')}',
                badge: StatusChip(
                  tone: strict ? StatusTone.error : StatusTone.ok,
                  label: strict ? '严格锁' : '普通局',
                ),
              ),
              if (!strict)
                ListPageRow(
                  icon: AppIcons.blocked,
                  title: '立即升级为严格铁笼',
                  subtitle: '锁死非白名单应用且隐藏松懈跳过项',
                  onTap: _busy ? null : () => unawaited(_sendBoutCommand({'action': 'strict'}, toast: '已切为严格锁模式')),
                ),
              ListPageRow(
                icon: AppIcons.done,
                iconColor: colors.success,
                title: '收工 · 提交验收',
                subtitle: '通知墙与姐妹按实际产出核算本局',
                onTap: _busy ? null : () => unawaited(_sendBoutCommand({'action': 'close'}, toast: '已发起收工验收')),
              ),
            ],
          ),
        ],
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('当前空闲 · 快速起局', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 10),
                TextField(
                  controller: _goalCtrl,
                  decoration: const InputDecoration(
                    labelText: '本局目标（一句话说明要交付什么）',
                    isDense: true,
                  ),
                ),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    for (final m in const [25, 45, 60, 90])
                      ChoiceChip(
                        label: Text('$m 分钟'),
                        selected: _selectedMinutes == m,
                        onSelected: (_) => setState(() => _selectedMinutes = m),
                      ),
                  ],
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    for (final (pkg, label) in _quickApps)
                      FilterChip(
                        label: Text(label),
                        selected: _selectedAllow.contains(pkg),
                        onSelected: (on) => setState(() {
                          if (on) {
                            _selectedAllow.add(pkg);
                          } else {
                            _selectedAllow.remove(pkg);
                          }
                        }),
                      ),
                  ],
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _strictMode,
                  onChanged: (v) => setState(() => _strictMode = v),
                  title: const Text('严格模式（防数字练习破解、3秒拉回）'),
                ),
                const SizedBox(height: 8),
                FilledButton.icon(
                  onPressed: _busy
                      ? null
                      : () => unawaited(_sendBoutCommand({
                            'action': 'open',
                            'protocolId': _selectedProtocol,
                            'title': _goalCtrl.text.trim().isEmpty ? '专注推进' : _goalCtrl.text.trim(),
                            'minutes': _selectedMinutes,
                            'apps': {'need': _selectedAllow.toList(), 'allow': _selectedAllow.toList()},
                            'strict': _strictMode,
                          }, toast: '已开启 $_selectedMinutes 分钟专注局')),
                  icon: const Icon(AppIcons.play),
                  label: Text('按「$_selectedProtocol」立刻开局'),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildQueueTab(DshColors colors) {
    final q = _queueBouts;
    return ListPageCard(
      title: '今日排局队列（${q.length}）',
      children: q.isEmpty
          ? [
              ListPageRow(
                icon: AppIcons.queued,
                title: '队列目前空着',
                subtitle: '可在「现在」或直接与鲸澜对话排入下一轮',
                onTap: () => setState(() => _tab = _WorkspaceTab.now),
              ),
            ]
          : [
              for (final item in q)
                ListPageRow(
                  icon: AppIcons.queued,
                  title: '${item['title'] ?? item['protocolId'] ?? '待开局'}',
                  subtitle: '${item['minutes'] ?? 45} 分钟 · ${item['protocolId'] ?? 'execute'}',
                  trailing: IconButton(
                    icon: const Icon(AppIcons.play),
                    onPressed: _busy
                        ? null
                        : () => unawaited(_sendBoutCommand({
                              'action': 'open',
                              'id': item['id'],
                              'protocolId': item['protocolId'] ?? item['protocol'] ?? 'execute',
                              'title': item['title'] ?? '队列开局',
                            })),
                  ),
                ),
            ],
    );
  }

  Widget _buildProtocolsTab(DshColors colors) {
    final raw = _bouts['protocols'];
    final items = <(String id, String name, String desc)>[
      if (raw is List && raw.isNotEmpty)
        for (final p in raw)
          if (p is Map)
            ('${p['id'] ?? 'custom'}', '${p['name'] ?? p['id'] ?? '协议'}', '${p['desc'] ?? p['summary'] ?? ''}')
          else ...[]
      else
        ..._fallbackProtocols,
    ];
    return ListPageCard(
      title: '内置协议模板',
      children: [
        for (final (id, name, desc) in items)
          ListPageRow(
            icon: AppIcons.files,
            iconColor: _selectedProtocol == id ? colors.success : null,
            title: name,
            subtitle: desc,
            badge: _selectedProtocol == id ? const StatusChip(tone: StatusTone.ok, label: '已选') : null,
            onTap: () => setState(() {
              _selectedProtocol = id;
              _tab = _WorkspaceTab.now;
            }),
          ),
      ],
    );
  }

  Widget _buildClockTab(DshColors colors) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ListPageCard(
          title: '内核高精度时钟 (${_clocks.length})',
          children: [
            ListPageRow(
              icon: AppIcons.add,
              iconColor: colors.info,
              title: '新建循环番茄 / Cron / 秒级高频闹钟',
              subtitle: '直接锚定系统 AlarmManager，息屏 Doze 可穿刺唤醒',
              onTap: _busy ? null : () => unawaited(_openCreateClockDialog()),
            ),
            if (_clocks.isEmpty)
              const ListPageRow(
                icon: AppIcons.queued,
                title: '暂无挂起的时钟任务',
                subtitle: '也可以直接对鱼丸或鲸澜说「设一个 25/5 循环番茄」',
              ),
            for (final task in _clocks)
              Builder(builder: (context) {
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
                  subtitle: '$kind · ${paused ? '已暂停' : _formatCountdown(nextWall)}',
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (kind == 'CYCLE_PHASE')
                        IconButton(
                          tooltip: '跳过本段',
                          icon: const Icon(AppIcons.play),
                          onPressed: _busy ? null : () => unawaited(_controlClock(id, 'skip')),
                        ),
                      IconButton(
                        tooltip: paused ? '继续' : '暂停',
                        icon: Icon(paused ? AppIcons.play : AppIcons.pause),
                        onPressed: _busy ? null : () => unawaited(_controlClock(id, paused ? 'resume' : 'pause')),
                      ),
                      IconButton(
                        tooltip: '删除',
                        icon: const Icon(AppIcons.delete),
                        onPressed: _busy ? null : () => unawaited(_controlClock(id, 'delete')),
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

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final headerActions = [
      IconButton(
        tooltip: '大肥鱼中枢与诊断',
        icon: const Icon(AppIcons.settings),
        onPressed: () => unawaited(CompanionHubPage.open(context, channel: _channel)),
      ),
      if (widget.nativePanel != null)
        IconButton(
          tooltip: '插件原生视图',
          icon: const Icon(AppIcons.preview),
          onPressed: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => NativePanelPage(panel: widget.nativePanel!, sessionId: widget.sessionId),
            ),
          ),
        ),
    ];

    final body = ListView(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 28),
      children: [
        SegmentedPill<_WorkspaceTab>(
          segments: const [
            PillSegment(_WorkspaceTab.now, '现在'),
            PillSegment(_WorkspaceTab.queue, '队列'),
            PillSegment(_WorkspaceTab.protocols, '协议库'),
            PillSegment(_WorkspaceTab.clock, '时钟与账'),
          ],
          selected: _tab,
          onChanged: (next) => setState(() => _tab = next),
        ),
        const SizedBox(height: 12),
        if (_banner != null) ...[
          Card(
            color: colors.info.withValues(alpha: 0.10),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              child: Text(_banner!, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: colors.info)),
            ),
          ),
          const SizedBox(height: 10),
        ],
        if (_loading)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 32),
            child: Center(child: CircularProgressIndicator()),
          )
        else
          switch (_tab) {
            _WorkspaceTab.now => _buildNowTab(colors),
            _WorkspaceTab.queue => _buildQueueTab(colors),
            _WorkspaceTab.protocols => _buildProtocolsTab(colors),
            _WorkspaceTab.clock => _buildClockTab(colors),
          },
      ],
    );

    if (widget.asTab) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('大肥鱼 · 局与时钟'),
          actions: headerActions,
        ),
        body: RefreshIndicator(onRefresh: _refresh, child: body),
      );
    }

    return AppPage(
      title: '局工作台',
      actions: headerActions,
      slivers: [
        SliverFillRemaining(
          hasScrollBody: true,
          child: RefreshIndicator(onRefresh: _refresh, child: body),
        ),
      ],
    );
  }
}
