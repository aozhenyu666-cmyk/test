part of 'plan_modality_page.dart';

extension _PlanModalityPrepare on _PlanModalityPageState {
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
                  if (mounted) _mutate(() => _tab = _PlanTab.build);
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
                    onSelected: () => _mutate(() {
                      _auditInputCtrl.text = '对方让私聊微信先付定金80元买资料包，说马上涨价且不退不换，刚注册小号';
                    }),
                  ),
                  CapsuleChoiceChip(
                    label: '例：想改写软件配置',
                    selected: false,
                    onSelected: () => _mutate(() {
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
}
