part of 'bouts_workspace_page.dart';

extension _BoutsWorkspaceHero on _BoutsWorkspacePageState {
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
      final scopeLabel = '${cur['scopeLabel'] ?? ''}'.trim();
      final sealTitle = _resolveBoutSealTitle(cur);
      final ringColor = strict ? colors.danger : scheme.primary;
      final progress = _runningProgress(cur);

      return GlassSurface(
        key: const ValueKey('today-running-hero'),
        tier: GlassTier.card,
        borderRadius: AppShape.radiusLg,
        onTap: _openBoutDetail,
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
                          '${_formatCountdown(endsAt)}到点 · ${allow.isEmpty ? '只放行紧急工具' : '放行 ${allow.map(_labelOf).join('、')}'}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 4),
                  Icon(AppIcons.forward, size: 18, color: colors.textTertiary),
                ],
              ),
              if (scopeLabel.isNotEmpty || sealTitle.isNotEmpty) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    if (scopeLabel.isNotEmpty)
                      StatusChip(
                        key: const ValueKey('running-bout-scope-chip'),
                        tone: StatusTone.ok,
                        label: '自动范围：$scopeLabel（免手选）',
                      ),
                    if (sealTitle.isNotEmpty)
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(AppIcons.verified, size: 14, color: colors.info),
                          const SizedBox(width: 4),
                          Flexible(
                            child: Text(
                              '本局关联验收点：$sealTitle',
                              key: const ValueKey('running-bout-seal-label'),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.bodySmall?.copyWith(color: colors.info),
                            ),
                          ),
                        ],
                      ),
                  ],
                ),
              ],
              const SizedBox(height: 12),
              Divider(height: 0.5, thickness: 0.5, color: scheme.outlineVariant.withValues(alpha: 0.45)),
              const SizedBox(height: 10),
              Text(
                '放行的 App · 点一下直接打开（点卡片看局详情）',
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
                            final ok = await _channel.emergencySuspendStrict(minutes: 10);
                            if (ok) {
                              unawaited(_channel.tickNow().catchError((_) => false));
                            }
                            await _refresh(silent: true);
                            if (mounted) _mutate(() => _banner = '已紧急挂起严格锁 10 分钟，可安心调整设置或切换应用');
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
                    key: const ValueKey('today-bout-strict-toggle'),
                    style: OutlinedButton.styleFrom(shape: AppShape.card),
                    onPressed: _busy
                        ? null
                        : () {
                            if (strict) {
                              unawaited(_sendBoutCommand({'action': 'relax'}, toast: '已切为温和守护'));
                            } else {
                              unawaited(_upgradeToStrictWithDuration());
                            }
                          },
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
      for (final p in _BoutsWorkspacePageState._whitelistPresets) p.package: p.label,
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
                  onTap: () => _mutate(() => _strictMode = !_strictMode),
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
                  for (final p in _BoutsWorkspacePageState._quickFocusPresets) ...[
                    CapsuleChoiceChip(
                      icon: AppIcons.play,
                      label: p.chip,
                      selected: _goalCtrl.text.trim() == p.goal,
                      onSelected: () => _mutate(() {
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
                      onSelected: () => _mutate(() => _selectedProtocol = id),
                    ),
                    const SizedBox(width: 6),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 10),
            Wrap(
              key: const ValueKey('today-duration-capsules'),
              spacing: 8,
              runSpacing: 6,
              children: [
                for (final m in const [25, 45, 90])
                  CapsuleChoiceChip(
                    key: ValueKey('today-duration-${m}m'),
                    label: '${m}m',
                    selected: _selectedMinutes == m,
                    onSelected: () => _mutate(() => _selectedMinutes = m),
                  ),
                CapsuleChoiceChip(
                  key: const ValueKey('today-duration-custom'),
                  icon: AppIcons.queued,
                  label: const [25, 45, 90].contains(_selectedMinutes) ? '自定义' : '自定义 ${_selectedMinutes}m',
                  selected: !const [25, 45, 90].contains(_selectedMinutes),
                  onSelected: () async {
                    final custom = await _pickCustomMinutesDialog(
                      title: '自定义专注时长',
                      initialMinutes: _selectedMinutes,
                    );
                    if (custom != null && mounted) {
                      _mutate(() => _selectedMinutes = custom);
                    }
                  },
                ),
              ],
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
                    onSelected: () => _mutate(() {
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
                onTap: () => _mutate(() => _strictMode = !_strictMode),
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
                        onChanged: (v) => _mutate(() => _strictMode = v),
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

  /// 局详情：从「此刻焦距」点进来的完整页。和局页共用同一份状态（[_tick] 每次刷新都会重建它），
  /// 所以这里的按钮和局页上的是同一套动作，不会两边不一致。
  void _openBoutDetail() {
    unawaited(Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ValueListenableBuilder<int>(
          valueListenable: _tick,
          builder: (ctx, _, _) => AppPage.list(
            key: const ValueKey('bout-detail-page'),
            title: '局详情',
            subtitle: const Text('这一局的时间、放行、交付和操作'),
            actions: [
              GlassIconButton(
                tooltip: '刷新状态',
                icon: AppIcons.refresh,
                onPressed: () => unawaited(_refresh(silent: true)),
              ),
            ],
            children: _buildBoutDetailChildren(ctx),
          ),
        ),
      ),
    ));
  }

  /// 节点上下文：这一局从哪个节点派出来、要交什么、上次断在哪。点进去是战役档案。
  Widget _nodeContextCard(BuildContext ctx, Map<String, dynamic> nc, Object? link) {
    final colors = DshColors.of(ctx);
    final horizonId = link is Map ? '${link['horizonId'] ?? ''}' : '';
    final bp = '${nc['lastBreakpoint'] ?? ''}'.trim();
    final deliverable = '${nc['deliverable'] ?? ''}'.trim();
    return ListPageCard(
      key: const ValueKey('bout-detail-node'),
      title: '节点上下文',
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      children: [
        ListPageRow(
          icon: AppIcons.planMode,
          iconColor: Theme.of(ctx).colorScheme.primary,
          title: '${nc['nodeTitle'] ?? ''}',
          subtitle: [
            '${nc['horizonTitle'] ?? ''}',
            if ('${nc['targetDate'] ?? ''}'.isNotEmpty) '预留到 ${nc['targetDate']}',
          ].where((x) => x.isNotEmpty).join(' · '),
          chevron: horizonId.isNotEmpty,
          onTap: horizonId.isEmpty ? null : () => unawaited(HorizonDossierPage.open(ctx, horizonId: horizonId, channel: _channel)),
        ),
        if (bp.isNotEmpty)
          ListPageRow(icon: AppIcons.history, iconColor: colors.info, title: '上次断在', subtitle: bp, subtitleMaxLines: 3),
        if (deliverable.isNotEmpty)
          ListPageRow(icon: AppIcons.done, iconColor: colors.success, title: '节点交付', subtitle: deliverable, subtitleMaxLines: 3),
      ],
    );
  }

  /// 局详情「叫 Gemini 来判」：插件拼好这一局的背景（think 口吻），手机拉起 Gemini。
  Future<void> _askGeminiForBout() async {
    _mutate(() => _busy = true);
    final r = await _channel.boutsCommand({'action': 'ask-gemini'});
    var ok = false;
    if (r?['ok'] == true) {
      ok = await _channel.launchGeminiProactive(
        topic: '${r!['topic'] ?? '判一下这一局'}',
        brief: '${r['brief'] ?? ''}',
        mode: 'countdown_3s',
        preferVoice: true,
      );
    }
    if (!mounted) return;
    _mutate(() => _busy = false);
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(
      content: Text(ok ? '已拉起 Gemini（背景已带上）' : '${r?['toast'] ?? '没拉起 Gemini：检查悬浮窗与无障碍权限'}'),
    ));
  }

  String _clockOf(num? ms) {
    if (ms == null || ms <= 0) return '--';
    final t = DateTime.fromMillisecondsSinceEpoch(ms.toInt());
    return '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
  }

  List<Widget> _buildBoutDetailChildren(BuildContext ctx) {
    final theme = Theme.of(ctx);
    final colors = DshColors.of(ctx);
    final cur = _runningBout;
    if (cur == null) {
      return [
        const OverviewCard(
          leading: OverviewGlyph(AppIcons.done, tint: 1),
          title: Text('这一局已经结束'),
          subtitle: Text('回到「局」页可以开下一局；验收结果在「今日使用情况」里。'),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: FilledButton(
            style: FilledButton.styleFrom(shape: AppShape.card, minimumSize: const Size.fromHeight(44)),
            onPressed: () => Navigator.of(ctx).maybePop(),
            child: const Text('返回局页'),
          ),
        ),
      ];
    }
    final title = '${cur['title'] ?? cur['task'] ?? cur['protocol'] ?? '专注进行中'}';
    final strict = cur['strict'] == true || cur['mode'] == 'strict';
    final endsAt = cur['until'] as num? ?? cur['endsAt'] as num?;
    final startedAt = cur['startedAt'] as num? ?? cur['at'] as num?;
    final protocol = '${cur['protocolId'] ?? cur['protocol'] ?? ''}'.trim();
    final deliverable = '${cur['deliverable'] ?? cur['goal'] ?? cur['accept'] ?? ''}'.trim();
    final scopeLabel = '${cur['scopeLabel'] ?? ''}'.trim();
    final sealTitle = _resolveBoutSealTitle(cur);
    final appsRaw = cur['allow'] ?? (cur['apps'] is Map ? (cur['apps'] as Map)['allow'] : null);
    final allow = appsRaw is List ? [for (final a in appsRaw) '$a'] : const <String>[];
    final ringColor = strict ? colors.danger : theme.colorScheme.primary;
    final leftMin = endsAt == null
        ? null
        : ((endsAt.toInt() - DateTime.now().millisecondsSinceEpoch) / 60000).ceil().clamp(0, 24 * 60);
    final bigText = leftMin == null ? '--' : (leftMin < 60 ? '$leftMin' : '${leftMin ~/ 60}:${(leftMin % 60).toString().padLeft(2, '0')}');
    final bigUnit = leftMin == null ? '没有设到点时间' : (leftMin == 0 ? '到点了' : (leftMin < 60 ? '分钟后到点' : '小时后到点'));

    return [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: GlassSurface(
          key: const ValueKey('bout-detail-hero'),
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 20, 16, 18),
            child: Column(
              children: [
                ProgressHalo(
                  value: _runningProgress(cur),
                  size: 148,
                  strokeWidth: 8,
                  color: ringColor,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        bigText,
                        style: AppType.tabular(theme.textTheme.displaySmall!.copyWith(fontWeight: FontWeight.w700, color: ringColor)),
                      ),
                      Text(bigUnit, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
                Text(title, textAlign: TextAlign.center, style: theme.textTheme.titleLarge, maxLines: 2, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 8),
                Wrap(
                  alignment: WrapAlignment.center,
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    StatusChip(tone: strict ? StatusTone.error : StatusTone.ok, label: strict ? '严格锁' : '温和局'),
                    StatusChip(
                      tone: StatusTone.idle,
                      label: startedAt != null && startedAt > 0 ? '${_clockOf(startedAt)} → ${_clockOf(endsAt)}' : '到 ${_clockOf(endsAt)}',
                    ),
                    if (protocol.isNotEmpty) StatusChip(tone: StatusTone.idle, label: protocol),
                    if (scopeLabel.isNotEmpty)
                      StatusChip(
                        key: const ValueKey('bout-detail-scope-chip'),
                        tone: StatusTone.ok,
                        label: '自动范围：$scopeLabel（免手选）',
                      ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
      if (cur['nodeContext'] is Map) _nodeContextCard(ctx, Map<String, dynamic>.from(cur['nodeContext'] as Map), cur['nodeLink']),
      ListPageCard(
        title: '这一局',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          if (sealTitle.isNotEmpty)
            ListPageRow(
              key: const ValueKey('bout-detail-seal-row'),
              icon: AppIcons.verified,
              iconColor: colors.info,
              title: '本局关联验收点：$sealTitle',
              subtitle: '开局已自动关联，无需手动再挑',
              subtitleMaxLines: 2,
            ),
          ListPageRow(
            icon: AppIcons.done,
            title: '要交付什么',
            subtitle: deliverable.isNotEmpty ? deliverable : title,
            subtitleMaxLines: 3,
          ),
          ListPageRow(
            icon: strict ? AppIcons.lock : AppIcons.unlock,
            iconColor: strict ? colors.danger : colors.success,
            title: strict ? '严格锁' : '温和局',
            subtitle: strict ? '不在放行名单里的 App 一打开就拦' : '只提醒，不拦',
          ),
          if (startedAt != null && startedAt > 0) ListPageRow(icon: AppIcons.time, title: '开始', value: _clockOf(startedAt)),
          ListPageRow(icon: AppIcons.time, title: '到点', value: _clockOf(endsAt), subtitle: _formatCountdown(endsAt)),
        ],
      ),
      ListPageCard(
        title: '放行的 App（点一下直接打开）',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          if (allow.isEmpty)
            const ListPageRow(icon: AppIcons.lock, title: '只放行紧急工具')
          else
            for (final pkg in allow)
              ListPageRow(
                icon: AppIcons.play,
                iconColor: theme.colorScheme.primary,
                title: _labelOf(pkg),
                chevron: true,
                onTap: () => unawaited(_channel.launchApp(pkg)),
              ),
          ListPageRow(
            key: const ValueKey('bout-detail-allow'),
            icon: AppIcons.add,
            iconColor: colors.info,
            title: '调整白名单',
            onTap: _busy ? null : () => unawaited(_pickRunningAllowApps(allow)),
          ),
        ],
      ),
      ListPageCard(
        title: '操作',
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          ListPageRow(
            key: const ValueKey('bout-detail-ask-gemini'),
            icon: AppIcons.askAi,
            iconColor: Theme.of(ctx).colorScheme.primary,
            title: '叫 Gemini 来判',
            subtitle: '卡住了，或想让它判一下这局做得对不对（带上目标、交付、断点）',
            subtitleMaxLines: 2,
            onTap: _busy ? null : () => unawaited(_askGeminiForBout()),
          ),
          ListPageRow(
            icon: AppIcons.voice,
            title: '语音 / Gemini',
            subtitle: '连麦推进这一局',
            chevron: true,
            onTap: () => Navigator.of(ctx).push(MaterialPageRoute<void>(builder: (_) => const BoutVoicePage())),
          ),
          ListPageRow(
            icon: strict ? AppIcons.unlock : AppIcons.lock,
            title: strict ? '切成温和局' : '升级为严格锁',
            onTap: _busy
                ? null
                : () {
                    if (strict) {
                      unawaited(_sendBoutCommand({'action': 'relax'}, toast: '已切为温和守护'));
                    } else {
                      unawaited(_upgradeToStrictWithDuration());
                    }
                  },
          ),
          ListPageRow(
            icon: AppIcons.warning,
            iconColor: colors.warning,
            title: '紧急暂停 10 分钟',
            subtitle: '挂起严格锁，记一次紧急解除',
            onTap: _busy
                ? null
                : () async {
                    final ok = await _channel.emergencySuspendStrict(minutes: 10);
                    if (ok) {
                      unawaited(_channel.tickNow().catchError((_) => false));
                    }
                    await _refresh(silent: true);
                    if (mounted) _mutate(() => _banner = '已紧急挂起严格锁 10 分钟，可安心调整设置或切换应用');
                  },
          ),
          ListPageRow(
            icon: AppIcons.allFeatures,
            title: '时钟与队列',
            chevron: true,
            onTap: () => unawaited(_openSystemDetails()),
          ),
        ],
      ),
      ?_buildActiveContractCard(colors),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        child: FilledButton.icon(
          key: const ValueKey('bout-detail-close'),
          style: FilledButton.styleFrom(shape: AppShape.card, minimumSize: const Size.fromHeight(48)),
          onPressed: _busy ? null : () => unawaited(_sendBoutCommand({'action': 'close'}, toast: '已发起收工验收')),
          icon: const Icon(AppIcons.done, size: 18),
          label: const Text('收工验收'),
        ),
      ),
    ];
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
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
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
}
