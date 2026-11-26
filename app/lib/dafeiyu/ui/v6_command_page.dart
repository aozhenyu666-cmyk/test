import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'wanshiming_page.dart';

enum V6CommandTab { poolAndBus, nineGridAndAppeal, framework }

/// V6 统一主动调度中枢：
/// 1) 知识海储量动效表 + Live 三合一监测灯 + 剪贴板双向总线
/// 2) 强关联九宫格 + 3步追问立约 + 严锁 4 类申诉减压阀
/// 3) 免发版动态时间与严格度框架编辑器
class V6CommandPage extends StatefulWidget {
  const V6CommandPage({
    super.key,
    this.channel,
    this.initialTab = V6CommandTab.poolAndBus,
  });

  final ProactiveChannel? channel;
  final V6CommandTab initialTab;

  static Future<void> open(
    BuildContext context, {
    ProactiveChannel? channel,
    V6CommandTab initialTab = V6CommandTab.poolAndBus,
  }) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => V6CommandPage(channel: channel, initialTab: initialTab),
        ),
      );

  @override
  State<V6CommandPage> createState() => _V6CommandPageState();
}

class _V6CommandPageState extends State<V6CommandPage> with SingleTickerProviderStateMixin {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final AnimationController _waveCtrl = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2800),
  )..repeat();

  late V6CommandTab _tab = widget.initialTab;
  bool _loading = true;
  bool _busy = false;
  String? _banner;

  Map<String, dynamic> _v6 = const {};
  Map<String, dynamic> _liveSignals = const {};

  // Framework editable fields
  bool _wakeQuizEnabled = true;
  int _wakeQuizCooldownMin = 10;
  bool _liveKeepAliveUnlock = true;
  int _liveDropGraceSec = 90;
  int _fiveMinBoutSec = 300;
  int _strictLockGraceSec = 45;
  int _dailyAppealLimit = 3;
  int _dailyPhysioRestLimit = 1;
  int _poolCooldownHours = 18;
  final _fragWindowsCtrl = TextEditingController(text: '11:30-13:30, 17:30-19:30');
  final _deskWindowsCtrl = TextEditingController(text: '09:00-11:30, 14:30-17:30, 20:00-22:00');

  // Grocery 9-grid quick checklist state
  final Set<String> _pickedGrocery = <String>{};

  static const _groceryCards = <(String, String)>[
    ('豆腐', '豆制品区'),
    ('青菜/生菜', '叶菜区'),
    ('西红柿', '茄果区'),
    ('鸡蛋', '蛋禽区'),
    ('瘦肉/排骨', '鲜肉区'),
    ('葱姜蒜', '调味干货'),
    ('土豆/胡萝卜', '根茎区'),
    ('鲜面/米', '主食区'),
    ('饮用水/酸奶', '饮品区'),
  ];

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _waveCtrl.dispose();
    _fragWindowsCtrl.dispose();
    _deskWindowsCtrl.dispose();
    super.dispose();
  }

  static Map<String, dynamic> _asMap(Object? raw) {
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return {
        for (final e in raw.entries)
          if (e.key is String) e.key as String: e.value,
      };
    }
    return const {};
  }

  static int _asInt(Object? raw, int fallback) => raw is num ? raw.round() : fallback;

  Future<void> _refresh() async {
    final results = await Future.wait<Object?>([
      _channel.v6Op().catchError((_) => null),
      _channel.v6LiveSignals().catchError((_) => null),
    ]);
    if (!mounted) return;
    final v6Map = _asMap(results[0]);
    final sigMap = _asMap(results[1]);
    final fw = _asMap(v6Map['framework']);
    setState(() {
      _loading = false;
      if (v6Map.isNotEmpty) _v6 = v6Map;
      if (sigMap.isNotEmpty) _liveSignals = sigMap;
      if (fw.isNotEmpty) {
        _wakeQuizEnabled = fw['wakeQuizEnabled'] != false;
        _wakeQuizCooldownMin = _asInt(fw['wakeQuizCooldownMin'], 10);
        _liveKeepAliveUnlock = fw['liveKeepAliveUnlock'] != false;
        _liveDropGraceSec = _asInt(fw['liveDropGraceSec'], 90);
        _fiveMinBoutSec = _asInt(fw['fiveMinBoutSec'], 300);
        _strictLockGraceSec = _asInt(fw['strictLockGraceSec'], 45);
        _dailyAppealLimit = _asInt(fw['dailyAppealLimit'], 3);
        _dailyPhysioRestLimit = _asInt(fw['dailyPhysioRestLimit'], 1);
        _poolCooldownHours = _asInt(fw['poolCooldownHours'], 18);
        if (fw['fragmentedWindows'] is List) {
          _fragWindowsCtrl.text = (fw['fragmentedWindows'] as List).join(', ');
        }
        if (fw['deskMoatWindows'] is List) {
          _deskWindowsCtrl.text = (fw['deskMoatWindows'] as List).join(', ');
        }
      }
    });
  }

  Future<void> _ingestClipboard() async {
    if (_busy) return;
    final data = await Clipboard.getData('text/plain');
    final clipText = data?.text?.trim() ?? '';
    if (clipText.isEmpty) {
      setState(() => _banner = '剪贴板为空：先在 Gemini 复制一段有用结论或【今日契约】');
      return;
    }
    setState(() => _busy = true);
    final res = await _channel.v6Op({'action': 'pool_ingest', 'text': clipText, 'source': 'gemini'});
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (res?['kind'] == 'contract') {
        final target = _asMap(res?['contract'])['target'] ?? '契约';
        _banner = '✓ 已从剪贴板吸纳【今日契约】并武装验收锁：$target';
      } else if (res?['ok'] == true) {
        _banner = '✓ 已将 Gemini 内容切片喂入知识海（新增 ${res?['addedCount'] ?? 1} 条）';
      } else {
        _banner = '未发现可入库新句子（${res?['error'] ?? '可能已存在'}）';
      }
    });
  }

  Future<void> _launchGeminiInterrogation() async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.v6Op({'action': 'interrogate_prompt'});
    final prompt = '${res?['prompt'] ?? ''}'.trim();
    if (prompt.isNotEmpty) {
      await Clipboard.setData(ClipboardData(text: prompt));
      await _channel.triggerGeminiAutoLive(
        topic: '3刀追问立约',
        brief: prompt,
        voiceLine: '来，3刀追问定契约，定完你再动。',
        autoClickLive: false,
      );
    }
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = prompt.isNotEmpty ? '✓ 已复制 3 刀追问剧本并拉起 Gemini，聊完复制【今日契约】回来即自动上锁' : '拉起追问剧本失败';
    });
  }

  Future<void> _testWakeQuizNow() async {
    if (_busy) return;
    setState(() => _busy = true);
    final ok = await _channel.triggerWakeQuizNow();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = ok ? '⚡ 已弹出 #1 亮屏 1 秒左右盲点微题，请看悬浮卡' : '未弹出：请先授予悬浮窗/无障碍权限或确认引擎已连';
    });
  }

  Future<void> _sweepPool() async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.v6Op({'action': 'pool_sweep'});
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = '✓ 遗忘清理完成：归档 ${res?['archivedCount'] ?? 0} 条沉底卡，降权 ${res?['decayedCount'] ?? 0} 条';
    });
  }

  Future<void> _triggerAppeal(String kind) async {
    if (_busy) return;
    setState(() => _busy = true);
    final res = await _channel.v6Op({'action': 'appeal', 'kind': kind});
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true ? '✓ ${res?['message']}' : '✕ ${res?['error'] ?? '申诉未批准'}';
    });
    if (res?['ok'] == true && kind == 'downgrade_micro_quiz') {
      unawaited(_testWakeQuizNow());
    }
  }

  Future<void> _openInAppContractDialog() async {
    final targetCtrl = TextEditingController();
    final verifyCtrl = TextEditingController(text: '交出 1 句核心错误归因或做对 5 题');
    int mins = 25;

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDlg) => AlertDialog(
          title: const Text('3 步快速立约（免坐桌前失神）'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: targetCtrl,
                decoration: const InputDecoration(labelText: '1. 这段只咬死哪 1 个具体动作？'),
              ),
              const SizedBox(height: 10),
              DropdownButtonFormField<int>(
                initialValue: mins,
                decoration: const InputDecoration(labelText: '2. 限时多久'),
                items: const [10, 20, 25, 45].map((m) => DropdownMenuItem(value: m, child: Text('$m 分钟'))).toList(),
                onChanged: (v) => setDlg(() => mins = v ?? 25),
              ),
              const SizedBox(height: 10),
              TextField(
                controller: verifyCtrl,
                decoration: const InputDecoration(labelText: '3. 到点拿什么交差才免锁？'),
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('签契约上锁')),
          ],
        ),
      ),
    );

    if (ok == true && targetCtrl.text.trim().isNotEmpty) {
      final res = await _channel.v6Op({
        'action': 'interrogate_commit',
        'target': targetCtrl.text.trim(),
        'durationMin': mins,
        'verifyRule': verifyCtrl.text.trim(),
      });
      await _refresh();
      if (mounted) {
        setState(() {
          _banner = res?['ok'] == true ? '✓ 今日契约已签约并武装验收锁' : '签约失败';
        });
      }
    }

    targetCtrl.dispose();
    verifyCtrl.dispose();
  }

  Future<void> _openGrocerySheet() async {
    await showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheet) => Padding(
          padding: const EdgeInsets.fromLTRB(16, 18, 16, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('买菜/出门 9 宫格看图快门（顺路合并防折返）', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
              const SizedBox(height: 12),
              GridView.count(
                shrinkWrap: true,
                crossAxisCount: 3,
                mainAxisSpacing: 8,
                crossAxisSpacing: 8,
                childAspectRatio: 1.55,
                children: _groceryCards.map((pair) {
                  final picked = _pickedGrocery.contains(pair.$1);
                  return InkWell(
                    borderRadius: BorderRadius.circular(10),
                    onTap: () {
                      setSheet(() {
                        if (picked) {
                          _pickedGrocery.remove(pair.$1);
                        } else {
                          _pickedGrocery.add(pair.$1);
                        }
                      });
                    },
                    child: Container(
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(10),
                        color: picked ? const Color(0x2BC5A059) : Colors.black12,
                        border: Border.all(color: picked ? const Color(0xFFC5A059) : Colors.white12),
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Text(pair.$1, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
                          Text(pair.$2, style: const TextStyle(fontSize: 10.5, color: Colors.grey)),
                        ],
                      ),
                    ),
                  );
                }).toList(),
              ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () {
                  final list = _pickedGrocery.isEmpty ? '（未点选）' : _pickedGrocery.join(' → ');
                  unawaited(Clipboard.setData(ClipboardData(text: '【顺路采购单】$list')));
                  Navigator.of(ctx).pop();
                  setState(() => _banner = '✓ 采购动线已复制：$list');
                },
                child: Text('导出顺路采购单（已选 ${_pickedGrocery.length} 项）'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _saveFramework() async {
    if (_busy) return;
    setState(() => _busy = true);
    List<String> splitWins(String raw) => raw
        .split(RegExp(r'[,，\n]+'))
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();

    final res = await _channel.v6Op({
      'action': 'framework_save',
      'framework': {
        'wakeQuizEnabled': _wakeQuizEnabled,
        'wakeQuizCooldownMin': _wakeQuizCooldownMin,
        'liveKeepAliveUnlock': _liveKeepAliveUnlock,
        'liveDropGraceSec': _liveDropGraceSec,
        'fiveMinBoutSec': _fiveMinBoutSec,
        'strictLockGraceSec': _strictLockGraceSec,
        'dailyAppealLimit': _dailyAppealLimit,
        'dailyPhysioRestLimit': _dailyPhysioRestLimit,
        'poolCooldownHours': _poolCooldownHours,
        'fragmentedWindows': splitWins(_fragWindowsCtrl.text),
        'deskMoatWindows': splitWins(_deskWindowsCtrl.text),
      },
    });
    await _refresh();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _banner = res?['ok'] == true ? '✓ 动态时间与严格度框架已实时生效（无需重新发包）' : '保存失败';
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final tel = _asMap(_v6['telemetry']);
    final win = _asMap(_v6['windowMode']);
    final appeals = _asMap(_v6['appeals']);
    final nineGrid = (_v6['nineGrid'] is List) ? (_v6['nineGrid'] as List) : const [];

    return AppPage.list(
      title: 'V6 双模态调度中枢',
      subtitle: Text('${win['label'] ?? '动态框架实时生效 · 知识海自转'}'),
      actions: [
        WanshimingCornerSealButton(channel: _channel),
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: '刷新 V6 状态',
          onPressed: () => unawaited(_refresh()),
        ),
      ],
      children: [
        if (_banner != null) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: GlassSurface(
              tier: GlassTier.card,
              borderRadius: AppShape.radiusLg,
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              child: Row(
                children: [
                  Expanded(child: Text(_banner!, style: TextStyle(color: colors.info, fontSize: 13))),
                  IconButton(
                    icon: Icon(AppIcons.close, size: 16, color: colors.textTertiary),
                    onPressed: () => setState(() => _banner = null),
                  ),
                ],
              ),
            ),
          ),
        ],
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: SegmentedPill<V6CommandTab>(
            selected: _tab,
            segments: const [
              PillSegment(V6CommandTab.poolAndBus, '知识海与Live灯'),
              PillSegment(V6CommandTab.nineGridAndAppeal, '九宫格与申诉'),
              PillSegment(V6CommandTab.framework, '免发版框架'),
            ],
            onChanged: (next) => setState(() => _tab = next),
          ),
        ),
        const SizedBox(height: 14),
        if (_loading)
          const Center(child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()))
        else if (_tab == V6CommandTab.poolAndBus)
          ..._buildPoolAndBusTab(colors, tel)
        else if (_tab == V6CommandTab.nineGridAndAppeal)
          ..._buildNineGridTab(colors, nineGrid, appeals)
        else
          ..._buildFrameworkTab(colors),
      ],
    );
  }

  Widget _v6Card({required Widget child}) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: GlassSurface(
          tier: GlassTier.card,
          borderRadius: AppShape.radiusLg,
          padding: const EdgeInsets.all(14),
          child: Material(type: MaterialType.transparency, child: child),
        ),
      );

  List<Widget> _buildPoolAndBusTab(DshColors colors, Map<String, dynamic> tel) {
    final activeCount = _asInt(tel['active'], 16);
    final totalCount = _asInt(tel['total'], 16);
    final dedupeRate = _asInt(tel['dedupeRate72h'], 98);
    final bySrc = _asMap(tel['bySource']);
    final gCount = _asInt(bySrc['gemini'], 0);
    final qCount = _asInt(bySrc['quiz'], 0);
    final oCount = _asInt(bySrc['obsidian'], 0);

    final liveOnline = _liveSignals['liveOnline'] == true || _v6['liveVoiceActive'] == true;
    final sigAudio = _liveSignals['audioInComm'] == true;
    final sigMic = _liveSignals['micRecording'] == true;
    final sigVDisplay = _liveSignals['virtualDisplayActive'] == true;

    return [
      _v6Card(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                StatusChip(
                  label: liveOnline ? 'Live 在线免锁常驻中' : 'Live 离线 · 大肥鱼主触发值守',
                  tone: liveOnline ? StatusTone.ok : StatusTone.busy,
                ),
                const Spacer(),
                Flexible(
                  child: Text(
                    '${_liveSignals['summary'] ?? '硬件监测就绪'}',
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: colors.textTertiary, fontSize: 11),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                _hwLamp('音频通话态', sigAudio),
                _hwLamp('麦克风采集', sigMic),
                _hwLamp('虚拟共享副屏', sigVDisplay),
              ],
            ),
          ],
        ),
      ),
      _v6Card(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                SizedBox(
                  width: 96,
                  height: 96,
                  child: AnimatedBuilder(
                    animation: _waveCtrl,
                    builder: (_, _) => CustomPaint(
                      painter: _KnowledgePoolRingPainter(
                        progress: _waveCtrl.value,
                        fillRatio: (activeCount / math.max(1, totalCount)).clamp(0.18, 0.95),
                      ),
                      child: Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              '$activeCount',
                              style: const TextStyle(color: Color(0xFFE8CF82), fontSize: 22, fontWeight: FontWeight.w700),
                            ),
                            Text('鲜活弹药', style: TextStyle(color: colors.textTertiary, fontSize: 10)),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('知识海动态储量 · 总库 $totalCount 条', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                      const SizedBox(height: 6),
                      Text('72小时零复读率：$dedupeRate%（拒绝念叨一两个词）', style: TextStyle(color: colors.info, fontSize: 12.5)),
                      const SizedBox(height: 8),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(4),
                        child: SizedBox(
                          height: 8,
                          child: Row(
                            children: [
                              if (gCount + qCount + oCount == 0)
                                Expanded(child: Container(color: const Color(0xFFC5A059))),
                              if (gCount > 0) Expanded(flex: gCount, child: Container(color: const Color(0xFF4CA87A))),
                              if (qCount > 0) Expanded(flex: qCount, child: Container(color: const Color(0xFFC5A059))),
                              if (oCount > 0) Expanded(flex: oCount, child: Container(color: const Color(0xFF5D76B5))),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text('绿·Gemini喂入 $gCount  |  金·真题库 $qCount  |  青·模型 $oCount', style: TextStyle(color: colors.textTertiary, fontSize: 11)),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton.icon(
                  onPressed: _busy ? null : () => unawaited(_ingestClipboard()),
                  icon: const Icon(Icons.content_paste_go, size: 16),
                  label: const Text('读剪贴板喂鱼 / 吸契约'),
                ),
                OutlinedButton.icon(
                  onPressed: _busy ? null : () => unawaited(_launchGeminiInterrogation()),
                  icon: const Icon(Icons.bolt, size: 16),
                  label: const Text('发3刀追问去Gemini'),
                ),
                OutlinedButton.icon(
                  onPressed: _busy ? null : () => unawaited(_testWakeQuizNow()),
                  icon: const Icon(Icons.touch_app, size: 16),
                  label: const Text('试测 #1 亮屏微题'),
                ),
                TextButton(
                  onPressed: _busy ? null : () => unawaited(_sweepPool()),
                  child: const Text('清理遗忘沉底'),
                ),
              ],
            ),
          ],
        ),
      ),
    ];
  }

  List<Widget> _buildNineGridTab(DshColors colors, List<dynamic> nineGrid, Map<String, dynamic> appeals) {
    final usedTotal = _asInt(appeals['total'], 0);
    final usedPhysio = _asInt(appeals['physio'], 0);

    return [
      _v6Card(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('强关联 3×3 九宫格（替代旧 6 骰子）', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
            const SizedBox(height: 4),
            Text('上行：知识海高权切口 | 中行：真题速杀 | 下行：生活防耗 & 万世铭', style: TextStyle(color: colors.textTertiary, fontSize: 12)),
            const SizedBox(height: 12),
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              itemCount: 9,
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
                mainAxisSpacing: 8,
                crossAxisSpacing: 8,
                childAspectRatio: 1.05,
              ),
              itemBuilder: (ctx, idx) {
                final cell = idx < nineGrid.length ? _asMap(nineGrid[idx]) : <String, dynamic>{'title': '待填充', 'kind': 'knowledge'};
                final kind = '${cell['kind'] ?? 'knowledge'}';
                return InkWell(
                  borderRadius: BorderRadius.circular(10),
                  onTap: () {
                    if (kind == 'grocery') {
                      unawaited(_openGrocerySheet());
                    } else if (kind == 'wanshiming') {
                      unawaited(WanshimingPage.open(context, channel: _channel));
                    } else if (kind == 'interrogate') {
                      unawaited(_openInAppContractDialog());
                    } else {
                      unawaited(_testWakeQuizNow());
                    }
                  },
                  child: Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(10),
                      color: const Color(0x16C5A059),
                      border: Border.all(color: const Color(0x40C5A059)),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${cell['badge'] ?? ''}',
                          style: const TextStyle(color: Color(0xFFE8CF82), fontSize: 10, fontWeight: FontWeight.w600),
                        ),
                        const Spacer(),
                        Text(
                          '${cell['title'] ?? ''}',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, height: 1.25),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          '${cell['subtitle'] ?? ''}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(color: colors.textTertiary, fontSize: 10),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ],
        ),
      ),
      _v6Card(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text('严锁理性申诉减压阀', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                const Spacer(),
                Text('今日已用 $usedTotal/$_dailyAppealLimit · 停战 $usedPhysio/$_dailyPhysioRestLimit', style: TextStyle(color: colors.textTertiary, fontSize: 12)),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton(
                  onPressed: _busy ? null : () => unawaited(_triggerAppeal('downgrade_micro_quiz')),
                  child: const Text('① 降档连答5道亮屏微题'),
                ),
                OutlinedButton(
                  onPressed: _busy ? null : () => unawaited(_triggerAppeal('swap_item')),
                  child: const Text('② 同级换 1 条知识海素材'),
                ),
                OutlinedButton(
                  onPressed: _busy ? null : () => unawaited(_triggerAppeal('handoff_gemini_live')),
                  child: const Text('③ 携料转交 Gemini 深潜'),
                ),
                OutlinedButton(
                  onPressed: _busy ? null : () => unawaited(_triggerAppeal('physio_rest')),
                  child: const Text('④ 彻底呆滞·生理停战15m'),
                ),
              ],
            ),
          ],
        ),
      ),
    ];
  }

  List<Widget> _buildFrameworkTab(DshColors colors) {
    return [
      _v6Card(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('免发版 · 长久可调参动态规则框架', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
            const SizedBox(height: 4),
            Text('修改即时落盘，不需改代码重编 APK。', style: TextStyle(color: colors.textTertiary, fontSize: 12)),
            const SizedBox(height: 10),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('#1 亮屏 1 秒左右盲点微题'),
              subtitle: Text('每隔 $_wakeQuizCooldownMin 分钟自然解屏后自动触发'),
              value: _wakeQuizEnabled,
              onChanged: (v) => setState(() => _wakeQuizEnabled = v),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Gemini Live 在线免锁常驻（Road B）'),
              subtitle: Text('硬件麦/虚拟屏感知在线即放行免锁，断线 $_liveDropGraceSec 秒再接管'),
              value: _liveKeepAliveUnlock,
              onChanged: (v) => setState(() => _liveKeepAliveUnlock = v),
            ),
            const Divider(),
            _numSlider('亮屏微题冷却（分钟）', _wakeQuizCooldownMin, 1, 60, (v) => setState(() => _wakeQuizCooldownMin = v)),
            _numSlider('5分钟点火单局秒数', _fiveMinBoutSec, 120, 900, (v) => setState(() => _fiveMinBoutSec = v)),
            _numSlider('严锁宽限缓冲（秒）', _strictLockGraceSec, 15, 180, (v) => setState(() => _strictLockGraceSec = v)),
            _numSlider('每日申诉总配额（次）', _dailyAppealLimit, 0, 8, (v) => setState(() => _dailyAppealLimit = v)),
            _numSlider('同条知识防重复冷却（小时）', _poolCooldownHours, 2, 72, (v) => setState(() => _poolCooldownHours = v)),
            const SizedBox(height: 10),
            TextField(
              controller: _fragWindowsCtrl,
              decoration: const InputDecoration(
                labelText: '零碎清口窗（做饭/通勤 · 自动消化想聊杂念）',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _deskWindowsCtrl,
              decoration: const InputDecoration(
                labelText: '桌前护城河窗（硬禁谈空泛体系）',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 14),
            Row(
              children: [
                Expanded(
                  child: FilledButton(
                    onPressed: _busy ? null : () => unawaited(_saveFramework()),
                    child: const Text('热保存动态框架'),
                  ),
                ),
                const SizedBox(width: 10),
                OutlinedButton(
                  onPressed: () {
                    final jsonStr = const JsonEncoder.withIndent('  ').convert(_v6['framework'] ?? {});
                    unawaited(Clipboard.setData(ClipboardData(text: jsonStr)));
                    setState(() => _banner = '✓ 当前时间框架 JSON 已复制到剪贴板');
                  },
                  child: const Text('导出配置'),
                ),
              ],
            ),
          ],
        ),
      ),
    ];
  }

  Widget _hwLamp(String label, bool on) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          color: on ? const Color(0x244CA87A) : Colors.black12,
          border: Border.all(color: on ? const Color(0xFF4CA87A) : Colors.white12),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 7,
              height: 7,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: on ? const Color(0xFF56D396) : Colors.grey,
              ),
            ),
            const SizedBox(width: 6),
            Text(label, style: const TextStyle(fontSize: 11.5)),
          ],
        ),
      );

  Widget _numSlider(String label, int value, int min, int max, ValueChanged<int> onChanged) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(label, style: const TextStyle(fontSize: 13)),
              const Spacer(),
              Text('$value', style: const TextStyle(color: Color(0xFFE8CF82), fontWeight: FontWeight.w600)),
            ],
          ),
          Slider(
            value: value.clamp(min, max).toDouble(),
            min: min.toDouble(),
            max: max.toDouble(),
            divisions: (max - min) > 40 ? 20 : (max - min),
            onChanged: (d) => onChanged(d.round()),
          ),
        ],
      );
}

/// 金液水波知识海储量动效绘制器。
class _KnowledgePoolRingPainter extends CustomPainter {
  const _KnowledgePoolRingPainter({required this.progress, required this.fillRatio});

  final double progress;
  final double fillRatio;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = math.min(size.width, size.height) / 2 - 4;

    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..color = const Color(0x30C5A059)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.2,
    );

    canvas.save();
    canvas.clipPath(Path()..addOval(Rect.fromCircle(center: center, radius: radius - 2)));

    final waterTop = size.height * (1.0 - fillRatio);
    final wavePath = Path()..moveTo(0, size.height);
    for (double x = 0; x <= size.width; x += 4) {
      final y = waterTop + math.sin((x / size.width) * math.pi * 2 + progress * math.pi * 2) * 3.5;
      wavePath.lineTo(x, y);
    }
    wavePath
      ..lineTo(size.width, size.height)
      ..close();

    canvas.drawPath(
      wavePath,
      Paint()
        ..color = const Color(0x42C5A059)
        ..style = PaintingStyle.fill,
    );
    canvas.restore();

    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius),
      -math.pi / 2,
      math.pi * 2 * fillRatio,
      false,
      Paint()
        ..color = const Color(0xFFE8CF82)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 3
        ..strokeCap = StrokeCap.round,
    );
  }

  @override
  bool shouldRepaint(covariant _KnowledgePoolRingPainter oldDelegate) =>
      oldDelegate.progress != progress || oldDelegate.fillRatio != fillRatio;
}
