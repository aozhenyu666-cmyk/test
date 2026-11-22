import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../bank/bank_text_extract.dart';
import '../channels/proactive_state.dart';
import 'review_page.dart';

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
  Map<String, dynamic>? _review;

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
    final both = await Future.wait([_channel.bankOp(), _channel.reviewOp({'action': 'summary'})]);
    final res = both[0];
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (res != null) _bankData = res;
      _review = both[1];
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

  /// 选文件导入：可以一次选多个；Word / Excel 先在手机上抽成文字；每个文件先预览解析结果，确认后再入库。
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
    final preErrors = <String>[];
    final validPreps = <_PreparedUpload>[];
    String firstSample = '';
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
          preErrors.add('「${f.name}」：${ext.problem ?? '是空的'}');
          continue;
        }
        final bankName = picked.length == 1 ? f.name.replaceAll(RegExp(r'\.[^.]+$'), '') : _nameCtrl.text.trim();
        final prep = await _previewUploadText(
          ext.text,
          name: bankName,
          append: first ? _appendMode : true,
          label: f.name,
          extractProblem: ext.problem,
        );
        final res = prep.preview;
        final count = _previewCount(res);
        if (res == null || res['ok'] != true || count == 0) {
          final err = '${res?['error'] ?? (res == null ? '引擎没应答' : '未能识别出题目格式')}';
          preErrors.add('「${f.name}」：$err');
          if (firstSample.isEmpty && '${res?['sample'] ?? ''}'.trim().isNotEmpty) {
            firstSample = '${res?['sample']}';
          }
          continue;
        }
        first = false;
        validPreps.add(prep);
      } on Exception catch (e) {
        preErrors.add('「${f.name}」读取失败：$e');
      }
    }
    if (!mounted) return;
    if (validPreps.isEmpty) {
      final errText = preErrors.isEmpty ? '未能识别出题目格式' : preErrors.join('\n');
      setState(() {
        _busy = false;
        _banner = errText;
      });
      await _showPreviewErrorDialog(title: '文件未能识别题目', error: errText, sample: firstSample);
      return;
    }
    setState(() {
      _busy = false;
      _banner = null;
    });
    final confirmed = await _showImportPreviewSheet(validPreps, preErrors: preErrors);
    if (confirmed != true || !mounted) return;
    setState(() {
      _busy = true;
      _banner = '正在入库…';
    });
    final lines = <String>[...preErrors];
    for (final prep in validPreps) {
      final res = await _commitPrepared(prep);
      lines.add(_reportLine(prep.label, res) + (prep.extractProblem != null ? '（${prep.extractProblem}）' : ''));
    }
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = lines.join('\n');
    });
  }

  /// 一份文字先发给引擎做解析预览：小的一次传，大的分块传（begin → chunk… → finish previewOnly:true）。
  Future<_PreparedUpload> _previewUploadText(
    String text, {
    required String name,
    required bool append,
    String label = '',
    String? extractProblem,
  }) async {
    final bankName = name.isEmpty ? '备考真题库' : name;
    if (utf8.encode(text).length < 10 * 1024) {
      final res = await _channel.bankOp({
        'action': 'import',
        'name': bankName,
        'raw': text,
        'append': append,
        'previewOnly': true,
      });
      return _PreparedUpload(
        label: label,
        bankName: bankName,
        raw: text,
        append: append,
        chunked: false,
        uploadId: null,
        preview: res,
        extractProblem: extractProblem,
      );
    }
    final id = 'u${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}';
    final begin = await _channel.bankOp({'action': 'import-begin', 'uploadId': id});
    if (begin?['ok'] != true) {
      return _PreparedUpload(
        label: label,
        bankName: bankName,
        raw: text,
        append: append,
        chunked: true,
        uploadId: id,
        preview: const {'ok': false, 'error': '引擎没应答（开始上传失败）'},
        extractProblem: extractProblem,
      );
    }
    final parts = chunkText(text);
    for (var i = 0; i < parts.length; i++) {
      if (mounted) setState(() => _banner = '上传${label.isEmpty ? '' : '「$label」'} ${i + 1}/${parts.length}…');
      final r = await _channel.bankOp({'action': 'import-chunk', 'uploadId': id, 'index': i, 'text': parts[i]});
      if (r?['ok'] != true) {
        return _PreparedUpload(
          label: label,
          bankName: bankName,
          raw: text,
          append: append,
          chunked: true,
          uploadId: id,
          preview: {'ok': false, 'error': '第 ${i + 1} 块没传上：${r?['error'] ?? '引擎没应答'}'},
          extractProblem: extractProblem,
        );
      }
    }
    if (mounted) setState(() => _banner = '解析预览${label.isEmpty ? '' : '「$label」'}…');
    final res = await _channel.bankOp({
      'action': 'import-finish',
      'uploadId': id,
      'name': bankName,
      'append': append,
      'previewOnly': true,
    });
    final resolvedId = res?['uploadId'] is String && (res!['uploadId'] as String).isNotEmpty
        ? res['uploadId'] as String
        : id;
    return _PreparedUpload(
      label: label,
      bankName: bankName,
      raw: text,
      append: append,
      chunked: true,
      uploadId: resolvedId,
      preview: res,
      extractProblem: extractProblem,
    );
  }

  /// 确认预览后真正入库。
  Future<Map<String, dynamic>?> _commitPrepared(_PreparedUpload prep) {
    if (prep.chunked) {
      return _channel.bankOp({
        'action': 'import-finish',
        'uploadId': prep.uploadId,
        'name': prep.bankName,
        'append': prep.append,
        'previewOnly': false,
      });
    }
    return _channel.bankOp({
      'action': 'import',
      'name': prep.bankName,
      'raw': prep.raw,
      'append': prep.append,
    });
  }

  List<Map<String, dynamic>> _previewItems(Map<String, dynamic>? res) {
    final raw = res?['items'];
    if (raw is! List) return const [];
    return [for (final item in raw) if (item is Map) Map<String, dynamic>.from(item)];
  }

  int _previewCount(Map<String, dynamic>? res) {
    final items = _previewItems(res);
    return (res?['count'] as num?)?.toInt() ?? (res?['total'] as num?)?.toInt() ?? items.length;
  }

  int _previewSkippedCount(Map<String, dynamic>? res) {
    if (res?['skippedCount'] is num) return (res!['skippedCount'] as num).toInt();
    final skipped = res?['skipped'];
    if (skipped is num) return skipped.toInt();
    if (skipped is List) return skipped.length;
    return 0;
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

  Future<void> _showPreviewErrorDialog({
    required String title,
    required String error,
    required String sample,
  }) async {
    await showDialog<void>(
      context: context,
      builder: (ctx) {
        final theme = Theme.of(ctx);
        final colors = DshColors.of(ctx);
        return AlertDialog(
          key: const ValueKey('bank-import-error-dialog'),
          title: Row(
            children: [
              Icon(AppIcons.error, size: 22, color: colors.danger),
              const SizedBox(width: 8),
              Expanded(child: Text(title)),
            ],
          ),
          content: SizedBox(
            width: 400,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(error, style: theme.textTheme.bodyMedium?.copyWith(color: colors.danger)),
                  if (sample.trim().isNotEmpty) ...[
                    const SizedBox(height: 12),
                    Text('标准格式样例：', style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w700)),
                    const SizedBox(height: 6),
                    Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: colors.codeWellBg,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: colors.codeWellBorder),
                      ),
                      child: SelectableText(
                        sample,
                        style: theme.textTheme.bodySmall?.copyWith(color: colors.codeWellFg),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
          actions: [
            if (sample.trim().isNotEmpty)
              TextButton(
                key: const ValueKey('bank-error-use-sample'),
                onPressed: () {
                  _rawCtrl.text = sample;
                  Navigator.of(ctx).pop();
                  setState(() => _banner = '已填入标准样例，可直接修改或点击预览入库');
                },
                child: const Text('填入该样例'),
              ),
            FilledButton(
              key: const ValueKey('bank-error-close'),
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('知道了'),
            ),
          ],
        );
      },
    );
  }

  Future<bool?> _showImportPreviewSheet(
    List<_PreparedUpload> preps, {
    List<String> preErrors = const [],
  }) {
    final totalCount = preps.fold<int>(0, (s, p) => s + _previewCount(p.preview));
    final totalSkipped = preps.fold<int>(0, (s, p) => s + _previewSkippedCount(p.preview));
    final formats = <String>{
      for (final p in preps)
        if ('${p.preview?['format'] ?? ''}'.trim().isNotEmpty) '${p.preview?['format']}'.trim(),
    };
    final rows = <({String fileLabel, Map<String, dynamic> item, List<String> issues})>[];
    final skippedNotes = <String>[];
    for (final p in preps) {
      for (final item in _previewItems(p.preview)) {
        final issues = [
          for (final x in (item['issues'] is List ? item['issues'] as List : const []))
            if ('$x'.trim().isNotEmpty) '$x'.trim(),
        ];
        rows.add((fileLabel: p.label, item: item, issues: issues));
      }
      if (p.preview?['skipped'] is List) {
        for (final x in (p.preview!['skipped'] as List).take(5)) {
          if (x is Map) {
            final prefix = p.label.isEmpty ? '' : '「${p.label}」';
            skippedNotes.add('$prefix第 ${x['n'] ?? '?'} 题：${x['why'] ?? '跳过'}');
          }
        }
      }
    }
    final suspectCount = rows.where((r) => r.issues.isNotEmpty).length;
    var onlySuspect = false;

    return showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) {
          final theme = Theme.of(ctx);
          final colors = DshColors.of(ctx);
          final shown = onlySuspect ? rows.where((r) => r.issues.isNotEmpty).toList() : rows;
          return SafeArea(
            top: false,
            child: SizedBox(
              height: MediaQuery.sizeOf(ctx).height * 0.76,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '导入前预览',
                          style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                        ),
                      ),
                      if (formats.isNotEmpty)
                        StatusChip(tone: StatusTone.idle, label: formats.join(' / ')),
                      IconButton(
                        tooltip: '关闭',
                        onPressed: () => Navigator.of(ctx).pop(false),
                        icon: const Icon(AppIcons.close, size: 18),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Container(
                    key: const ValueKey('bank-preview-summary'),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: suspectCount > 0
                          ? colors.warningContainer.withValues(alpha: 0.55)
                          : colors.successContainer.withValues(alpha: 0.45),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(
                        color: suspectCount > 0
                            ? colors.warning.withValues(alpha: 0.5)
                            : colors.success.withValues(alpha: 0.4),
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '共 $totalCount 题 · 可疑 $suspectCount 题 · 跳过 $totalSkipped 题',
                          style: theme.textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w700,
                            color: suspectCount > 0 ? colors.onWarningContainer : colors.onSuccessContainer,
                          ),
                        ),
                        if (suspectCount > 0) ...[
                          const SizedBox(height: 6),
                          Wrap(
                            spacing: 8,
                            children: [
                              ChoiceChip(
                                key: const ValueKey('bank-preview-filter-all'),
                                label: Text('全部 (${rows.length})'),
                                selected: !onlySuspect,
                                onSelected: (_) => setSheetState(() => onlySuspect = false),
                              ),
                              ChoiceChip(
                                key: const ValueKey('bank-preview-filter-suspect'),
                                label: Text('仅看可疑 ($suspectCount)'),
                                selected: onlySuspect,
                                onSelected: (_) => setSheetState(() => onlySuspect = true),
                              ),
                            ],
                          ),
                        ],
                        if (preErrors.isNotEmpty) ...[
                          const SizedBox(height: 6),
                          Text(
                            preErrors.join('；'),
                            style: theme.textTheme.bodySmall?.copyWith(color: colors.danger),
                          ),
                        ],
                        if (skippedNotes.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            '跳过说明：${skippedNotes.join('；')}',
                            style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 10),
                  Expanded(
                    child: shown.isEmpty
                        ? Center(
                            child: Text(
                              '共识别出 $totalCount 题（后端未返回明细条目）',
                              style: theme.textTheme.bodyMedium?.copyWith(color: colors.textTertiary),
                            ),
                          )
                        : ListView.separated(
                            itemCount: shown.length,
                            separatorBuilder: (_, _) => const SizedBox(height: 8),
                            itemBuilder: (_, idx) {
                              final row = shown[idx];
                              final item = row.item;
                              final issues = row.issues;
                              final hasIssues = issues.isNotEmpty;
                              final options = item['options'] is List ? item['options'] as List : const [];
                              final ans = '${item['answer'] ?? ''}'.trim();
                              final analysis = '${item['analysis'] ?? ''}'.trim();
                              final qIndex = item['index'] ?? (idx + 1);
                              final qType = '${item['type'] ?? '选择'}'.trim();
                              return Container(
                                key: ValueKey('bank-preview-item-$qIndex'),
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: hasIssues
                                      ? colors.dangerContainer.withValues(alpha: 0.42)
                                      : theme.colorScheme.surfaceContainerLow,
                                  borderRadius: BorderRadius.circular(12),
                                  border: Border.all(
                                    color: hasIssues
                                        ? colors.danger.withValues(alpha: 0.7)
                                        : theme.colorScheme.outlineVariant.withValues(alpha: 0.45),
                                  ),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      children: [
                                        if (row.fileLabel.isNotEmpty) ...[
                                          Text(
                                            '「${row.fileLabel}」',
                                            style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                                          ),
                                          const SizedBox(width: 4),
                                        ],
                                        Text(
                                          '第 $qIndex 题 · $qType',
                                          style: theme.textTheme.labelMedium?.copyWith(
                                            fontWeight: FontWeight.w700,
                                            color: hasIssues ? colors.danger : theme.colorScheme.primary,
                                          ),
                                        ),
                                        const Spacer(),
                                        Text(
                                          '选项 ${options.length} 个 · 答案 ${ans.isEmpty ? '缺失' : ans}'
                                          '${analysis.isNotEmpty ? ' · 有解析' : ''}',
                                          style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
                                        ),
                                      ],
                                    ),
                                    const SizedBox(height: 6),
                                    Text(
                                      '${item['stem'] ?? ''}',
                                      maxLines: 3,
                                      overflow: TextOverflow.ellipsis,
                                      style: theme.textTheme.bodyMedium,
                                    ),
                                    if (hasIssues) ...[
                                      const SizedBox(height: 8),
                                      Wrap(
                                        spacing: 6,
                                        runSpacing: 6,
                                        children: [
                                          for (final issue in issues)
                                            Container(
                                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                              decoration: BoxDecoration(
                                                color: colors.danger.withValues(alpha: 0.15),
                                                borderRadius: BorderRadius.circular(999),
                                                border: Border.all(color: colors.danger.withValues(alpha: 0.55)),
                                              ),
                                              child: Row(
                                                mainAxisSize: MainAxisSize.min,
                                                children: [
                                                  Icon(AppIcons.warning, size: 13, color: colors.danger),
                                                  const SizedBox(width: 4),
                                                  Text(
                                                    issue,
                                                    style: theme.textTheme.labelSmall?.copyWith(
                                                      color: colors.danger,
                                                      fontWeight: FontWeight.w700,
                                                    ),
                                                  ),
                                                ],
                                              ),
                                            ),
                                        ],
                                      ),
                                    ],
                                  ],
                                ),
                              );
                            },
                          ),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(
                          key: const ValueKey('bank-preview-cancel'),
                          onPressed: () => Navigator.of(ctx).pop(false),
                          child: const Text('取消'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        flex: 2,
                        child: FilledButton.icon(
                          key: const ValueKey('bank-preview-confirm'),
                          onPressed: () => Navigator.of(ctx).pop(true),
                          icon: const Icon(AppIcons.success, size: 18),
                          label: Text('确认入库（$totalCount 题）'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            ),
          );
        },
      ),
    );
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
    final prep = await _previewUploadText(raw, name: _nameCtrl.text.trim(), append: _appendMode);
    if (!mounted) return;
    final res = prep.preview;
    final count = _previewCount(res);
    if (res == null || res['ok'] != true || count == 0) {
      final err = '${res?['error'] ?? (res == null ? '引擎没应答' : '未能识别出题目格式')}';
      final sample = '${res?['sample'] ?? ''}';
      setState(() {
        _busy = false;
        _banner = '导入预览失败：$err';
      });
      await _showPreviewErrorDialog(title: '未能识别题目', error: err, sample: sample);
      return;
    }
    setState(() {
      _busy = false;
      _banner = null;
    });
    final confirmed = await _showImportPreviewSheet([prep]);
    if (confirmed != true || !mounted) return;
    setState(() {
      _busy = true;
      _banner = '正在入库…';
    });
    final commitRes = await _commitPrepared(prep);
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (commitRes?['ok'] == true) _rawCtrl.clear();
      _banner = _reportLine('', commitRes);
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
        ListPageCard(
          key: const ValueKey('bank-review'),
          title: '个人复习卡（FSRS）',
          margin: const EdgeInsets.only(bottom: 14),
          children: [
            ListPageRow(
              key: const ValueKey('bank-review-row'),
              icon: AppIcons.recurring,
              title: _review == null
                  ? '复习卡：引擎没应答'
                  : '今天到期 ${_review!['dueCount'] ?? 0} 张 · 共 ${_review!['totalCards'] ?? 0} 张'
                      '${_review!['retention7d'] == null ? '' : ' · 7 天留存 ${_review!['retention7d']}%'}',
              subtitle: '锁屏刷题先出到期卡（带选项的代码判，算 1 题；自己对答案的算 0.7 题），都不进上面的题库答对数',
              subtitleMaxLines: 2,
              chevron: true,
              onTap: () async {
                await ReviewPage.open(context, channel: _channel);
                if (mounted) unawaited(_refresh());
              },
            ),
          ],
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
          child: Material(
            type: MaterialType.transparency,
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
                    key: const ValueKey('bank-name-input'),
                    controller: _nameCtrl,
                    decoration: const InputDecoration(
                      labelText: '题库名称',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    key: const ValueKey('bank-raw-input'),
                    controller: _rawCtrl,
                    minLines: 6,
                    maxLines: 12,
                    decoration: const InputDecoration(
                      labelText: '粘贴题目（1. 题干 / A.B.C.D. / 答案：；也支持 --- 与 【题目】【答案】 标签块）',
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
                          key: const ValueKey('bank-file-import'),
                          onPressed: _busy ? null : _pickFileImport,
                          icon: const Icon(AppIcons.files, size: 18),
                          label: const Text('选文件（可多选）'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        flex: 2,
                        child: FilledButton.icon(
                          key: const ValueKey('bank-import-submit'),
                          onPressed: _busy ? null : _submitImport,
                          icon: const Icon(AppIcons.success, size: 18),
                          label: Text(_busy ? '清洗预览中…' : '预览并入库'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _PreparedUpload {
  const _PreparedUpload({
    required this.label,
    required this.bankName,
    required this.raw,
    required this.append,
    required this.chunked,
    required this.uploadId,
    required this.preview,
    this.extractProblem,
  });

  final String label;
  final String bankName;
  final String raw;
  final bool append;
  final bool chunked;
  final String? uploadId;
  final Map<String, dynamic>? preview;
  final String? extractProblem;
}
