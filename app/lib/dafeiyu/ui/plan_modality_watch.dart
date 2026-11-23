part of 'plan_modality_page.dart';

extension _PlanModalityWatch on _PlanModalityPageState {
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
