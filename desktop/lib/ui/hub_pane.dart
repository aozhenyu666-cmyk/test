import 'package:flutter/material.dart';

import '../services/dsh_bridge.dart';
import '../theme/mist_theme.dart';
import 'dice_drill_card.dart';

class HubPane extends StatefulWidget {
  const HubPane({super.key, required this.controller});

  final DesktopController controller;

  @override
  State<HubPane> createState() => _HubPaneState();
}

class _HubPaneState extends State<HubPane> {
  String _dept = 'plan';
  final _thoughtCtrl = TextEditingController();
  final _reportCtrl = TextEditingController();
  String _depositMsg = '';

  @override
  void dispose() {
    _thoughtCtrl.dispose();
    _reportCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final plan = (c.planDayData['plan'] as Map?)?.cast<String, Object?>();
    final queue = (plan?['queue'] as List?) ?? const [];
    final sistersMap = c.sistersData;
    final deptsMap = (c.depositData['departments'] as Map?)?.cast<String, Object?>() ?? const {};

    return ListView(
      children: [
        Row(
          children: [
            Text('今日伴侣中枢', style: Theme.of(context).textTheme.headlineSmall),
            const Spacer(),
            OutlinedButton.icon(
              onPressed: c.hasHttpCompanion ? c.refreshHub : null,
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: const Text('刷新手机状态'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: MistGlassSurface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('今日计划队列 (/plan/day)', style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    if (queue.isEmpty)
                      const Text('暂无今日同步队列（连接手机伴侣后将自动拉取每日专注时段）', style: TextStyle(fontSize: 13))
                    else
                      for (final raw in queue.take(8))
                        if (raw is Map)
                          Padding(
                            padding: const EdgeInsets.only(bottom: 8),
                            child: Row(
                              children: [
                                Container(
                                  width: 8,
                                  height: 8,
                                  decoration: const BoxDecoration(color: MistColors.cobalt, shape: BoxShape.circle),
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    '${raw['title'] ?? raw['protocolId'] ?? '局'} · ${_formatMin(raw['minute'])} (${raw['budget'] ?? '-'}min)',
                                    style: const TextStyle(fontSize: 13),
                                  ),
                                ),
                              ],
                            ),
                          ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: MistGlassSurface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('鱼丸 & 鲸澜镜像视角 (/sisters)', style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    if (sistersMap.isEmpty)
                      const Text('连接 Companion Host 后在此查看双姐妹最近印象、账本与专注趋势。', style: TextStyle(fontSize: 13))
                    else ...[
                      Text('当前状态：${sistersMap['mode'] ?? sistersMap['state'] ?? '在线'}', style: const TextStyle(fontSize: 13)),
                      const SizedBox(height: 4),
                      Text(
                        (sistersMap['summary'] ?? sistersMap['audit'] ?? sistersMap.keys.take(6).join(', ')).toString(),
                        maxLines: 5,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 12.5),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        SixDiceDrillCard(controller: c),
        const SizedBox(height: 12),
        MistGlassSurface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text('每日四部门大屏录入 (/deposit)', style: Theme.of(context).textTheme.titleMedium),
                  const Spacer(),
                  if (deptsMap.isNotEmpty)
                    Text('今日状态键：${deptsMap.keys.join(' · ')}', style: const TextStyle(fontSize: 12)),
                ],
              ),
              const SizedBox(height: 10),
              Wrap(
                spacing: 8,
                children: [
                  for (final entry in const [
                    ('plan', '规划部'),
                    ('exec', '执行部'),
                    ('behavior', '行为部'),
                    ('review', '复盘部'),
                  ])
                    ChoiceChip(
                      selected: _dept == entry.$1,
                      label: Text(entry.$2),
                      onSelected: (_) => setState(() => _dept = entry.$1),
                    ),
                ],
              ),
              const SizedBox(height: 10),
              TextField(
                controller: _reportCtrl,
                decoration: const InputDecoration(
                  isDense: true,
                  labelText: '客观进展 / 完成情况（可选）',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _thoughtCtrl,
                minLines: 2,
                maxLines: 4,
                decoration: const InputDecoration(
                  labelText: '真实思考与卡点记录（支持键盘长文高效输入）',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  FilledButton(
                    onPressed: () async {
                      final res = await c.submitDeposit(
                        department: _dept,
                        report: _reportCtrl.text,
                        thought: _thoughtCtrl.text,
                      );
                      if (!mounted) return;
                      setState(() {
                        _depositMsg = res?['ok'] == true
                            ? '部门「$_dept」已在 PC 端入账成功'
                            : '返回结果：${res?['error'] ?? res}';
                      });
                    },
                    child: const Text('提交部门汇报'),
                  ),
                  const SizedBox(width: 12),
                  if (_depositMsg.isNotEmpty)
                    Expanded(child: Text(_depositMsg, style: const TextStyle(fontSize: 12.5))),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

  static String _formatMin(Object? rawMin) {
    final m = int.tryParse((rawMin ?? '').toString());
    if (m == null || m < 0) return '--:--';
    final hh = (m ~/ 60).toString().padLeft(2, '0');
    final mm = (m % 60).toString().padLeft(2, '0');
    return '$hh:$mm';
  }
}
