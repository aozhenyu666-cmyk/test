part of 'plan_modality_page.dart';

extension _PlanModalityBuild on _PlanModalityPageState {
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
                    for (final p in (_remotePresets.isNotEmpty ? _remotePresets : _PlanModalityPageState._fallbackPresets)) ...[
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
                onChanged: (v) => _mutate(() => _verifyMode = v),
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
                onChanged: (v) => _mutate(() => _minutes = v),
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
}
