import 'package:flutter/material.dart';

import '../services/dsh_bridge.dart';
import '../theme/mist_theme.dart';

/// Interactive FWK-003 6-sided Focus Dice staged card (`Stage 1` -> `Stage 2` -> `Stage 3`).
class SixDiceDrillCard extends StatefulWidget {
  const SixDiceDrillCard({super.key, required this.controller});

  final DesktopController controller;

  @override
  State<SixDiceDrillCard> createState() => _SixDiceDrillCardState();
}

class _SixDiceDrillCardState extends State<SixDiceDrillCard> {
  int _stage = 1;
  int _slotIndex = 0;
  final _topicCtrl = TextEditingController();
  final _answerCtrl = TextEditingController();
  String _feedback = '';
  bool _submitting = false;

  @override
  void dispose() {
    _topicCtrl.dispose();
    _answerCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final specs = SixDiceCardSpec.forStage(_stage, topic: _topicCtrl.text);
    final spec = specs[_slotIndex.clamp(0, specs.length - 1)];

    return MistGlassSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.casino_rounded, color: MistColors.cobalt),
              const SizedBox(width: 8),
              Text('六骰思维防走神打卡（FWK-003）', style: Theme.of(context).textTheme.titleMedium),
              const Spacer(),
              SegmentedButton<int>(
                segments: const [
                  ButtonSegment(value: 1, label: Text('一阶·入门')),
                  ButtonSegment(value: 2, label: Text('二阶·主线')),
                  ButtonSegment(value: 3, label: Text('三阶·高阶')),
                ],
                selected: {_stage},
                onSelectionChanged: (s) => setState(() {
                  _stage = s.first;
                  _slotIndex = 0;
                  _feedback = '';
                }),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _topicCtrl,
                  onChanged: (_) => setState(() {}),
                  decoration: const InputDecoration(
                    isDense: true,
                    labelText: '当前学习/代码主题（可选）',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Wrap(
                spacing: 6,
                children: [
                  for (var i = 0; i < specs.length; i++)
                    ChoiceChip(
                      selected: i == _slotIndex,
                      label: Text('骰${specs[i].diceNumber} · ${specs[i].name}'),
                      onSelected: (_) => setState(() => _slotIndex = i),
                    ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 10),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: MistColors.cobalt.withValues(alpha: 0.08),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Text(spec.prompt, style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w500)),
          ),
          const SizedBox(height: 10),
          TextField(
            controller: _answerCtrl,
            minLines: 2,
            maxLines: 4,
            decoration: InputDecoration(
              hintText: '用你自己的话说清这一骰动作（至少 ${spec.minChars} 字，禁止纯套话/复读原句）',
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              FilledButton(
                onPressed: _submitting
                    ? null
                    : () async {
                        setState(() {
                          _submitting = true;
                          _feedback = '';
                        });
                        final res = await widget.controller.submitDiceAnswer(spec, _answerCtrl.text);
                        if (!mounted) return;
                        setState(() {
                          _submitting = false;
                          if (res?['ok'] == true || res?['accepted'] == true) {
                            _feedback = '已送入大脑核验并入账（${spec.name}）';
                            _answerCtrl.clear();
                          } else {
                            _feedback = (res?['error'] ?? res?['message'] ?? res.toString()).toString();
                          }
                        });
                      },
                child: Text(_submitting ? '提交中...' : '提交本骰答案'),
              ),
              const SizedBox(width: 12),
              if (_feedback.isNotEmpty)
                Expanded(child: Text(_feedback, style: const TextStyle(fontSize: 12.5))),
            ],
          ),
        ],
      ),
    );
  }
}
