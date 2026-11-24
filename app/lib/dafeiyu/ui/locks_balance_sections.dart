part of 'locks_balance_page.dart';

extension _LocksBalanceSections on _LocksBalancePageState {
  Widget _buildTopWhitelistAndCapsuleCard(
    BuildContext context, {
    required ThemeData theme,
    required DshColors colors,
    required ColorScheme scheme,
    required Map<String, dynamic> overview,
  }) {
    final defaultAllow = _resolveDefaultAllow(overview);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
      child: GlassSurface(
        key: const ValueKey('locks-reverse-capsule-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(AppIcons.appRules, size: 18, color: scheme.primary),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '反向白名单单开舱（填平发呆洼地）',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleSmall,
                    ),
                  ),
                  TextButton.icon(
                    key: const ValueKey('locks-default-allow-btn'),
                    style: TextButton.styleFrom(
                      visualDensity: VisualDensity.compact,
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    ),
                    onPressed: _busy ? null : () => unawaited(_handleEditDefaultAllow(overview)),
                    icon: const Icon(AppIcons.verified, size: 15),
                    label: Text('调整手机默认白名单（${defaultAllow.length}）'),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  FilledButton.tonalIcon(
                    key: const ValueKey('locks-capsule-gemini-btn'),
                    style: FilledButton.styleFrom(
                      shape: AppShape.card,
                      visualDensity: VisualDensity.compact,
                    ),
                    onPressed: _busy ? null : () => unawaited(_handleReverseGeminiLive()),
                    icon: const Icon(AppIcons.askAi, size: 15),
                    label: const Text('仅留 Gemini 共脑 15m'),
                  ),
                  FilledButton.tonalIcon(
                    key: const ValueKey('locks-capsule-quiz-btn'),
                    style: FilledButton.styleFrom(
                      shape: AppShape.card,
                      visualDensity: VisualDensity.compact,
                    ),
                    onPressed: _busy ? null : () => unawaited(_handleReverseQuizCapsule()),
                    icon: const Icon(AppIcons.skill, size: 15),
                    label: const Text('仅留刷题 App 20m'),
                  ),
                  OutlinedButton.icon(
                    key: const ValueKey('locks-capsule-commute-btn'),
                    style: OutlinedButton.styleFrom(
                      shape: AppShape.card,
                      visualDensity: VisualDensity.compact,
                    ),
                    onPressed: _busy ? null : () => unawaited(_handleCommuteAudioSpark()),
                    icon: const Icon(AppIcons.voice, size: 15),
                    label: const Text('骑车/做饭 · 语音抽背'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildVerifyCenterCard(
    BuildContext context, {
    required ThemeData theme,
    required DshColors colors,
    required ColorScheme scheme,
    required Map<String, dynamic> overview,
  }) {
    final center = _asMap(overview['verifyLockCenter']);
    final totalVerify = _asInt(center['totalVerify'], 0);
    final passedVerify = _asInt(center['passedVerify'], 0);
    final overdueCount = _asInt(center['overdueCount'], 0);
    final pendingReviewCount = _asInt(center['pendingReviewCount'], 0);
    final clearedToday = center['clearedToday'] == true || overview['verifyClearedToday'] == true;
    final custody = _asMap(center['custody']);
    final custodyActive = custody['active'] == true;
    final adaptiveSplits = _asInt(center['adaptiveSplitsToday'], 0);

    final overdueBlocks = _asListOfMaps(center['overdueBlocks']);
    final pendingBlocks = _asListOfMaps(center['pendingReviewBlocks']);
    final activeVerify = _asMap(center['activeVerifyBlock']);
    final upcomingBlocks = _asListOfMaps(center['upcomingVerifyBlocks']);
    final hasDetails = overdueBlocks.isNotEmpty ||
        pendingBlocks.isNotEmpty ||
        activeVerify.isNotEmpty ||
        upcomingBlocks.isNotEmpty ||
        totalVerify > 0 ||
        custodyActive ||
        adaptiveSplits > 0;

    if (!hasDetails) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: GlassSurface(
          key: const ValueKey('locks-verify-center-card'),
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            child: Row(
              children: [
                Icon(AppIcons.verified, size: 18, color: colors.textTertiary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '验收锁中枢 · 今日暂无验收块（可去计划模态设验收块）',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                  ),
                ),
                TextButton(
                  onPressed: () => unawaited(PlanModalityPage.open(context, channel: _channel)),
                  child: const Text('计划模态'),
                ),
              ],
            ),
          ),
        ),
      );
    }

    final statusTone = overdueCount > 0
        ? StatusTone.error
        : (clearedToday ? StatusTone.ok : StatusTone.idle);
    final statusLabel = overdueCount > 0
        ? '逾期严锁 $overdueCount 项'
        : (clearedToday ? '今日全部通关' : '已过 $passedVerify/$totalVerify');

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: GlassSurface(
        key: const ValueKey('locks-verify-center-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(AppIcons.verified, size: 18, color: overdueCount > 0 ? colors.danger : scheme.primary),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '验收锁中枢${pendingReviewCount > 0 ? ' · 待终审 $pendingReviewCount' : ''}',
                      style: theme.textTheme.titleSmall,
                    ),
                  ),
                  StatusChip(tone: statusTone, label: statusLabel),
                  const SizedBox(width: 4),
                  TextButton(
                    onPressed: () => unawaited(PlanModalityPage.open(context, channel: _channel)),
                    child: const Text('计划模态'),
                  ),
                ],
              ),
              if (custodyActive || adaptiveSplits > 0) ...[
                const SizedBox(height: 8),
                Container(
                  key: const ValueKey('locks-verify-custody-banner'),
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  decoration: BoxDecoration(
                    color: (custodyActive ? scheme.primaryContainer : colors.warningContainer).withValues(alpha: 0.6),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    [
                      if (custodyActive)
                        '托管冲刺轨：「${custody['title'] ?? '冲刺轨'}」（至 ${custody['untilHm'] ?? '--:--'} · 托管期禁自删降级）',
                      if (adaptiveSplits > 0) '今日自适应缩块 $adaptiveSplits 次（2×25m 短快严控）',
                    ].join(' · '),
                    style: theme.textTheme.labelSmall?.copyWith(fontWeight: FontWeight.w600),
                  ),
                ),
              ],
              for (final b in overdueBlocks) ...[
                const SizedBox(height: 8),
                Container(
                  key: ValueKey('locks-verify-overdue-${b['id'] ?? ''}'),
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: colors.dangerContainer.withValues(alpha: 0.7),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              '${b['title'] ?? '验收块'}（截止 ${b['dueHm'] ?? ''} · 锁剩 ${_asInt(b['remainingLockMin'], 0)}m / 防死锁 60m 上限）',
                              style: theme.textTheme.bodySmall?.copyWith(
                                fontWeight: FontWeight.w700,
                                color: colors.onDangerContainer,
                              ),
                            ),
                          ),
                          if (b['lockedByBoss'] == true)
                            const StatusChip(tone: StatusTone.error, label: '主管硬指令'),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '约定交付：${b['deliverable'] ?? '真实产出'}',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.labelSmall?.copyWith(color: colors.onDangerContainer),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        runSpacing: 6,
                        children: [
                          FilledButton.tonalIcon(
                            key: ValueKey('locks-verify-submit-${b['id'] ?? ''}'),
                            onPressed: _busy ? null : () => unawaited(_openSubmitDeliveryDialog(b)),
                            icon: const Icon(AppIcons.edit, size: 15),
                            label: const Text('提交交付解锁'),
                          ),
                          if ('${b['verifyStatus'] ?? ''}' == 'submitted')
                            OutlinedButton.icon(
                              onPressed: _busy ? null : () => unawaited(_requestBossJudge(b)),
                              icon: const Icon(AppIcons.lock, size: 15),
                              label: const Text('通知主管终审'),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
              for (final b in pendingBlocks)
                if (!overdueBlocks.any((o) => '${o['id']}' == '${b['id']}')) ...[
                  const SizedBox(height: 8),
                  Container(
                    key: ValueKey('locks-verify-pending-${b['id'] ?? ''}'),
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: scheme.surfaceContainerLow,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                '${b['title'] ?? ''}',
                                style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w700),
                              ),
                            ),
                            StatusChip(
                              tone: b['verifyStatus'] == 'rejected' ? StatusTone.error : StatusTone.warn,
                              label: b['verifyStatus'] == 'rejected' ? '主管驳回' : '已交待终审',
                            ),
                          ],
                        ),
                        if ('${b['judgeNote'] ?? ''}'.trim().isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.only(top: 4),
                            child: Text(
                              '主管批注：${b['judgeNote']}',
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.labelSmall?.copyWith(color: colors.danger),
                            ),
                          ),
                        const SizedBox(height: 6),
                        Wrap(
                          spacing: 8,
                          runSpacing: 6,
                          children: [
                            OutlinedButton(
                              onPressed: _busy ? null : () => unawaited(_openSubmitDeliveryDialog(b)),
                              child: const Text('重新提交'),
                            ),
                            FilledButton.tonalIcon(
                              key: ValueKey('locks-verify-judge-${b['id'] ?? ''}'),
                              onPressed: _busy ? null : () => unawaited(_requestBossJudge(b)),
                              icon: const Icon(AppIcons.verified, size: 15),
                              label: const Text('通知主管终审'),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              if (activeVerify.isNotEmpty &&
                  !overdueBlocks.any((o) => '${o['id']}' == '${activeVerify['id']}') &&
                  !pendingBlocks.any((p) => '${p['id']}' == '${activeVerify['id']}')) ...[
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerLow,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          '进行中验收块：${activeVerify['title'] ?? ''}（截止 ${activeVerify['dueHm'] ?? ''} · 余 ${_asInt(activeVerify['remainingMin'], 0)}m）',
                          style: theme.textTheme.bodySmall,
                        ),
                      ),
                      const SizedBox(width: 8),
                      TextButton(
                        onPressed: _busy ? null : () => unawaited(_openSubmitDeliveryDialog(activeVerify)),
                        child: const Text('提前交卷'),
                      ),
                    ],
                  ),
                ),
              ],
              for (final b in upcomingBlocks.take(2)) ...[
                const SizedBox(height: 6),
                Text(
                  '待开干：${b['startHm'] ?? ''}–${b['dueHm'] ?? ''} · ${b['title'] ?? ''}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLockProbeCard(
    BuildContext context, {
    required ThemeData theme,
    required DshColors colors,
    required ColorScheme scheme,
    required Map<String, dynamic> overview,
    required Map<String, dynamic> activeLock,
    required bool verifyOverdue,
  }) {
    final dayWorkWallEnabled = overview['dayWorkWallEnabled'] != false;
    final hands = _resolveSixHands(overview, activeLock, dayWorkWallEnabled);
    final winnerIdRaw = '${overview['winnerId'] ?? ''}'.trim();
    final winner = hands.cast<Map<String, dynamic>?>().firstWhere(
          (h) => h?['isWinner'] == true || (winnerIdRaw.isNotEmpty && h?['id'] == winnerIdRaw),
          orElse: () => null,
        );
    final eff = _asMap(overview['effectiveDecision']);
    final allowList = _asListOfMaps(eff['allow']);
    final effMode = '${eff['mode'] ?? (activeLock['active'] == true ? 'allowlist' : 'free')}';
    final remMin = _asInt(winner?['remainingMin'] ?? eff['remainingMin']);
    final lastIntercept = overview['lastIntercept'] is Map ? _asMap(overview['lastIntercept']) : null;
    final boutHand = hands.cast<Map<String, dynamic>?>().firstWhere(
          (h) => h?['id'] == 'BOUT_STRICT',
          orElse: () => null,
        );
    final canRevokeBout = boutHand?['active'] == true &&
        boutHand?['canToggle'] != false &&
        !verifyOverdue &&
        winner?['id'] != 'NIGHT_CURFEW' &&
        winner?['id'] != 'CONTRACT_OVERDUE_STRICT';

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      child: GlassSurface(
        key: const ValueKey('locks-probe-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const OverviewGlyph(AppIcons.verified, tint: 2, size: 36),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('此刻哪只手在发力 · 锁机探针', style: theme.textTheme.titleSmall),
                        const SizedBox(height: 2),
                        Text(
                          winner != null
                              ? '${winner['name']}（${winner['priority']} · ${effMode == 'allowlist' ? '白名单拦截' : effMode}）'
                              : '自由推进中 · 暂无严格锁发力（$effMode）',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: winner != null ? colors.danger : colors.success,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        if (winner != null && ('${winner['triggerTitle'] ?? ''}'.trim().isNotEmpty || '${winner['duty'] ?? ''}'.trim().isNotEmpty))
                          Padding(
                            padding: const EdgeInsets.only(top: 2),
                            child: Text(
                              [
                                if ('${winner['triggerTitle'] ?? ''}'.trim().isNotEmpty) '触发源：${winner['triggerTitle']}',
                                if ('${winner['duty'] ?? ''}'.trim().isNotEmpty) '${winner['duty']}',
                              ].join(' · '),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                            ),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  StatusChip(
                    tone: winner != null ? StatusTone.error : StatusTone.ok,
                    label: remMin > 0 ? '余 ${remMin}m' : (winner != null ? '持续生效' : '自由'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('工作时段默认防分心锁（Option A）', style: theme.textTheme.labelLarge),
                          const SizedBox(height: 2),
                          Text(
                            dayWorkWallEnabled
                                ? '已启用（07:00–23:00 拦娱乐放工作；12:00–14:00 午休放风）'
                                : '已停用 · 白天不再自动拦娱乐',
                            style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                          ),
                        ],
                      ),
                    ),
                    Switch.adaptive(
                      key: const ValueKey('locks-day-wall-switch'),
                      value: dayWorkWallEnabled,
                      onChanged: _busy ? null : (v) => unawaited(_handleToggleDayWall(v)),
                    ),
                  ],
                ),
              ),
              if (boutHand?['active'] == true) ...[
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  key: const ValueKey('locks-strict-off-btn'),
                  style: OutlinedButton.styleFrom(shape: AppShape.card),
                  onPressed: (_busy || !canRevokeBout) ? null : () => unawaited(_handleStrictOff()),
                  icon: const Icon(AppIcons.unlock, size: 17),
                  label: Text(canRevokeBout ? '撤回当前手动严格锁' : '逾期/夜间硬锁生效中（不可撤回）'),
                ),
              ],
              const SizedBox(height: 8),
              Text('6 只控制手实时状态', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
              const SizedBox(height: 4),
              for (final h in hands)
                () {
                  final hId = '${h['id'] ?? ''}';
                  final isWinner = h['isWinner'] == true;
                  final isOn = h['active'] == true;
                  final hRem = _asInt(h['remainingMin']);
                  return Padding(
                    key: ValueKey('locks-probe-hand-$hId'),
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Padding(
                          padding: const EdgeInsets.only(top: 5),
                          child: AnimatedStatusDot(
                            tone: isWinner ? StatusTone.error : (isOn ? StatusTone.warn : StatusTone.idle),
                            size: 7,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '${h['priority'] ?? ''} · ${h['name'] ?? hId}${hRem > 0 ? '（剩 ${hRem}m）' : ''}',
                                style: theme.textTheme.bodySmall?.copyWith(
                                  fontWeight: isWinner ? FontWeight.w700 : FontWeight.w500,
                                  color: isWinner ? colors.danger : scheme.onSurface,
                                ),
                              ),
                              Text(
                                '${h['summary'] ?? ''}',
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                              ),
                              if ('${h['duty'] ?? ''}'.trim().isNotEmpty)
                                Text(
                                  '${h['duty']}',
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                                ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 6),
                        StatusChip(
                          tone: isWinner ? StatusTone.error : (isOn ? StatusTone.warn : StatusTone.idle),
                          label: isWinner ? '主发力' : (isOn ? '在位' : '未触发'),
                        ),
                      ],
                    ),
                  );
                }(),
              if (allowList.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text('当前放行工作应用（点按可拉起）', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final item in allowList.take(8))
                      ActionChip(
                        key: ValueKey('locks-probe-allow-chip-${item['pkg'] ?? ''}'),
                        avatar: const Icon(AppIcons.play, size: 14),
                        label: Text('${item['label'] ?? item['pkg'] ?? ''}', style: theme.textTheme.labelSmall),
                        onPressed: () {
                          final pkg = '${item['pkg'] ?? ''}'.trim();
                          if (pkg.isNotEmpty) unawaited(_channel.launchApp(pkg));
                        },
                      ),
                  ],
                ),
              ],
              const SizedBox(height: 10),
              Container(
                key: const ValueKey('locks-probe-last-intercept'),
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Row(
                  children: [
                    Icon(AppIcons.history, size: 15, color: colors.textTertiary),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        lastIntercept != null && '${lastIntercept['pkg'] ?? ''}'.isNotEmpty
                            ? '最近拦截：${lastIntercept['label'] ?? lastIntercept['pkg']} · 被 ${lastIntercept['handName'] ?? lastIntercept['handId'] ?? '规则'} 拦下（${lastIntercept['reason'] ?? '限制中'}）'
                            : '最近拦截：暂无手机拦截记录',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLiveCoreCard(
    BuildContext context, {
    required ThemeData theme,
    required DshColors colors,
    required ColorScheme scheme,
  }) {
    final lc = _resolveLiveCore();
    final enabled = lc['enabled'] != false;
    final wantScreenShare = lc['wantScreenShare'] != false;
    final autoReengage = lc['autoReengage'] != false;
    final muted = lc['muted'] == true;
    final phase = '${lc['phase'] ?? 'standby'}';
    final dice = _asMap(lc['dice']);
    final diceFace = _asInt(dice['face'], 0);
    final diceName = '${dice['name'] ?? ''}';
    final diceRoleHint = '${dice['roleHint'] ?? '随机六面破冰 · 窥视现场 · 喂入笔记/错题与禁聊死命令'}';

    final (StatusTone tone, String phaseLabel) = switch (phase) {
      'live_active' => (StatusTone.ok, 'Live 在线'),
      'ignited' => (StatusTone.warn, '窥视待命'),
      'yielded_studying' => (StatusTone.ok, '已让路：刷题或睡眠中'),
      'yielded_night' || 'yielded_emergency' || 'yielded_break' || 'yielded_plan_modal' =>
        (StatusTone.idle, '已让路：刷题或睡眠中'),
      'muted' => (StatusTone.idle, '静音中'),
      _ => enabled ? (StatusTone.ok, '窥视待命') : (StatusTone.idle, '已暂停'),
    };
    final effectiveTone = muted ? StatusTone.idle : tone;
    final effectiveLabel = muted ? '静音中' : phaseLabel;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      child: GlassSurface(
        key: const ValueKey('locks-live-core-card'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Icon(AppIcons.voice, size: 18, color: scheme.primary),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '🎲 Gemini Live 窥视核心（总调度 V1）',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleSmall,
                    ),
                  ),
                  StatusChip(
                    key: const ValueKey('locks-live-core-phase-chip'),
                    tone: effectiveTone,
                    label: effectiveLabel,
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                diceFace > 0
                    ? '最近骰$diceFace「$diceName」：$diceRoleHint'
                    : '入口极简只留 1 颗骰子，后台整合设定 / 错题 / Obsidian / 屏幕摘录灌进官方 Live；刷题或睡眠自动让路。',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
              ),
              const SizedBox(height: 10),
              FilledButton.icon(
                key: const ValueKey('locks-live-core-roll-btn'),
                style: FilledButton.styleFrom(shape: AppShape.card),
                onPressed: _busy ? null : () => unawaited(_handleRollLiveCoreDice()),
                icon: const Icon(AppIcons.voice, size: 17),
                label: const Text('🎲 摇骰子灌料进 Live'),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 6,
                children: [
                  FilterChip(
                    key: const ValueKey('locks-live-core-share-chip'),
                    label: const Text('窥视共享屏幕'),
                    selected: wantScreenShare,
                    onSelected: _busy ? null : (v) => unawaited(_handleToggleLiveCoreShare(v)),
                  ),
                  FilterChip(
                    key: const ValueKey('locks-live-core-reengage-chip'),
                    label: const Text('断线自动拉回'),
                    selected: autoReengage,
                    onSelected: _busy ? null : (v) => unawaited(_handleToggleLiveCoreReengage(v)),
                  ),
                  ActionChip(
                    key: const ValueKey('locks-live-core-mute-chip'),
                    avatar: const Icon(AppIcons.close, size: 14),
                    label: const Text('静音 20m'),
                    onPressed: _busy ? null : () => unawaited(_handleMuteLiveCore20m()),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MiniBalanceMetric extends StatelessWidget {
  const _MiniBalanceMetric({
    required this.label,
    required this.value,
    required this.accent,
  });

  final String label;
  final String value;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
            const SizedBox(height: 2),
            Text(
              value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppType.tabular(theme.textTheme.titleSmall!.copyWith(fontWeight: FontWeight.w700, color: accent)),
            ),
          ],
        ),
      ),
    );
  }
}

class _WindowRuleMeter extends StatelessWidget {
  const _WindowRuleMeter({
    required this.title,
    required this.badgeText,
    required this.progress,
    required this.accent,
    required this.subtitle,
  });

  final String title;
  final String badgeText;
  final double progress;
  final Color accent;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(title, style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w600)),
              ),
              Text(
                badgeText,
                style: AppType.tabular(theme.textTheme.labelMedium!.copyWith(color: accent, fontWeight: FontWeight.w700)),
              ),
            ],
          ),
          const SizedBox(height: 6),
          ClipRRect(
            borderRadius: BorderRadius.circular(999),
            child: LinearProgressIndicator(
              value: progress.clamp(0.0, 1.0),
              minHeight: 6,
              backgroundColor: theme.colorScheme.outlineVariant.withValues(alpha: 0.25),
              valueColor: AlwaysStoppedAnimation<Color>(accent),
            ),
          ),
          const SizedBox(height: 5),
          Text(subtitle, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
        ],
      ),
    );
  }
}
