part of 'plan_modality_page.dart';

extension _PlanModalityDialogs on _PlanModalityPageState {
  Future<void> _openRebriefCustodyDialog() async {
    final reasonCtrl = TextEditingController();
    var downgradeLowEnergy = false;
    String? err;
    final out = await showDialog<(String, bool)>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          key: const ValueKey('plan-custody-rebrief-dialog'),
          shape: AppShape.dialog,
          title: const Text('受控改签（Re-brief）'),
          content: SizedBox(
            width: 380,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    '托管冲刺轨进行期间禁止自删或降级时间块。写清不少于 6 字的客观突发原因后，可重签为保底推进轨。',
                    style: TextStyle(fontSize: 12),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    key: const ValueKey('plan-custody-rebrief-reason'),
                    controller: reasonCtrl,
                    minLines: 2,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      labelText: '改签理由（至少 6 字）',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      const Expanded(child: Text('切为低电量 25m 保底轨')),
                      Switch(
                        value: downgradeLowEnergy,
                        onChanged: (v) => setLocal(() => downgradeLowEnergy = v),
                      ),
                    ],
                  ),
                  if (err != null) ...[
                    const SizedBox(height: 4),
                    Text(err!, style: TextStyle(color: DshColors.of(ctx).danger, fontSize: 12)),
                  ],
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            FilledButton(
              key: const ValueKey('plan-custody-rebrief-confirm'),
              onPressed: () {
                final r = reasonCtrl.text.trim();
                if (r.length < 6) {
                  setLocal(() => err = '请写清至少 6 个字的客观原因');
                  return;
                }
                Navigator.pop(ctx, (r, downgradeLowEnergy));
              },
              child: const Text('确认重签'),
            ),
          ],
        ),
      ),
    );
    disposeAfterRoute(reasonCtrl);
    if (out == null || !mounted) return;
    _mutate(() => _busy = true);
    final res = await _channel.planContractOp({
      'action': 'rebrief_custody',
      'reason': out.$1,
      'downgradeLowEnergy': out.$2,
    }).catchError((_) => null);
    if (!mounted) return;
    _mutate(() {
      _busy = false;
      if (res?['ok'] == true) {
        _hasLocalBlockEdits = false;
        _banner = '✓ 已执行受控改签（Re-brief）并重签保底推进轨';
      } else {
        _banner = '${res?['text'] ?? res?['error'] ?? '受控改签失败'}';
      }
    });
    unawaited(_channel.tickNow().catchError((_) => false));
    await _refresh();
  }

  Future<void> _openBlockEditor([Map<String, dynamic>? existing]) async {
    final isNew = existing == null;
    final titleCtrl = TextEditingController(text: '${existing?['title'] ?? ''}');
    final delivCtrl = TextEditingController(text: '${existing?['deliverable'] ?? '提交可见物理产物或验收'}');
    var startMin = _asInt(existing?['startMin'], 9 * 60);
    var durationMin = existing != null
        ? math.max(10, _asInt(existing['endMin'], startMin + 45) - startMin)
        : 45;
    var lockMode = '${existing?['lockMode'] ?? (existing?['isBreak'] == true ? 'gentle' : 'strict')}';
    final allow = <String>{
      if (existing?['allowedApps'] is List)
        for (final x in existing!['allowedApps'] as List) '$x'
      else ...['md.obsidian', 'com.tencent.mm'],
    };
    final isBreak = existing?['isBreak'] == true;
    final isBossLocked = existing?['lockedByBoss'] == true || existing?['source'] == 'wall_boss';
    final isDone = existing?['status'] == 'done';
    if (isBossLocked && !isDone) lockMode = 'verify';

    final action = await showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          title: Text(isNew ? '新增时间块' : '编辑时间块'),
          content: SizedBox(
            width: 380,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (isBossLocked) ...[
                    Container(
                      padding: const EdgeInsets.all(10),
                      margin: const EdgeInsets.only(bottom: 10),
                      decoration: BoxDecoration(
                        color: DshColors.of(ctx).dangerContainer,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        '主管止损硬指令：不可删除、不可跳过，须交付过审解除验收锁',
                        style: Theme.of(ctx).textTheme.bodySmall?.copyWith(
                              color: DshColors.of(ctx).onDangerContainer,
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                    ),
                  ],
                  TextField(
                    key: const ValueKey('block-editor-title'),
                    controller: titleCtrl,
                    decoration: const InputDecoration(labelText: '时间块标题', border: OutlineInputBorder(), isDense: true),
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: () async {
                            final picked = await showTimePicker(
                              context: ctx,
                              initialTime: TimeOfDay(hour: startMin ~/ 60, minute: startMin % 60),
                            );
                            if (picked != null) {
                              setDialog(() => startMin = picked.hour * 60 + picked.minute);
                            }
                          },
                          icon: const Icon(AppIcons.time, size: 16),
                          label: Text('开始 ${_hm(startMin)}'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text('→ 结束 ${_hm(math.min(1440, startMin + durationMin))}'),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text('块时长（锁时长跟块走）：${durationMin}m', style: Theme.of(ctx).textTheme.labelMedium),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      for (final d in const [20, 30, 45, 60, 90, 120])
                        ChoiceChip(
                          label: Text('${d}m'),
                          selected: durationMin == d,
                          onSelected: (_) => setDialog(() => durationMin = d),
                        ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text('锁强度', style: Theme.of(ctx).textTheme.labelMedium),
                  const SizedBox(height: 6),
                  SegmentedPill<String>(
                    segments: const [
                      PillSegment('gentle', '温和'),
                      PillSegment('strict', '严格'),
                      PillSegment('verify', '验收锁'),
                    ],
                    selected: const ['gentle', 'strict', 'verify'].contains(lockMode) ? lockMode : 'strict',
                    onChanged: (v) {
                      if (isBossLocked && !isDone && v != 'verify') return;
                      setDialog(() => lockMode = v);
                    },
                  ),
                  if (lockMode == 'verify') ...[
                    const SizedBox(height: 6),
                    Text(
                      '验收锁规则：到开始时间自动 P4 接管；结束前未过审且超 60 秒宽限自动升级 P3 验收严锁（须交付过审解锁，不可花余额跳过）。',
                      style: Theme.of(ctx).textTheme.labelSmall?.copyWith(color: DshColors.of(ctx).textTertiary),
                    ),
                  ],
                  const SizedBox(height: 10),
                  TextField(
                    key: const ValueKey('block-editor-deliverable'),
                    controller: delivCtrl,
                    decoration: const InputDecoration(labelText: '到点交付物', border: OutlineInputBorder(), isDense: true),
                  ),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    onPressed: () async {
                      final picked = await showInstalledAppsPicker(
                        ctx,
                        title: '选择本时间块允许的 App',
                        loop: _loop,
                        initialSelected: allow.toList(),
                        allowEmpty: true,
                      );
                      if (picked != null) {
                        setDialog(() {
                          allow
                            ..clear()
                            ..addAll(picked.map((a) => a.package));
                        });
                      }
                    },
                    icon: const Icon(AppIcons.files, size: 16),
                    label: Text('允许 App（${allow.length} 个）：${allow.map(_appName).join('、')}'),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            if (!isNew && !isBossLocked)
              TextButton(
                onPressed: () => Navigator.pop(ctx, 'delete'),
                child: Text('删除', style: TextStyle(color: DshColors.of(ctx).danger)),
              ),
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            FilledButton(
              key: const ValueKey('block-editor-save'),
              onPressed: () {
                if (titleCtrl.text.trim().isEmpty) return;
                Navigator.pop(ctx, 'save');
              },
              child: const Text('保存时间块'),
            ),
          ],
        ),
      ),
    );

    final title = titleCtrl.text.trim();
    final deliverable = delivCtrl.text.trim();
    disposeAfterRoute(titleCtrl);
    disposeAfterRoute(delivCtrl);
    if (!mounted || action == null) return;

    if (action == 'delete' && existing != null) {
      final delId = '${existing['id']}';
      _mutate(() {
        _hasLocalBlockEdits = true;
        _dayBlocks = [for (final b in _dayBlocks) if ('${b['id']}' != delId) b];
        _banner = '✓ 已删除时间块「${existing['title']}」';
      });
      unawaited(_channel.planContractOp({'action': 'delete_block', 'blockId': delId}).catchError((_) => null));
      unawaited(_channel.tickNow().catchError((_) => false));
      return;
    }

    if (action == 'save' && title.isNotEmpty) {
      final id = '${existing?['id'] ?? 'blk-${DateTime.now().millisecondsSinceEpoch.toRadixString(36)}'}';
      final slot = _slotOf(startMin);
      final updated = <String, dynamic>{
        'id': id,
        'slot': slot,
        'startMin': startMin,
        'endMin': math.min(1440, startMin + durationMin),
        'title': title,
        'deliverable': deliverable.isEmpty ? '提交可见产出' : deliverable,
        'lockMode': lockMode,
        'allowedApps': allow.isEmpty ? ['md.obsidian'] : allow.toList(),
        'status': '${existing?['status'] ?? 'pending'}',
        'isBreak': isBreak || (slot == 'lunch' && lockMode == 'gentle' && RegExp(r'午休|放风|休息').hasMatch(title)),
        if (isBossLocked) 'lockedByBoss': true,
        if ('${existing?['source'] ?? ''}'.isNotEmpty) 'source': existing!['source'],
        if ('${existing?['verifyStatus'] ?? ''}'.isNotEmpty) 'verifyStatus': existing!['verifyStatus'],
        if ('${existing?['sealId'] ?? ''}'.isNotEmpty) 'sealId': existing!['sealId'],
        if ('${existing?['submissionText'] ?? ''}'.isNotEmpty) 'submissionText': existing!['submissionText'],
      };
      _mutate(() {
        _hasLocalBlockEdits = true;
        final list = [for (final b in _dayBlocks) if ('${b['id']}' != id) b, updated];
        _dayBlocks = _sortBlocks(list);
        _banner = '✓ 已保存时间块「$title」（${_hm(startMin)}–${_hm(_asInt(updated['endMin'], startMin + durationMin))} · ${_lockModeLabel(lockMode)}）';
      });
      unawaited(_channel.planContractOp({'action': 'upsert_block', 'block': updated}).catchError((_) => null));
      unawaited(_channel.tickNow().catchError((_) => false));
    }
  }

  Future<void> _openVerifySheet(Map<String, dynamic> cp) async {
    final sumCtrl = TextEditingController();
    final res = await showDialog<Map<String, dynamic>?>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('验收点：${cp['title']}'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                cp['mode'] == 'video_extract'
                    ? '把你看完视频/材料后由 AI 压缩提炼的因果链核心发进来（建议包含：因为/所以/机制/结论），系统会先检验因果结构是否敷衍，或直接与大肥鱼高强度对线通过！'
                    : '写下本次产出的物理证据与核心结论：',
                style: Theme.of(ctx).textTheme.bodySmall,
              ),
              const SizedBox(height: 10),
              TextField(
                controller: sumCtrl,
                minLines: 4,
                maxLines: 8,
                decoration: const InputDecoration(
                  labelText: '提交压缩提炼 / 成果结论',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () async {
              final sum = sumCtrl.text.trim();
              final msg = '以下是我围绕验收点「${cp['title']}」压出的提炼结论：\n${sum.isEmpty ? '【请补上你的提炼要点】' : sum}\n'
                  '请调统筹规划部+督办审计部，按底层因果和考场/面试反例高强度追问我 2 句，答对后调 plan_verify_checkpoint(aiConfirmed=true) 放行。';
              await Clipboard.setData(ClipboardData(text: msg));
              if (ctx.mounted) {
                ScaffoldMessenger.maybeOf(ctx)?.showSnackBar(const SnackBar(content: Text('已复制高强度对线指令，可发给 AI 验收')));
              }
            },
            child: const Text('复制发 AI 对线'),
          ),
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () async {
              final r = await _channel.planContractOp({
                'action': 'verify',
                'checkpointId': cp['id'],
                'summary': sumCtrl.text.trim(),
                'aiConfirmed': false,
              });
              if (ctx.mounted) Navigator.pop(ctx, r);
            },
            child: const Text('提交验收'),
          ),
        ],
      ),
    );
    disposeAfterRoute(sumCtrl);
    if (!mounted || res == null) return;
    await _refresh();
    if (!mounted) return;
    _mutate(() {
      if (res['passed'] == true) {
        _banner = '✓ 验收通过！已记入正面战绩并触发正向松绑 (autonomyLevel)';
      } else {
        final probes = (res['probes'] as List?)?.join('\n· ') ?? '${res['error'] ?? '内容缺少因果机制，请补充后再交'}';
        _banner = '进入高强度追问：\n· $probes';
      }
    });
  }
}
