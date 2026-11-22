import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';

import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';

String _practiceKindLabel(String kind) => switch (kind) {
  'card' => '展示卡',
  'short_qa' => '小问题',
  _ => '选择题',
};

String _defaultPracticeTitle(String scope, String kind, String bankName) {
  final scopeLabel = switch (scope) {
    'wrong_book' => '错题本攻坚',
    'fsrs_due' => 'FSRS 今日复习',
    'starred' => '我的收藏练习',
    'unseen' => '未刷专项',
    _ => bankName.isNotEmpty ? bankName : '综合刷题',
  };
  final kindSuffix = switch (kind) {
    'mcq' => ' · 选择',
    'card' => ' · 展示卡',
    'short_qa' => ' · 小问题',
    _ => '',
  };
  return '$scopeLabel$kindSuffix';
}

String _formatSecondsClock(int sec) {
  final s = sec < 0 ? 0 : sec;
  final mm = (s ~/ 60).toString().padLeft(2, '0');
  final ss = (s % 60).toString().padLeft(2, '0');
  return '$mm:$ss';
}

String _normalizeToken(String raw) {
  var s = raw.trim().toUpperCase();
  if (s.startsWith('(') || s.startsWith('（')) {
    s = s.substring(1);
  }
  return s.replaceAll(RegExp(r'[）).、．\s]+$'), '');
}

const Set<String> _selfPositiveTokens = {
  'PASS',
  'REMEMBER',
  'KNOWN',
  'UNDERSTOOD',
  'READ',
  'YES',
  'OK',
  'HARD',
  'GOOD',
  'EASY',
  '我读懂了',
  '答上了',
  '记得',
};

const Set<String> _selfNegativeTokens = {
  'FORGOT',
  'UNKNOWN',
  'UNFAMILIAR',
  'MISSED',
  'FAIL',
  'NO',
  'AGAIN',
  '还不熟',
  '没答上',
  '忘了',
};

class _PracticeAnswerRecord {
  const _PracticeAnswerRecord({
    required this.questionId,
    required this.chosenAnswer,
    required this.correct,
    required this.selfJudged,
    required this.expectedAnswer,
    required this.explain,
    required this.rating,
    required this.elapsedMs,
    required this.streakCorrect,
    required this.inWrongBook,
    required this.addedToWrongBook,
    required this.graduatedFromWrongBook,
    required this.scheduledDays,
    required this.nextDueAt,
  });

  final String questionId;
  final String chosenAnswer;
  final bool correct;
  final bool selfJudged;
  final String expectedAnswer;
  final String explain;
  final int rating;
  final int elapsedMs;
  final int streakCorrect;
  final bool inWrongBook;
  final bool addedToWrongBook;
  final bool graduatedFromWrongBook;
  final int scheduledDays;
  final int nextDueAt;
}

/// 成熟刷题软件级全屏刷题与 FSRS 复习页面（支持选择题、知识展示卡、小问题、答题卡跳转与战报结算）。
class QuizPracticePage extends StatefulWidget {
  const QuizPracticePage({
    super.key,
    this.channel,
    this.bankId = '',
    this.bankName = '',
    this.kind = '',
    this.scope = 'all',
    this.limit = 20,
    this.title,
    this.initialQuestions,
  });

  final ProactiveChannel? channel;
  final String bankId;
  final String bankName;
  final String kind;
  final String scope;
  final int limit;
  final String? title;
  final List<Map<String, dynamic>>? initialQuestions;

  static Future<bool?> open(
    BuildContext context, {
    ProactiveChannel? channel,
    String bankId = '',
    String bankName = '',
    String kind = '',
    String scope = 'all',
    int limit = 20,
    String? title,
    List<Map<String, dynamic>>? initialQuestions,
  }) => Navigator.of(context).push<bool>(
    MaterialPageRoute<bool>(
      builder: (_) => QuizPracticePage(
        channel: channel,
        bankId: bankId,
        bankName: bankName,
        kind: kind,
        scope: scope,
        limit: limit,
        title: title,
        initialQuestions: initialQuestions,
      ),
    ),
  );

  @override
  State<QuizPracticePage> createState() => _QuizPracticePageState();
}

class _QuizPracticePageState extends State<QuizPracticePage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();

  List<Map<String, dynamic>> _questions = const [];
  final Map<String, _PracticeAnswerRecord> _records = {};
  final ValueNotifier<int> _elapsedSec = ValueNotifier<int>(0);
  Timer? _ticker;
  DateTime? _questionStartedAt;

  int _currentIndex = 0;
  bool _loading = true;
  bool _submitting = false;
  bool _revealed = false;
  bool _finished = false;
  bool _hasChanges = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.initialQuestions != null) {
      _questions = [
        for (final q in widget.initialQuestions!) Map<String, dynamic>.from(q),
      ];
      _loading = false;
      _startQuestionTimer();
    } else {
      unawaited(_loadSession());
    }
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _elapsedSec.dispose();
    super.dispose();
  }

  String get _pageTitle =>
      widget.title ?? _defaultPracticeTitle(widget.scope, widget.kind, widget.bankName);

  Map<String, dynamic>? get _currentQuestion =>
      (_currentIndex >= 0 && _currentIndex < _questions.length) ? _questions[_currentIndex] : null;

  _PracticeAnswerRecord? get _currentRecord {
    final q = _currentQuestion;
    if (q == null) return null;
    return _records['${q['id'] ?? ''}'];
  }

  void _startQuestionTimer() {
    _ticker?.cancel();
    _elapsedSec.value = 0;
    if (_loading || _finished || _questions.isEmpty || _currentRecord != null) {
      _questionStartedAt = null;
      return;
    }
    _questionStartedAt = DateTime.now();
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted || _finished || _currentRecord != null) {
        _ticker?.cancel();
        return;
      }
      _elapsedSec.value += 1;
    });
  }

  void _stopQuestionTimer() {
    _ticker?.cancel();
    _ticker = null;
  }

  Future<void> _loadSession({List<String> excludeIds = const []}) async {
    _stopQuestionTimer();
    setState(() {
      _loading = true;
      _error = null;
      _finished = false;
      _revealed = false;
      _currentIndex = 0;
      _records.clear();
    });

    Map<String, dynamic>? res = await _channel.bankOp({
      'action': 'practice_session',
      if (widget.bankId.isNotEmpty) 'bankId': widget.bankId,
      if (widget.kind.isNotEmpty) 'kind': widget.kind,
      'scope': widget.scope,
      'limit': widget.limit,
      if (excludeIds.isNotEmpty) 'excludeIds': excludeIds,
    });

    // 若引擎尚未接入新 route 但指定了 bankId，优雅回退到 get_bank 组装
    if ((res == null || res['ok'] != true) && widget.bankId.isNotEmpty) {
      final fallback = await _channel.bankOp({
        'action': 'get_bank',
        'bankId': widget.bankId,
      });
      if (fallback != null && fallback['ok'] == true && fallback['questions'] is List) {
        final bankMeta = fallback['bank'] is Map ? fallback['bank'] as Map : const {};
        final bName = '${bankMeta['name'] ?? widget.bankName}'.trim();
        final rawList = <Map<String, dynamic>>[];
        for (final item in fallback['questions'] as List) {
          if (item is! Map) continue;
          final m = Map<String, dynamic>.from(item);
          final k = '${m['kind'] ?? 'mcq'}';
          if (widget.kind.isNotEmpty && k != widget.kind) continue;
          if (widget.scope == 'wrong_book' && m['inWrongPool'] != true) continue;
          rawList.add({
            ...m,
            'bankId': widget.bankId,
            'bankName': bName.isNotEmpty ? bName : '我的题库',
            'explain': m['explain'] ?? m['analysis'] ?? '',
          });
        }
        res = {'ok': true, 'questions': rawList.take(widget.limit).toList()};
      }
    }

    if (!mounted) return;
    if (res == null || res['ok'] != true) {
      setState(() {
        _loading = false;
        _questions = const [];
        _error = '${res?['error'] ?? '未能连上引擎或题库接口'}';
      });
      return;
    }

    final rawQs = res['questions'];
    final list = <Map<String, dynamic>>[
      if (rawQs is List)
        for (final item in rawQs)
          if (item is Map) Map<String, dynamic>.from(item),
    ];

    setState(() {
      _loading = false;
      _questions = list;
      _currentIndex = 0;
      _revealed = false;
      _finished = false;
    });
    _startQuestionTimer();
  }

  _PracticeAnswerRecord _localFallbackJudge(
    Map<String, dynamic> q,
    String answer,
    String? rating,
    int elapsedMs,
  ) {
    final qid = '${q['id'] ?? ''}';
    final kind = '${q['kind'] ?? 'mcq'}';
    final opts = q['options'] is List ? [for (final o in q['options'] as List) '$o'] : const <String>[];
    final selfJudged = kind != 'mcq' || opts.isEmpty;
    final expectedRaw = '${q['answer'] ?? ''}'.trim();
    final expNorm = _normalizeToken(expectedRaw);
    final gotNorm = _normalizeToken(answer);
    var correct = false;
    if (selfJudged) {
      if (_selfNegativeTokens.contains(gotNorm)) {
        correct = false;
      } else if (_selfPositiveTokens.contains(gotNorm) || (gotNorm.isNotEmpty && gotNorm == expNorm)) {
        correct = true;
      }
    } else if (gotNorm.isNotEmpty && gotNorm == expNorm) {
      correct = true;
    } else if (opts.isNotEmpty && RegExp(r'^[A-H]$').hasMatch(gotNorm)) {
      final idx = gotNorm.codeUnitAt(0) - 65;
      if (idx >= 0 && idx < opts.length && _normalizeToken(opts[idx]) == expNorm) {
        correct = true;
      }
    }
    final prevStreak = (q['streakCorrect'] as num?)?.toInt() ?? 0;
    final wasWrong = q['inWrongBook'] == true;
    final nextStreak = correct ? prevStreak + 1 : 0;
    final nextWrong = correct ? (wasWrong && nextStreak < 2) : true;
    final resolvedRating = !correct
        ? 1
        : switch (rating?.toLowerCase()) {
            'hard' => 2,
            'easy' => 4,
            _ => 3,
          };
    final scheduledDays = !correct
        ? 0
        : switch (resolvedRating) {
            2 => 1,
            4 => 4,
            _ => 2,
          };
    return _PracticeAnswerRecord(
      questionId: qid,
      chosenAnswer: answer,
      correct: correct,
      selfJudged: selfJudged,
      expectedAnswer: expectedRaw,
      explain: '${q['explain'] ?? q['analysis'] ?? ''}',
      rating: resolvedRating,
      elapsedMs: elapsedMs,
      streakCorrect: nextStreak,
      inWrongBook: nextWrong,
      addedToWrongBook: !correct && !wasWrong,
      graduatedFromWrongBook: correct && wasWrong && !nextWrong,
      scheduledDays: scheduledDays,
      nextDueAt: 0,
    );
  }

  Future<void> _submitAnswer(
    Map<String, dynamic> q, {
    required String answer,
    String? rating,
  }) async {
    final qid = '${q['id'] ?? ''}';
    if (qid.isEmpty || _submitting || _records.containsKey(qid)) return;
    _stopQuestionTimer();
    final elapsedMs = _questionStartedAt != null
        ? DateTime.now().difference(_questionStartedAt!).inMilliseconds.clamp(0, 3600000)
        : _elapsedSec.value * 1000;

    setState(() => _submitting = true);
    final res = await _channel.bankOp({
      'action': 'submit_practice_answer',
      'questionId': qid,
      'answer': answer,
      'rating': ?rating,
      'elapsedMs': elapsedMs,
    });
    if (!mounted) return;

    final _PracticeAnswerRecord record;
    if (res != null && res['ok'] == true) {
      final fsrs = res['fsrs'] is Map ? res['fsrs'] as Map : const {};
      record = _PracticeAnswerRecord(
        questionId: qid,
        chosenAnswer: answer,
        correct: res['correct'] == true,
        selfJudged: res['selfJudged'] == true,
        expectedAnswer: '${res['expectedAnswer'] ?? res['correctAnswer'] ?? q['answer'] ?? ''}',
        explain: '${res['explain'] ?? q['explain'] ?? q['analysis'] ?? ''}',
        rating: (res['rating'] as num?)?.toInt() ?? 3,
        elapsedMs: elapsedMs,
        streakCorrect: (res['streakCorrect'] as num?)?.toInt() ?? 0,
        inWrongBook: res['inWrongBook'] == true,
        addedToWrongBook: res['addedToWrongBook'] == true,
        graduatedFromWrongBook: res['graduatedFromWrongBook'] == true,
        scheduledDays: (res['scheduledDays'] as num?)?.toInt() ??
            (fsrs['scheduledDays'] as num?)?.toInt() ??
            0,
        nextDueAt: (res['nextDueAt'] as num?)?.toInt() ??
            (fsrs['dueAt'] as num?)?.toInt() ??
            0,
      );
    } else {
      record = _localFallbackJudge(q, answer, rating, elapsedMs);
    }

    setState(() {
      _submitting = false;
      _revealed = true;
      _hasChanges = true;
      _records[qid] = record;
      _questions[_currentIndex] = {
        ..._questions[_currentIndex],
        'inWrongBook': record.inWrongBook,
        'streakCorrect': record.streakCorrect,
        'dueAt': record.nextDueAt,
      };
    });
  }

  Future<void> _toggleStar(Map<String, dynamic> q) async {
    final qid = '${q['id'] ?? ''}';
    if (qid.isEmpty) return;
    final nextStarred = q['starred'] != true;
    setState(() {
      _hasChanges = true;
      _questions[_currentIndex] = {
        ..._questions[_currentIndex],
        'starred': nextStarred,
      };
    });
    final res = await _channel.bankOp({
      'action': 'toggle_star',
      'questionId': qid,
      'starred': nextStarred,
    });
    if (!mounted) return;
    if (res != null && res['ok'] == true && res['starred'] is bool) {
      setState(() {
        _questions[_currentIndex] = {
          ..._questions[_currentIndex],
          'starred': res['starred'] == true,
        };
      });
    }
  }

  void _jumpTo(int index) {
    if (index < 0 || index >= _questions.length) return;
    setState(() {
      _currentIndex = index;
      _finished = false;
      _revealed = _records.containsKey('${_questions[index]['id'] ?? ''}');
    });
    _startQuestionTimer();
  }

  void _goNextOrFinish() {
    if (_currentIndex < _questions.length - 1) {
      _jumpTo(_currentIndex + 1);
    } else {
      _finishSession();
    }
  }

  void _finishSession() {
    _stopQuestionTimer();
    setState(() => _finished = true);
  }

  void _retryRoundWrongs() {
    final wrongQs = <Map<String, dynamic>>[
      for (final q in _questions)
        if (_records['${q['id'] ?? ''}']?.correct == false) Map<String, dynamic>.from(q),
    ];
    if (wrongQs.isEmpty) return;
    setState(() {
      _questions = wrongQs;
      _records.clear();
      _currentIndex = 0;
      _revealed = false;
      _finished = false;
    });
    _startQuestionTimer();
  }

  Future<void> _showAnswerSheet() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (ctx) {
        final theme = Theme.of(ctx);
        final colors = DshColors.of(ctx);
        return SafeArea(
          top: false,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(ctx).height * 0.72),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '答题卡（已答 ${_records.length} / ${_questions.length}）',
                          style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                        ),
                      ),
                      IconButton(
                        tooltip: '关闭',
                        onPressed: () => Navigator.of(ctx).pop(),
                        icon: const Icon(AppIcons.close, size: 18),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 12,
                    runSpacing: 6,
                    children: [
                      _LegendDot(color: colors.success, label: '答对'),
                      _LegendDot(color: colors.danger, label: '答错'),
                      _LegendDot(color: theme.colorScheme.primary, label: '当前'),
                      _LegendDot(color: colors.textDisabled, label: '未做'),
                    ],
                  ),
                  const SizedBox(height: 14),
                  Flexible(
                    child: GridView.builder(
                      shrinkWrap: true,
                      itemCount: _questions.length,
                      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                        crossAxisCount: 5,
                        mainAxisSpacing: 10,
                        crossAxisSpacing: 10,
                        childAspectRatio: 1.1,
                      ),
                      itemBuilder: (_, idx) {
                        final item = _questions[idx];
                        final rec = _records['${item['id'] ?? ''}'];
                        final isCurrent = idx == _currentIndex && !_finished;
                        final Color bg;
                        final Color fg;
                        final Color border;
                        if (rec != null && rec.correct) {
                          bg = colors.successContainer;
                          fg = colors.onSuccessContainer;
                          border = colors.success;
                        } else if (rec != null && !rec.correct) {
                          bg = colors.dangerContainer;
                          fg = colors.onDangerContainer;
                          border = colors.danger;
                        } else if (isCurrent) {
                          bg = theme.colorScheme.primaryContainer;
                          fg = theme.colorScheme.onPrimaryContainer;
                          border = theme.colorScheme.primary;
                        } else {
                          bg = theme.colorScheme.surfaceContainerLow;
                          fg = colors.textTertiary;
                          border = theme.colorScheme.outlineVariant.withValues(alpha: 0.6);
                        }
                        return InkWell(
                          key: ValueKey('practice-sheet-cell-$idx'),
                          borderRadius: AppShape.radiusSm,
                          onTap: () {
                            Navigator.of(ctx).pop();
                            _jumpTo(idx);
                          },
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: bg,
                              borderRadius: AppShape.radiusSm,
                              border: Border.all(
                                color: isCurrent ? theme.colorScheme.primary : border,
                                width: isCurrent ? 2 : 1,
                              ),
                            ),
                            child: Center(
                              child: Text(
                                '${idx + 1}',
                                style: AppType.tabular(
                                  theme.textTheme.labelLarge!.copyWith(
                                    color: fg,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                  const SizedBox(height: 14),
                  FilledButton.icon(
                    key: const ValueKey('practice-sheet-submit-btn'),
                    onPressed: () {
                      Navigator.of(ctx).pop();
                      _finishSession();
                    },
                    icon: const Icon(AppIcons.done, size: 18),
                    label: const Text('交卷并查看战报'),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildImagesSection(ThemeData theme, DshColors colors, List<dynamic> rawImages) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final item in rawImages)
          if (item is Map || item is String) ...[
            const SizedBox(height: 10),
            Builder(
              builder: (_) {
                final path = item is String ? item : '${(item as Map)['path'] ?? ''}';
                final caption = item is Map ? '${item['caption'] ?? ''}'.trim() : '';
                var exists = false;
                File? localFile;
                if (path.trim().isNotEmpty) {
                  try {
                    localFile = File(path);
                    exists = localFile.existsSync();
                  } on Object {
                    exists = false;
                  }
                }
                if (exists && localFile != null) {
                  return ClipRRect(
                    borderRadius: AppShape.radiusSm,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Image.file(
                          localFile,
                          height: 160,
                          fit: BoxFit.contain,
                          errorBuilder: (_, _, _) => _PracticeImagePlaceholder(path: path, caption: caption),
                        ),
                        if (caption.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.only(top: 4),
                            child: Text(caption, style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary)),
                          ),
                      ],
                    ),
                  );
                }
                return _PracticeImagePlaceholder(path: path, caption: caption);
              },
            ),
          ],
      ],
    );
  }

  Widget _buildTopBar(ThemeData theme, DshColors colors) {
    final q = _currentQuestion;
    final starred = q?['starred'] == true;
    final total = _questions.length;
    final progress = total > 0 ? (_currentIndex + 1) / total : 0.0;

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          BackdropGroup(
            child: Row(
              children: [
                GlassIconButton(
                  key: const ValueKey('practice-back'),
                  icon: AppIcons.back,
                  tooltip: '返回',
                  onPressed: () => Navigator.of(context).pop(_hasChanges),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _pageTitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                      ),
                      if (total > 0 && !_finished)
                        Row(
                          children: [
                            Text(
                              '${_currentIndex + 1} / $total',
                              key: const ValueKey('practice-progress-text'),
                              style: AppType.tabular(
                                theme.textTheme.labelMedium!.copyWith(
                                  color: theme.colorScheme.primary,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ),
                            const SizedBox(width: 10),
                            Icon(AppIcons.commandTimeout, size: 13, color: colors.textTertiary),
                            const SizedBox(width: 3),
                            ValueListenableBuilder<int>(
                              valueListenable: _elapsedSec,
                              builder: (_, sec, _) => Text(
                                _formatSecondsClock(sec),
                                key: const ValueKey('practice-timer-text'),
                                style: AppType.tabular(
                                  theme.textTheme.labelMedium!.copyWith(color: colors.textTertiary),
                                ),
                              ),
                            ),
                          ],
                        ),
                    ],
                  ),
                ),
                if (q != null && !_finished) ...[
                  GlassIconButton(
                    key: const ValueKey('practice-star-btn'),
                    icon: starred ? AppIcons.favoriteActive : AppIcons.favorite,
                    tooltip: starred ? '取消收藏' : '收藏本题',
                    onPressed: () => unawaited(_toggleStar(q)),
                  ),
                  const SizedBox(width: 8),
                  GlassIconButton(
                    key: const ValueKey('practice-sheet-btn'),
                    icon: AppIcons.allFeatures,
                    tooltip: '答题卡',
                    onPressed: () => unawaited(_showAnswerSheet()),
                  ),
                ],
              ],
            ),
          ),
          if (total > 0 && !_finished) ...[
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: AppShape.radiusXs,
              child: LinearProgressIndicator(
                value: progress.clamp(0.0, 1.0),
                minHeight: 4,
                backgroundColor: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.6),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildQuestionHeaderChips(ThemeData theme, DshColors colors, Map<String, dynamic> q) {
    final bankName = '${q['bankName'] ?? ''}'.trim();
    final kind = '${q['kind'] ?? 'mcq'}';
    final type = '${q['type'] ?? ''}'.trim();
    final inWrongBook = q['inWrongBook'] == true;
    final streak = (q['streakCorrect'] as num?)?.toInt() ?? 0;
    final starred = q['starred'] == true;

    return Wrap(
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        if (bankName.isNotEmpty)
          StatusChip(tone: StatusTone.idle, label: bankName),
        _PracticeTag(
          label: _practiceKindLabel(kind),
          bg: theme.colorScheme.secondaryContainer.withValues(alpha: 0.8),
          fg: theme.colorScheme.onSecondaryContainer,
        ),
        if (type.isNotEmpty)
          _PracticeTag(
            label: type,
            bg: theme.colorScheme.surfaceContainerHigh,
            fg: colors.textTertiary,
          ),
        if (inWrongBook)
          _PracticeTag(
            label: '错题本 · 连对 $streak/2',
            bg: colors.warningContainer,
            fg: colors.onWarningContainer,
          ),
        if (starred)
          _PracticeTag(
            label: '已收藏',
            bg: colors.infoContainer,
            fg: colors.onInfoContainer,
          ),
      ],
    );
  }

  Widget _buildMcqBody(ThemeData theme, DshColors colors, Map<String, dynamic> q, _PracticeAnswerRecord? rec) {
    final options = q['options'] is List ? [for (final o in q['options'] as List) '$o'] : const <String>[];
    final expNorm = _normalizeToken(rec?.expectedAnswer ?? '${q['answer'] ?? ''}');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < options.length; i++) ...[
          if (i > 0) const SizedBox(height: 10),
          Builder(
            builder: (_) {
              final letter = String.fromCharCode(65 + i);
              final isChosen = rec != null && _normalizeToken(rec.chosenAnswer) == letter;
              final isCorrectOption = rec != null &&
                  (expNorm == letter || _normalizeToken(options[i]) == expNorm);
              Color bg = theme.colorScheme.surfaceContainerLow;
              Color border = theme.colorScheme.outlineVariant.withValues(alpha: 0.55);
              Color fg = theme.colorScheme.onSurface;
              IconData? trailIcon;
              Color? trailColor;

              if (rec != null) {
                if (isCorrectOption) {
                  bg = colors.successContainer.withValues(alpha: 0.8);
                  border = colors.success;
                  fg = colors.onSuccessContainer;
                  trailIcon = AppIcons.success;
                  trailColor = colors.success;
                } else if (isChosen && !rec.correct) {
                  bg = colors.dangerContainer.withValues(alpha: 0.8);
                  border = colors.danger;
                  fg = colors.onDangerContainer;
                  trailIcon = AppIcons.stepFailed;
                  trailColor = colors.danger;
                }
              }

              return InkWell(
                key: ValueKey('practice-mcq-opt-$i'),
                borderRadius: AppShape.radiusMd,
                onTap: (rec != null || _submitting)
                    ? null
                    : () => unawaited(_submitAnswer(q, answer: letter)),
                child: AnimatedContainer(
                  duration: AppMotion.of(context, AppMotion.quick),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
                  decoration: BoxDecoration(
                    color: bg,
                    borderRadius: AppShape.radiusMd,
                    border: Border.all(
                      color: border,
                      width: (rec != null && (isCorrectOption || isChosen)) ? 1.5 : 1,
                    ),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        width: 26,
                        height: 26,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: (rec != null && (isCorrectOption || isChosen))
                              ? border.withValues(alpha: 0.2)
                              : theme.colorScheme.surfaceContainerHighest,
                        ),
                        child: Text(
                          letter,
                          style: AppType.tabular(
                            theme.textTheme.labelLarge!.copyWith(
                              color: fg,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(
                            options[i],
                            style: theme.textTheme.bodyLarge?.copyWith(color: fg, height: 1.45),
                          ),
                        ),
                      ),
                      if (trailIcon != null) ...[
                        const SizedBox(width: 8),
                        Icon(trailIcon, size: 20, color: trailColor),
                      ],
                    ],
                  ),
                ),
              );
            },
          ),
        ],
      ],
    );
  }

  Widget _buildCardBody(ThemeData theme, DshColors colors, Map<String, dynamic> q, _PracticeAnswerRecord? rec) {
    final anchor = '${q['answer'] ?? ''}'.trim();
    final explain = '${q['explain'] ?? q['analysis'] ?? ''}'.trim();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (!_revealed && rec == null)
          OutlinedButton.icon(
            key: const ValueKey('practice-card-reveal'),
            onPressed: () => setState(() => _revealed = true),
            icon: const Icon(AppIcons.preview, size: 18),
            label: const Text('展开记忆锚点 / 解析'),
          )
        else
          GlassSurface(
            key: const ValueKey('practice-card-anchor-box'),
            tier: GlassTier.card,
            borderRadius: AppShape.radiusMd,
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (anchor.isNotEmpty) ...[
                    Text(
                      '记忆锚点',
                      style: theme.textTheme.labelMedium?.copyWith(
                        color: theme.colorScheme.primary,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(anchor, style: theme.textTheme.bodyLarge?.copyWith(fontWeight: FontWeight.w600)),
                  ],
                  if (explain.isNotEmpty) ...[
                    if (anchor.isNotEmpty) const SizedBox(height: 10),
                    Text(
                      '扩展解析',
                      style: theme.textTheme.labelMedium?.copyWith(
                        color: colors.textTertiary,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(explain, style: theme.textTheme.bodyMedium?.copyWith(height: 1.5)),
                  ],
                ],
              ),
            ),
          ),
        if (rec == null) ...[
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  key: const ValueKey('practice-card-unfamiliar'),
                  onPressed: _submitting
                      ? null
                      : () => unawaited(_submitAnswer(q, answer: 'UNFAMILIAR', rating: 'again')),
                  icon: Icon(AppIcons.warning, size: 18, color: colors.warning),
                  label: const Text('还不熟'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.icon(
                  key: const ValueKey('practice-card-known'),
                  onPressed: _submitting
                      ? null
                      : () => unawaited(_submitAnswer(q, answer: 'KNOWN', rating: 'good')),
                  icon: const Icon(AppIcons.success, size: 18),
                  label: const Text('我读懂了'),
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }

  Widget _buildShortQaBody(ThemeData theme, DshColors colors, Map<String, dynamic> q, _PracticeAnswerRecord? rec) {
    final answer = '${q['answer'] ?? ''}'.trim();
    final explain = '${q['explain'] ?? q['analysis'] ?? ''}'.trim();

    if (!_revealed && rec == null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          FilledButton.icon(
            key: const ValueKey('practice-qa-reveal'),
            onPressed: () => setState(() => _revealed = true),
            icon: const Icon(AppIcons.preview, size: 18),
            label: const Text('查看参考答案'),
          ),
        ],
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        GlassSurface(
          key: const ValueKey('practice-qa-answer-box'),
          tier: GlassTier.card,
          borderRadius: AppShape.radiusMd,
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '参考答案',
                  style: theme.textTheme.labelMedium?.copyWith(
                    color: theme.colorScheme.primary,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  answer.isNotEmpty ? answer : '（暂无标准文本答案）',
                  style: theme.textTheme.bodyLarge?.copyWith(height: 1.5),
                ),
                if (explain.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  Text(
                    '延伸解析',
                    style: theme.textTheme.labelMedium?.copyWith(
                      color: colors.textTertiary,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(explain, style: theme.textTheme.bodyMedium?.copyWith(height: 1.5)),
                ],
              ],
            ),
          ),
        ),
        if (rec == null) ...[
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  key: const ValueKey('practice-qa-missed'),
                  onPressed: _submitting
                      ? null
                      : () => unawaited(_submitAnswer(q, answer: 'FORGOT', rating: 'again')),
                  icon: Icon(AppIcons.stepFailed, size: 18, color: colors.danger),
                  label: const Text('没答上'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.icon(
                  key: const ValueKey('practice-qa-passed'),
                  onPressed: _submitting
                      ? null
                      : () => unawaited(_submitAnswer(q, answer: 'REMEMBER', rating: 'good')),
                  icon: const Icon(AppIcons.success, size: 18),
                  label: const Text('答上了'),
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }

  Widget _buildFeedbackBanner(ThemeData theme, DshColors colors, _PracticeAnswerRecord rec) {
    final bg = rec.correct
        ? colors.successContainer.withValues(alpha: 0.65)
        : colors.dangerContainer.withValues(alpha: 0.65);
    final border = rec.correct ? colors.success : colors.danger;
    final fg = rec.correct ? colors.onSuccessContainer : colors.onDangerContainer;

    final String headline;
    if (rec.selfJudged) {
      headline = rec.correct ? '已掌握 · 计入今日复习巩固' : '还不熟 · 已纳入错题与近期重刷';
    } else {
      headline = rec.correct ? '答对了！' : '答错了，正确答案是 ${rec.expectedAnswer}';
    }

    final String? wrongNote;
    if (rec.graduatedFromWrongBook) {
      wrongNote = '🎉 连续答对 2 次，已自动从错题本毕业！';
    } else if (rec.inWrongBook && rec.correct) {
      wrongNote = '错题巩固进度：连续答对 ${rec.streakCorrect} / 2 次（再对 1 次即可毕业）';
    } else if (rec.addedToWrongBook) {
      wrongNote = '已收入错题本（需连续答对 2 次方可毕业移出）';
    } else if (rec.streakCorrect >= 2) {
      wrongNote = '连续答对 ${rec.streakCorrect} 次';
    } else {
      wrongNote = null;
    }

    final fsrsHint = rec.scheduledDays <= 0
        ? 'FSRS 下次复习：10 分钟后重练'
        : 'FSRS 下次复习：${rec.scheduledDays} 天后';

    return Container(
      key: const ValueKey('practice-feedback-card'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: AppShape.radiusMd,
        border: Border.all(color: border.withValues(alpha: 0.65)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(rec.correct ? AppIcons.success : AppIcons.stepFailed, size: 18, color: border),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  headline,
                  style: theme.textTheme.titleSmall?.copyWith(color: fg, fontWeight: FontWeight.w700),
                ),
              ),
            ],
          ),
          if (wrongNote != null) ...[
            const SizedBox(height: 6),
            Text(wrongNote, style: theme.textTheme.bodySmall?.copyWith(color: fg, fontWeight: FontWeight.w600)),
          ],
          const SizedBox(height: 6),
          Text(
            '$fsrsHint · 本题用时 ${(rec.elapsedMs / 1000).toStringAsFixed(1)}s',
            style: theme.textTheme.labelSmall?.copyWith(color: fg.withValues(alpha: 0.85)),
          ),
          if (!rec.selfJudged && rec.explain.trim().isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(
              '解析：${rec.explain.trim()}',
              style: theme.textTheme.bodyMedium?.copyWith(color: fg, height: 1.5),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildBottomNav(ThemeData theme) {
    final hasPrev = _currentIndex > 0;
    final isLast = _currentIndex >= _questions.length - 1;
    final rec = _currentRecord;

    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: GlassSurface(
          tier: GlassTier.chrome,
          borderRadius: AppShape.radiusLg,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: [
              OutlinedButton.icon(
                key: const ValueKey('practice-prev-btn'),
                onPressed: hasPrev ? () => _jumpTo(_currentIndex - 1) : null,
                icon: const Icon(AppIcons.pagePrevious, size: 16),
                label: const Text('上一题'),
              ),
              const SizedBox(width: 8),
              TextButton.icon(
                onPressed: () => unawaited(_showAnswerSheet()),
                icon: const Icon(AppIcons.allFeatures, size: 16),
                label: Text('${_records.length}/${_questions.length}'),
              ),
              const Spacer(),
              FilledButton.icon(
                key: const ValueKey('practice-next-btn'),
                onPressed: _goNextOrFinish,
                icon: Icon(isLast ? AppIcons.done : AppIcons.pageNext, size: 16),
                label: Text(
                  isLast
                      ? (rec != null ? '查看战报' : '完成交卷')
                      : (rec != null ? '下一题' : '跳过'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSummaryView(ThemeData theme, DshColors colors) {
    final total = _questions.length;
    final answered = _records.length;
    final correct = _records.values.where((r) => r.correct).length;
    final wrongQs = [
      for (final q in _questions)
        if (_records['${q['id'] ?? ''}']?.correct == false) q,
    ];
    final accuracyPct = answered > 0 ? ((correct * 100) / answered).round() : 0;
    final totalMs = _records.values.fold<int>(0, (s, r) => s + r.elapsedMs);
    final avgSec = answered > 0 ? (totalMs / answered / 1000).toStringAsFixed(1) : '0.0';
    final addedWrong = _records.values.where((r) => r.addedToWrongBook).length;
    final graduatedWrong = _records.values.where((r) => r.graduatedFromWrongBook).length;

    final due10m = _records.values.where((r) => r.scheduledDays <= 0).length;
    final due1to3d = _records.values.where((r) => r.scheduledDays >= 1 && r.scheduledDays <= 3).length;
    final due4dPlus = _records.values.where((r) => r.scheduledDays >= 4).length;

    return ListView(
      key: const ValueKey('practice-summary-view'),
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
      children: [
        OverviewCard(
          margin: const EdgeInsets.only(bottom: 14),
          leading: OverviewGlyph(
            AppIcons.badgeEarned,
            tint: accuracyPct >= 80 ? 5 : (accuracyPct >= 60 ? 4 : 3),
          ),
          title: Text('本轮战报 · 正确率 $accuracyPct%'),
          subtitle: Text('已作答 $answered / $total 题（答对 $correct 题） · 平均耗时 ${avgSec}s/题'),
          trailing: StatusChip(
            tone: wrongQs.isEmpty ? StatusTone.ok : StatusTone.warn,
            label: wrongQs.isEmpty ? '全对通关' : '错 ${wrongQs.length} 题',
          ),
          footer: OverviewFooter(
            tone: StatusTone.idle,
            text: '累计总耗时 ${(totalMs / 1000).toStringAsFixed(0)} 秒',
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
                Text('错题本与 FSRS 排期', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: _MetricTile(
                        label: '新加入错题',
                        value: '$addedWrong 题',
                        color: addedWrong > 0 ? colors.danger : colors.textTertiary,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: _MetricTile(
                        label: '错题本毕业',
                        value: '$graduatedWrong 题',
                        color: graduatedWrong > 0 ? colors.success : colors.textTertiary,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  'FSRS 下次复习安排',
                  style: theme.textTheme.labelMedium?.copyWith(color: colors.textTertiary),
                ),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _PracticeTag(
                      label: '10 分钟后重练 $due10m 题',
                      bg: colors.dangerContainer,
                      fg: colors.onDangerContainer,
                    ),
                    _PracticeTag(
                      label: '1~3 天巩固 $due1to3d 题',
                      bg: colors.warningContainer,
                      fg: colors.onWarningContainer,
                    ),
                    _PracticeTag(
                      label: '4 天以上长记忆 $due4dPlus 题',
                      bg: colors.successContainer,
                      fg: colors.onSuccessContainer,
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 16),
        if (wrongQs.isNotEmpty) ...[
          FilledButton.icon(
            key: const ValueKey('practice-retry-wrong'),
            onPressed: _retryRoundWrongs,
            icon: const Icon(AppIcons.replay, size: 18),
            label: Text('重刷本轮错题（${wrongQs.length} 题）'),
          ),
          const SizedBox(height: 10),
        ],
        if (widget.initialQuestions == null) ...[
          OutlinedButton.icon(
            key: const ValueKey('practice-next-batch'),
            onPressed: () {
              final doneIds = [for (final q in _questions) '${q['id'] ?? ''}'];
              unawaited(_loadSession(excludeIds: doneIds));
            },
            icon: const Icon(AppIcons.refresh, size: 18),
            label: const Text('再来一组（不重复新题）'),
          ),
          const SizedBox(height: 10),
        ],
        TextButton.icon(
          key: const ValueKey('practice-summary-back'),
          onPressed: () => Navigator.of(context).pop(_hasChanges),
          icon: const Icon(AppIcons.back, size: 18),
          label: const Text('返回题库中心'),
        ),
      ],
    );
  }

  Widget _buildEmptyOrError(ThemeData theme, DshColors colors) {
    final emptyMsg = switch (widget.scope) {
      'wrong_book' => '错题本目前空空如也，继续保持！',
      'fsrs_due' => '今天暂无到期的 FSRS 复习题目。',
      'starred' => '还没有收藏任何题目，刷题时点右上角星标即可收藏。',
      'unseen' => '这套题的未刷题都已经刷过一轮啦。',
      _ => '当前筛选条件下暂无可刷题目，请先导入题库或启用套库。',
    };
    return Center(
      key: const ValueKey('practice-empty-state'),
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(_error != null ? AppIcons.warning : AppIcons.empty, size: 36, color: colors.textTertiary),
                const SizedBox(height: 12),
                Text(
                  _error ?? emptyMsg,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium?.copyWith(color: _error != null ? colors.danger : colors.textTertiary),
                ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(_hasChanges),
                  child: const Text('返回题库中心'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final q = _currentQuestion;
    final rec = _currentRecord;

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) Navigator.of(context).pop(_hasChanges);
      },
      child: AppBackdrop(
        child: Scaffold(
          backgroundColor: Colors.transparent,
          bottomNavigationBar: (!_loading && !_finished && q != null) ? _buildBottomNav(theme) : null,
          body: SafeArea(
            child: Align(
              alignment: Alignment.topCenter,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: AppPage.contentWidth),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _buildTopBar(theme, colors),
                    Expanded(
                      child: _loading
                          ? const Center(child: CircularProgressIndicator())
                          : _questions.isEmpty
                              ? _buildEmptyOrError(theme, colors)
                              : _finished
                                  ? _buildSummaryView(theme, colors)
                                  : SingleChildScrollView(
                                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                                      child: Column(
                                        crossAxisAlignment: CrossAxisAlignment.stretch,
                                        children: [
                                          _buildQuestionHeaderChips(theme, colors, q!),
                                          const SizedBox(height: 12),
                                          GlassSurface(
                                            tier: GlassTier.card,
                                            borderRadius: AppShape.radiusLg,
                                            child: Padding(
                                              padding: const EdgeInsets.all(16),
                                              child: Column(
                                                crossAxisAlignment: CrossAxisAlignment.stretch,
                                                children: [
                                                  Text(
                                                    '${q['stem'] ?? ''}',
                                                    key: const ValueKey('practice-stem-text'),
                                                    style: theme.textTheme.bodyLarge?.copyWith(
                                                      fontSize: 16.5,
                                                      height: 1.55,
                                                      fontWeight: FontWeight.w500,
                                                    ),
                                                  ),
                                                  if (q['images'] is List && (q['images'] as List).isNotEmpty)
                                                    _buildImagesSection(theme, colors, q['images'] as List),
                                                ],
                                              ),
                                            ),
                                          ),
                                          const SizedBox(height: 14),
                                          if ('${q['kind'] ?? 'mcq'}' == 'card')
                                            _buildCardBody(theme, colors, q, rec)
                                          else if ('${q['kind'] ?? 'mcq'}' == 'short_qa')
                                            _buildShortQaBody(theme, colors, q, rec)
                                          else
                                            _buildMcqBody(theme, colors, q, rec),
                                          if (rec != null) ...[
                                            const SizedBox(height: 14),
                                            _buildFeedbackBanner(theme, colors, rec),
                                          ],
                                        ],
                                      ),
                                    ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PracticeTag extends StatelessWidget {
  const _PracticeTag({required this.label, required this.bg, required this.fg});

  final String label;
  final Color bg;
  final Color fg;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: theme.textTheme.labelSmall?.copyWith(color: fg, fontWeight: FontWeight.w600),
      ),
    );
  }
}

class _LegendDot extends StatelessWidget {
  const _LegendDot({required this.color, required this.label});

  final Color color;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 10,
          height: 10,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 5),
        Text(label, style: theme.textTheme.labelSmall),
      ],
    );
  }
}

class _MetricTile extends StatelessWidget {
  const _MetricTile({required this.label, required this.value, required this.color});

  final String label;
  final String value;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: AppShape.radiusMd,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: theme.textTheme.labelSmall?.copyWith(color: DshColors.of(context).textTertiary)),
          const SizedBox(height: 4),
          Text(
            value,
            style: AppType.tabular(theme.textTheme.titleMedium!.copyWith(color: color, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    );
  }
}

class _PracticeImagePlaceholder extends StatelessWidget {
  const _PracticeImagePlaceholder({required this.path, required this.caption});

  final String path;
  final String caption;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: AppShape.radiusSm,
        border: Border.all(color: theme.colorScheme.outlineVariant.withValues(alpha: 0.5)),
      ),
      child: Row(
        children: [
          Icon(AppIcons.image, size: 16, color: colors.textTertiary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '附图占位：${caption.isNotEmpty ? '$caption · ' : ''}$path',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
            ),
          ),
        ],
      ),
    );
  }
}
