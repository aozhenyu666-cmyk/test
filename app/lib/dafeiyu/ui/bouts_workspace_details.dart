part of 'bouts_workspace_page.dart';

extension _BoutsWorkspaceDetails on _BoutsWorkspacePageState {
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
        ..._BoutsWorkspacePageState._fallbackPromptScripts,
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
                            if (mounted) _mutate(() => _banner = '✓ 已复制「${item['title']}」，直接粘贴发给 AI 即可');
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
      _mutate(() => _busy = true);
      await _channel.clockUpsert(spec);
      await _refresh(silent: true);
      if (mounted) {
        _mutate(() {
          _busy = false;
          _banner = '已写入内核时钟并注册系统闹钟：$label';
        });
      }
    }
    labelCtrl.dispose();
    minCtrl.dispose();
    disposeAfterRoute(breakCtrl);
    disposeAfterRoute(cyclesCtrl);
    disposeAfterRoute(cronCtrl);
    disposeAfterRoute(intervalCtrl);
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
          onSelectProtocol: (id) => _mutate(() {
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
