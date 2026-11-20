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
import '../bank/bank_text_extract.dart';
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

  /// 选文件导入：可以一次选多个；Word / Excel 先在手机上抽成文字；每个文件分块传给引擎（引擎收请求上限 16 KB）。
  Future<void> _pickFileImport() async {
    List<PlatformFile> picked;
    try {
      picked = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['json', 'txt', 'md', 'csv', 'tsv', 'docx', 'xlsx', 'doc', 'pdf'],
      );
    } on Exception catch (e) {
      if (mounted) setState(() => _banner = '打不开文件选择器：$e');
      return;
    }
    if (picked.isEmpty || !mounted) return;
    setState(() {
      _busy = true;
      _banner = '读取 ${picked.length} 个文件…';
    });
    final lines = <String>[];
    var first = true;
    for (final f in picked) {
      try {
        final bytes = BytesBuilder(copy: false);
        await for (final chunk in f.readAsByteStream()) {
          bytes.add(chunk);
          if (bytes.length > 20 * 1024 * 1024) throw const FormatException('文件超过 20MB');
        }
        final ext = extractBankText(f.name, bytes.takeBytes());
        if (ext.text.trim().isEmpty) {
          lines.add('「${f.name}」：${ext.problem ?? '是空的'}');
          continue;
        }
        final res = await _uploadText(
          ext.text,
          name: picked.length == 1 ? f.name.replaceAll(RegExp(r'\.[^.]+$'), '') : _nameCtrl.text.trim(),
          append: first ? _appendMode : true,
          label: f.name,
        );
        first = false;
        lines.add(_reportLine(f.name, res) + (ext.problem != null ? '（${ext.problem}）' : ''));
      } on Exception catch (e) {
        lines.add('「${f.name}」读取失败：$e');
      }
    }
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = lines.join('\n');
    });
  }

  /// 一份文字导进引擎：小的一次传，大的分块传（begin → chunk… → finish）。
  Future<Map<String, dynamic>?> _uploadText(String text, {required String name, required bool append, String label = ''}) async {
    final bankName = name.isEmpty ? '备考真题库' : name;
    if (utf8.encode(text).length < 10 * 1024) {
      return _channel.bankOp({'action': 'import', 'name': bankName, 'raw': text, 'append': append});
    }
    final id = 'u${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}';
    final begin = await _channel.bankOp({'action': 'import-begin', 'uploadId': id});
    if (begin?['ok'] != true) return {'ok': false, 'error': '引擎没应答（开始上传失败）'};
    final parts = chunkText(text);
    for (var i = 0; i < parts.length; i++) {
      if (mounted) setState(() => _banner = '上传${label.isEmpty ? '' : '「$label」'} ${i + 1}/${parts.length}…');
      final r = await _channel.bankOp({'action': 'import-chunk', 'uploadId': id, 'index': i, 'text': parts[i]});
      if (r?['ok'] != true) return {'ok': false, 'error': '第 ${i + 1} 块没传上：${r?['error'] ?? '引擎没应答'}'};
    }
    if (mounted) setState(() => _banner = '解析${label.isEmpty ? '' : '「$label」'}…');
    return _channel.bankOp({'action': 'import-finish', 'uploadId': id, 'name': bankName, 'append': append});
  }

  String _reportLine(String label, Map<String, dynamic>? res) {
    final who = label.isEmpty ? '' : '「$label」';
    if (res == null) return '$who导入失败：引擎没应答';
    if (res['ok'] != true) return '$who导入失败：${res['error'] ?? '格式暂未识别'}';
    final skipped = (res['skippedCount'] as num?)?.toInt() ?? 0;
    final why = res['skipped'] is List
        ? [for (final x in (res['skipped'] as List).take(3)) if (x is Map) '第 ${x['n']} 题${x['why']}'].join('、')
        : '';
    return '✓ $who进了 ${res['importedCount'] ?? 0} 道，现在共 ${res['bankTotal'] ?? 0} 题'
        '${skipped > 0 ? '；$skipped 道没进（$why${skipped > 3 ? '…' : ''}）' : ''}';
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
      setState(() => _banner = '请先粘贴题目文本，或选文件（txt / docx / xlsx / csv / json，可多选）');
      return;
    }
    setState(() {
      _busy = true;
      _banner = null;
    });
    final res = await _uploadText(raw, name: _nameCtrl.text.trim(), append: _appendMode);
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (res?['ok'] == true) _rawCtrl.clear();
      _banner = _reportLine('', res);
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
                      child: Text('导入题目（文本 / Word / Excel / CSV / JSON，可多选）', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
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
                    labelText: '粘贴题目（1. 题干 / A.B.C.D. / 答案：；答案也可以统一放在最后「参考答案 1.A 2.B」或「1-5 ABCDA」）',
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
                        label: const Text('选文件（可多选）'),
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
