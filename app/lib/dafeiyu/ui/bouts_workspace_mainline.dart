part of 'bouts_workspace_page.dart';

extension _BoutsWorkspaceMainline on _BoutsWorkspacePageState {
  List<Widget> _buildTodayTaskRows(DshColors colors) {
    final tasks = _todayTasks;
    final totalCount = (_today['allQueueCount'] as num?)?.toInt() ?? _fullQueue.length;
    return [
      if (tasks.isEmpty)
        const ListPageRow(
          icon: AppIcons.queued,
          title: '主线清单空着',
          subtitle: '点下面添加，或在「此刻焦距」填一句话直接开干',
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
      ListPageRow(
        icon: AppIcons.add,
        iconColor: colors.info,
        title: '添加一条今日主线',
        subtitle: '继承当前白名单与默认验收标准，也可让鱼丸/鲸澜代加',
        onTap: _busy ? null : () => unawaited(_addQuickTask()),
      ),
      if (totalCount > 3)
        ListPageRow(
          icon: AppIcons.allFeatures,
          title: '查看全部排队任务（共 $totalCount 项）',
          onTap: () => unawaited(_openSystemDetails(initialTab: _DetailTab.queue)),
        ),
    ];
  }

  /// 主线：今日 / 中期 / 长期三层放在一张卡里，一个切换条；底部一行进完整的战略主线页。
  Widget _buildMainlineCard(DshColors colors) {
    final theme = Theme.of(context);
    final horizons = _today['horizons'] is Map ? _today['horizons'] as Map : const {};
    final medList = horizons['medium'] is List
        ? [for (final x in horizons['medium'] as List) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];
    final longList = horizons['long'] is List
        ? [for (final x in horizons['long'] as List) if (x is Map) Map<String, dynamic>.from(x)]
        : const <Map<String, dynamic>>[];
    final activeList = _horizonTab == _HorizonTab.long ? longList : medList;
    final horizonKey = _horizonTab == _HorizonTab.long ? 'long' : 'medium';

    return ListPageCard(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Row(
            children: [
              const OverviewGlyph(AppIcons.planMode, tint: 4),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('主线', style: theme.textTheme.titleMedium),
                    const SizedBox(height: 2),
                    Text(
                      '今日 ${_todayTasks.length}/3 · 中期 ${medList.length} · 长期 ${longList.length}',
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
          child: SegmentedPill<_HorizonTab>(
            height: 34,
            segments: const [
              PillSegment(_HorizonTab.today, '今日'),
              PillSegment(_HorizonTab.medium, '中期战役'),
              PillSegment(_HorizonTab.long, '长期方向'),
            ],
            selected: _horizonTab,
            onChanged: (next) => _mutate(() => _horizonTab = next),
          ),
        ),
        if (_horizonTab == _HorizonTab.today)
          ..._buildTodayTaskRows(colors)
        else ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _horizonCtrl,
                    decoration: InputDecoration(
                      hintText: _horizonTab == _HorizonTab.long ? '新增长期方向（如：拿下省考上岸）' : '新增阶段战役（如：资料分析 200 题通关）',
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
                          await _channel.horizonsOp({'action': 'upsert', 'horizon': horizonKey, 'title': t});
                          await _refresh(silent: true);
                        },
                  child: const Text('新增'),
                ),
              ],
            ),
          ),
          if (activeList.isEmpty)
            ListPageRow(
              icon: _horizonTab == _HorizonTab.long ? AppIcons.planMode : AppIcons.queued,
              title: _horizonTab == _HorizonTab.long ? '还没有长期方向' : '还没有中期战役',
              subtitle: _horizonTab == _HorizonTab.long ? '一句话写下终局目标，防止在工具里空转' : '建一个 3~14 天的硬项目，再拆进今日',
              subtitleMaxLines: 2,
            )
          else
            for (final item in activeList)
              ListPageRow(
                key: ValueKey('mainline-horizon-${item['id']}'),
                icon: AppIcons.planMode,
                title: '${item['title']}',
                subtitle: '${item['spanHint'] ?? ''} · 进度 ${item['doneSteps'] ?? 0}/${item['targetSteps'] ?? 5} · 点开看档案',
                onTap: () => unawaited(HorizonDossierPage.open(context, horizonId: '${item['id']}', channel: _channel)),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      tooltip: '进度 +1',
                      icon: const Icon(AppIcons.success, size: 18),
                      onPressed: () async {
                        await _channel.horizonsOp({'action': 'upsert', 'horizon': horizonKey, 'id': item['id'], 'bumpStep': true});
                        await _refresh(silent: true);
                      },
                    ),
                    TextButton(
                      onPressed: () async {
                        await _channel.horizonsOp({'action': 'push_to_today', 'id': item['id']});
                        await _refresh(silent: true);
                        if (mounted) _mutate(() => _banner = '已将「${item['title']}」推入今日主线队列');
                      },
                      child: const Text('推入今日'),
                    ),
                  ],
                ),
              ),
        ],
        _timelineRow(colors),
        ListPageRow(
          icon: AppIcons.planMode,
          iconColor: theme.colorScheme.primary,
          title: '全部计划',
          subtitle: '三层战略 · 封印时间轴 · 全部计划目录',
          value: '${longList.length} 长 · ${medList.length} 中',
          chevron: true,
          onTap: () => unawaited(PlanHubPage.open(context, channel: _channel, loopChannel: _loop)),
        ),
      ],
    );
  }

  /// 主线卡里的一行：今天时间轴上下一个没交的验收点（没有就提示 10:00 前定不打折）。
  Widget _timelineRow(DshColors colors) {
    final s = _seals;
    final today = '${s?['today'] ?? ''}';
    final items = s?['items'] is List
        ? [for (final x in s!['items'] as List) if (x is Map && x['day'] == today) Map<String, dynamic>.from(x)]
        : <Map<String, dynamic>>[];
    items.sort((a, b) => (a['dueMinute'] as num).compareTo(b['dueMinute'] as num));
    final passed = items.where((x) => x['status'] == 'floor' || x['status'] == 'stretch').length;
    final next = items.where((x) => x['status'] == 'open' || x['status'] == 'below').firstOrNull;
    final locking = s?['locking'] is Map;
    final unit = next == null ? '' : (kSealKinds['${next['kind']}']?.$2 ?? '');
    return ListPageRow(
      key: const ValueKey('today-timeline'),
      icon: locking ? AppIcons.lockActive : AppIcons.time,
      iconColor: locking ? colors.danger : colors.info,
      title: next == null
          ? (items.isEmpty ? '今天的时间轴还空着' : '今天的验收点都交了')
          : '下一个：${sealSpan(next)}「${next['title']}」',
      subtitle: next == null
          ? (items.isEmpty ? '10:00 前、开工前定的不打折；到开始时刻自动开局' : '$passed/${items.length} 过了')
          : '${next['actual']}/${next['stretch']}$unit · 保底 ${next['floor']} · 今天 $passed/${items.length} 过了',
      subtitleMaxLines: 2,
      badge: locking ? const StatusChip(tone: StatusTone.error, label: '锁着') : null,
      chevron: true,
      onTap: () async {
        await SealPage.open(context, channel: _channel);
        if (mounted) unawaited(_refresh(silent: true));
      },
    );
  }
}
