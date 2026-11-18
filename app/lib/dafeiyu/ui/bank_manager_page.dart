import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../../ui/common/overview_card.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';

/// 备考题库与锁屏刷题解锁中心（默认：答对 10 题解锁 5 分钟）。
class BankManagerPage extends StatefulWidget {
  const BankManagerPage({super.key, this.channel});

  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) => Navigator.of(context).push(
    MaterialPageRoute<void>(builder: (_) => BankManagerPage(channel: channel)),
  );

  @override
  State<BankManagerPage> createState() => _BankManagerPageState();
}

class _BankManagerPageState extends State<BankManagerPage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  final _nameCtrl = TextEditingController(text: '考公/备考真题库');
  final _rawCtrl = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  bool _appendMode = true;
  String? _banner;
  Map<String, dynamic> _bankData = const {};

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _rawCtrl.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final res = await _channel.bankOp();
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (res != null) _bankData = res;
    });
  }

  Future<void> _updateConfig({int? requiredCount, int? unlockMinutes}) async {
    setState(() => _busy = true);
    final res = await _channel.bankOp({
      'action': 'set_config',
      'requiredCount': ?requiredCount,
      'unlockMinutes': ?unlockMinutes,
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true ? '已保存锁屏刷题解锁参数' : '保存失败，请检查引擎连接';
    });
  }

  Future<void> _pickFileImport() async {
    try {
      final picked = await FilePicker.pickFile(
        type: FileType.custom,
        allowedExtensions: const ['json', 'txt', 'md'],
      );
      if (picked == null) return;
      final bytes = BytesBuilder(copy: false);
      await for (final chunk in picked.readAsByteStream()) {
        bytes.add(chunk);
        if (bytes.length > 4 * 1024 * 1024) {
          throw const FormatException('题库文件过大（超过 4MB）');
        }
      }
      final content = utf8.decode(bytes.takeBytes(), allowMalformed: true);
      if (!mounted) return;
      final baseName = picked.name.replaceAll(RegExp(r'\.[^.]+$'), '');
      setState(() {
        _rawCtrl.text = content;
        if (baseName.isNotEmpty) _nameCtrl.text = baseName;
        _banner = '已载入文件（${content.length} 字符），点击下方「立即清洗入库」即可完成导入';
      });
    } on Exception catch (e) {
      if (!mounted) return;
      setState(() => _banner = '读取文件失败：$e');
    }
  }

  void _fillSampleTemplate() {
    _rawCtrl.text = '''1. 在宏观经济政策中，政府提高财政支出、降低税率通常属于哪种政策？
A. 紧缩性财政政策
B. 扩张性财政政策
C. 紧缩性货币政策
D. 中性产业政策
答案：B
解析：增支减税会扩大社会总需求，属于典型的扩张性财政政策。

2. 【言语理解】体系化思考与零散信息堆砌的根本区别在于是否提炼出什么？
A. 更多字数摘要
B. 可迁移的因果链与行动规则
C. 复杂名词列表
D. 个人主观情绪
答案：B
解析：信息只有压缩为“触发场景 → 底层因果机制 → 下次行动”才能产生决策复利。''';
    setState(() => _banner = '已填入示例模板，可按此格式粘贴更多考公/专业题');
  }

  Future<void> _submitImport() async {
    final raw = _rawCtrl.text.trim();
    if (raw.isEmpty) {
      setState(() => _banner = '请先粘贴题目文本或从手机选择 JSON/TXT 题库');
      return;
    }
    setState(() {
      _busy = true;
      _banner = null;
    });
    final res = await _channel.bankOp({
      'action': 'import',
      'name': _nameCtrl.text.trim().isEmpty ? '备考真题库' : _nameCtrl.text.trim(),
      'raw': raw,
      'append': _appendMode,
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (res?['ok'] == true) {
        _rawCtrl.clear();
        _banner = '✓ 成功导入 ${res?['importedCount'] ?? 0} 道题，当前库共 ${res?['bankTotal'] ?? 0} 题！';
      } else {
        _banner = '导入失败：${res?['error'] ?? '格式暂未识别'}';
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final cfg = _bankData['config'] is Map ? _bankData['config'] as Map : const {};
    final stats = _bankData['stats'] is Map ? _bankData['stats'] as Map : const {};
    final total = (_bankData['bankTotal'] as num?)?.toInt() ?? 0;
    final wrong = (_bankData['wrongCount'] as num?)?.toInt() ?? 0;
    final reqCount = (cfg['requiredCount'] as num?)?.toInt() ?? 10;
    final unlockMin = (cfg['unlockMinutes'] as num?)?.toInt() ?? 5;
    final answered = (stats['answered'] as num?)?.toInt() ?? 0;
    final correct = (stats['correct'] as num?)?.toInt() ?? 0;
    final acc = answered > 0 ? '${((correct * 100) / answered).round()}%' : '--';

    return AppPage.list(
      title: '备考题库中心',
      subtitle: const Text('导入真题后，触发锁屏即可做题换休息时长（再也不用按数字或走 150 步）'),
      children: [
        if (_banner != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: GlassSurface(
              tier: GlassTier.card,
              borderRadius: AppShape.radiusMd,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(_banner!, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.primary)),
              ),
            ),
          ),
        OverviewCard(
          margin: const EdgeInsets.only(bottom: 14),
          leading: const OverviewGlyph(AppIcons.skill),
          title: Text('${_bankData['bankName'] ?? '未导入题库'}（共 $total 题）'),
          subtitle: Text('待攻克错题 $wrong 题 · 累计作答 $answered 次（正确率 $acc）'),
          trailing: StatusChip(
            tone: total >= reqCount ? StatusTone.ok : StatusTone.warn,
            label: total >= reqCount ? '已就绪' : '待充实',
          ),
          footer: OverviewFooter(
            tone: StatusTone.idle,
            text: _loading ? '读取中…' : '当前解锁规则：答对 $reqCount 题 → 临时放行 $unlockMin 分钟',
          ),
        ),
        GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('锁屏刷题解锁规则', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 10),
                Text('每次需答对题数', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                const SizedBox(height: 6),
                SegmentedPill<int>(
                  segments: const [
                    PillSegment(5, '5 题'),
                    PillSegment(10, '10 题（推荐）'),
                    PillSegment(15, '15 题'),
                  ],
                  selected: [5, 10, 15].contains(reqCount) ? reqCount : 10,
                  onChanged: (v) {
                    if (!_busy) unawaited(_updateConfig(requiredCount: v));
                  },
                ),
                const SizedBox(height: 12),
                Text('达标后临时解锁时长', style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary)),
                const SizedBox(height: 6),
                SegmentedPill<int>(
                  segments: const [
                    PillSegment(3, '3 分钟'),
                    PillSegment(5, '5 分钟（推荐）'),
                    PillSegment(10, '10 分钟'),
                  ],
                  selected: [3, 5, 10].contains(unlockMin) ? unlockMin : 5,
                  onChanged: (v) {
                    if (!_busy) unawaited(_updateConfig(unlockMinutes: v));
                  },
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
                    Expanded(
                      child: Text('导入题目（JSON / 纯文本 / 文件）', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                    ),
                    TextButton.icon(
                      onPressed: _busy ? null : _fillSampleTemplate,
                      icon: const Icon(AppIcons.tip, size: 16),
                      label: const Text('示例格式'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _nameCtrl,
                  decoration: const InputDecoration(
                    labelText: '题库名称',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: _rawCtrl,
                  minLines: 6,
                  maxLines: 12,
                  decoration: const InputDecoration(
                    labelText: '粘贴题目内容（支持 1. 题干 / A.B.C.D. / 答案： / 解析： 或 JSON）',
                    alignLabelWithHint: true,
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 8),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  value: _appendMode,
                  onChanged: (v) => setState(() => _appendMode = v),
                  title: const Text('追加到现有题库'),
                  subtitle: Text(_appendMode ? '保留原有题目并在后面新增' : '用这次导入替换整份题库'),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        onPressed: _busy ? null : _pickFileImport,
                        icon: const Icon(AppIcons.files, size: 18),
                        label: const Text('选文件导入'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      flex: 2,
                      child: FilledButton.icon(
                        onPressed: _busy ? null : _submitImport,
                        icon: const Icon(AppIcons.success, size: 18),
                        label: Text(_busy ? '清洗入库中…' : '立即清洗入库'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
