import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../channels/proactive_state.dart';

/// 低调内嵌于大肥鱼角落的《万世铭》博物馆级玄墨金箔长卷（GPU GLSL 着色 + 主线战役双向嵌套）。
class WanshimingPage extends StatefulWidget {
  const WanshimingPage({super.key, this.channel});

  final ProactiveChannel? channel;

  static Future<void> open(BuildContext context, {ProactiveChannel? channel}) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => WanshimingPage(channel: channel)),
      );

  @override
  State<WanshimingPage> createState() => _WanshimingPageState();
}

class _WanshimingPageState extends State<WanshimingPage> with SingleTickerProviderStateMixin {
  static const _bg = Color(0xFF070709);
  static const _surface = Color(0xCC111116);
  static const _ink = Color(0xFFECE7DC);
  static const _inkMuted = Color(0xFF969082);
  static const _gold = Color(0xFFC5A059);
  static const _goldBright = Color(0xFFE8CF82);
  static const _sealRed = Color(0xFFA63429);

  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final AnimationController _ambientCtrl = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 24),
  )..repeat();

  ui.FragmentProgram? _goldProgram;
  bool _loading = true;
  bool _busy = false;
  String _filter = '全部';
  List<Map<String, dynamic>> _steles = const [];
  List<Map<String, dynamic>> _longGoals = const [];
  List<Map<String, dynamic>> _mediumGoals = const [];
  int _cadenceStreak = 0;
  int _verifiedToday = 0;
  Offset _lightPos = const Offset(0.5, 0.35);
  String? _statusMsg;

  static const _fallbackSteles = <Map<String, dynamic>>[
    {
      'id': 'stele-seed-v6',
      'title': '立《万世铭》与 V6 双模态主动调度总框架',
      'date': '2026-11-26',
      'epoch': '丙午 · 破壁',
      'category': '纪元',
      'body': '确立大肥鱼主动触发与 Gemini Live 硬件监测并行架构，告别改时间重编译，告别知识贫血重复念叨。',
      'echo': '把高耗启动交给机器铺路，把清脆能量留给正事本身。',
      'starred': true,
    },
  ];

  @override
  void initState() {
    super.initState();
    unawaited(_loadShader());
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _ambientCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadShader() async {
    try {
      final prog = await ui.FragmentProgram.fromAsset('shaders/fx/wanshiming_gold.frag');
      if (mounted) setState(() => _goldProgram = prog);
    } catch (_) {
      // 无头测试或不支持运行时 Shader 的平台自动退回纯 Flutter 金箔粒子绘板
    }
  }

  Future<void> _refresh() async {
    final res = await _channel.v6Op({'action': 'wanshiming_list'}).catchError((_) => null);
    if (!mounted) return;
    final rawList = res?['steles'];
    final parsed = rawList is List
        ? [for (final x in rawList) if (x is Map) _asMap(x)]
        : _fallbackSteles;
    final horizons = res?['horizons'] is Map ? _asMap(res!['horizons'] as Map) : const <String, dynamic>{};
    final longRaw = horizons['long'];
    final medRaw = horizons['medium'];
    final cad = res?['cadence'] is Map ? _asMap(res!['cadence'] as Map) : const <String, dynamic>{};
    setState(() {
      _loading = false;
      _steles = parsed.isEmpty ? _fallbackSteles : parsed;
      _longGoals = longRaw is List ? [for (final g in longRaw) if (g is Map) _asMap(g)] : const [];
      _mediumGoals = medRaw is List ? [for (final g in medRaw) if (g is Map) _asMap(g)] : const [];
      _cadenceStreak = (res?['cadenceStreak'] as num?)?.round() ?? (cad['streak'] as num?)?.round() ?? 0;
      _verifiedToday = (res?['verifiedToday'] as num?)?.round() ?? (cad['verifiedToday'] as num?)?.round() ?? 0;
    });
  }

  static Map<String, dynamic> _asMap(Map raw) => {
        for (final e in raw.entries)
          if (e.key is String) e.key as String: e.value,
      };

  List<Map<String, dynamic>> get _allGoals => [
        for (final g in _longGoals) {...g, '_scope': 'long', '_scopeLabel': '远期大势'},
        for (final g in _mediumGoals) {...g, '_scope': 'medium', '_scopeLabel': '中期战役'},
      ];

  List<Map<String, dynamic>> get _filtered {
    return _steles.where((s) {
      if (_filter == '典藏') return s['starred'] == true;
      if (_filter == '主线战役') return '${s['category'] ?? ''}' == '战役';
      if (_filter != '全部') return '${s['category'] ?? ''}' == _filter;
      return true;
    }).toList();
  }

  Future<void> _launchCadenceFromGoal(Map<String, dynamic> goal) async {
    final title = '${goal['title'] ?? goal['name'] ?? '主线战役切片'}'.trim();
    if (title.isEmpty) return;
    setState(() => _busy = true);
    try {
      final res = await _channel.v6Op({
        'action': 'cadence_start',
        'target': title,
        'goalId': '${goal['id'] ?? ''}',
        'goalScope': '${goal['_scope'] ?? 'long'}',
        'intervalSec': 300,
      });
      if (!mounted) return;
      setState(() {
        _statusMsg = res?['ok'] == true
            ? '已用主线目标「$title」开启 5 分钟严验收局（到点锁+响铃）'
            : '${res?['error'] ?? '启动 5 分钟严验收失败'}';
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openEditor([Map<String, dynamic>? existing]) async {
    final titleCtrl = TextEditingController(text: '${existing?['title'] ?? ''}');
    final epochCtrl = TextEditingController(text: '${existing?['epoch'] ?? ''}');
    final bodyCtrl = TextEditingController(text: '${existing?['body'] ?? ''}');
    final echoCtrl = TextEditingController(text: '${existing?['echo'] ?? ''}');
    String cat = '${existing?['category'] ?? '抉择'}';
    bool starred = existing?['starred'] == true;

    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF101015),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(18)),
        side: BorderSide(color: Color(0x55C5A059)),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(20, 18, 20, MediaQuery.viewInsetsOf(ctx).bottom + 22),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  existing == null ? '刻下一铭' : '修订碑铭',
                  style: const TextStyle(color: _goldBright, fontSize: 18, fontWeight: FontWeight.w600, letterSpacing: 3),
                ),
                const SizedBox(height: 14),
                TextField(
                  controller: titleCtrl,
                  style: const TextStyle(color: _ink),
                  decoration: _inputDeco('铭题 *'),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: const ['抉择', '战役', '悟道', '纪元'].contains(cat) ? cat : '抉择',
                        dropdownColor: const Color(0xFF14141A),
                        style: const TextStyle(color: _ink),
                        decoration: _inputDeco('类别'),
                        items: const ['抉择', '战役', '悟道', '纪元']
                            .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                            .toList(),
                        onChanged: (v) => setSheetState(() => cat = v ?? '抉择'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: TextField(
                        controller: epochCtrl,
                        style: const TextStyle(color: _ink),
                        decoration: _inputDeco('纪元署号（如 丙午·破局）'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: bodyCtrl,
                  maxLines: 4,
                  style: const TextStyle(color: _ink),
                  decoration: _inputDeco('举措与铭文 *'),
                ),
                const SizedBox(height: 10),
                TextField(
                  controller: echoCtrl,
                  maxLines: 2,
                  style: const TextStyle(color: _ink),
                  decoration: _inputDeco('代价与回响（选填）'),
                ),
                const SizedBox(height: 10),
                CheckboxListTile(
                  value: starred,
                  activeColor: _sealRed,
                  contentPadding: EdgeInsets.zero,
                  title: const Text('加盖朱砂印 · 列为典藏', style: TextStyle(color: _inkMuted, fontSize: 13)),
                  onChanged: (v) => setSheetState(() => starred = v == true),
                ),
                const SizedBox(height: 8),
                ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0x33C5A059),
                    foregroundColor: _goldBright,
                    side: const BorderSide(color: _gold),
                    padding: const EdgeInsets.symmetric(vertical: 13),
                  ),
                  onPressed: () => Navigator.of(ctx).pop(true),
                  child: const Text('落铭刻石', style: TextStyle(letterSpacing: 3, fontWeight: FontWeight.w600)),
                ),
              ],
            ),
          ),
        ),
      ),
    );

    if (saved == true && titleCtrl.text.trim().isNotEmpty && bodyCtrl.text.trim().isNotEmpty) {
      setState(() => _busy = true);
      final res = await _channel.v6Op({
        'action': 'wanshiming_save',
        if (existing?['id'] != null) 'id': existing!['id'],
        'title': titleCtrl.text.trim(),
        'category': cat,
        'epoch': epochCtrl.text.trim(),
        'body': bodyCtrl.text.trim(),
        'echo': echoCtrl.text.trim(),
        'starred': starred,
      });
      await _refresh();
      if (mounted) {
        setState(() {
          _busy = false;
          _statusMsg = res?['ok'] == true ? '已落铭刻石' : '已仅暂存视图（引擎未连）';
        });
      }
    }
    titleCtrl.dispose();
    epochCtrl.dispose();
    bodyCtrl.dispose();
    echoCtrl.dispose();
  }

  Future<void> _openRubbing(Map<String, dynamic> stele) async {
    bool confirmDelete = false;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDlg) => Dialog(
          backgroundColor: const Color(0xFF0E0E13),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
            side: const BorderSide(color: Color(0x66C5A059)),
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(22, 22, 22, 16),
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      Text(
                        '${stele['date'] ?? ''} · ${stele['category'] ?? '抉择'}',
                        style: const TextStyle(color: _gold, fontSize: 12, fontFamily: 'monospace', letterSpacing: 1.1),
                      ),
                      const Spacer(),
                      if (stele['starred'] == true) _SealChop(size: 24),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(
                    '${stele['title'] ?? ''}',
                    style: const TextStyle(color: _ink, fontSize: 19, fontWeight: FontWeight.w600, height: 1.4),
                  ),
                  if ('${stele['epoch'] ?? ''}'.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text('署号：${stele['epoch']}', style: const TextStyle(color: _goldBright, fontSize: 12)),
                  ],
                  const SizedBox(height: 14),
                  Text(
                    '${stele['body'] ?? ''}',
                    style: const TextStyle(color: Color(0xFFD6CFC2), fontSize: 14.5, height: 1.85),
                  ),
                  if ('${stele['echo'] ?? ''}'.isNotEmpty) ...[
                    const SizedBox(height: 14),
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: const BoxDecoration(
                        color: Color(0x14C5A059),
                        border: Border(left: BorderSide(color: _gold, width: 2)),
                      ),
                      child: Text(
                        '代价与回响：${stele['echo']}',
                        style: const TextStyle(color: _inkMuted, fontSize: 13, height: 1.65),
                      ),
                    ),
                  ],
                  const SizedBox(height: 18),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      OutlinedButton(
                        style: OutlinedButton.styleFrom(foregroundColor: _goldBright, side: const BorderSide(color: Color(0x55C5A059))),
                        onPressed: () {
                          Navigator.of(ctx).pop();
                          unawaited(_openEditor(stele));
                        },
                        child: const Text('修订'),
                      ),
                      OutlinedButton(
                        style: OutlinedButton.styleFrom(foregroundColor: _gold, side: const BorderSide(color: Color(0x44C5A059))),
                        onPressed: () {
                          final md = '## ${stele['title']}\n\n- 日期：`${stele['date']}`\n\n${stele['body']}\n';
                          unawaited(Clipboard.setData(ClipboardData(text: md)));
                          setState(() => _statusMsg = '已复制 Markdown 拓文');
                          Navigator.of(ctx).pop();
                        },
                        child: const Text('复制拓文'),
                      ),
                      TextButton(
                        style: TextButton.styleFrom(
                          foregroundColor: confirmDelete ? const Color(0xFFFFB4AB) : const Color(0xFFD06B5E),
                          backgroundColor: confirmDelete ? _sealRed : Colors.transparent,
                        ),
                        onPressed: () async {
                          if (!confirmDelete) {
                            setDlg(() => confirmDelete = true);
                            return;
                          }
                          await _channel.v6Op({'action': 'wanshiming_delete', 'id': stele['id']});
                          if (ctx.mounted) Navigator.of(ctx).pop();
                          await _refresh();
                        },
                        child: Text(confirmDelete ? '再点确认凿毁' : '凿毁'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  static InputDecoration _inputDeco(String label) => InputDecoration(
        labelText: label,
        labelStyle: const TextStyle(color: _inkMuted, fontSize: 13),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: Color(0x38C5A059)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: const BorderSide(color: _goldBright),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final items = _filtered;
    final goals = _allGoals;
    final starredCount = _steles.where((s) => s['starred'] == true).length;

    return Theme(
      data: ThemeData.dark().copyWith(scaffoldBackgroundColor: _bg),
      child: Scaffold(
        backgroundColor: _bg,
        appBar: AppBar(
          backgroundColor: const Color(0xCC070709),
          foregroundColor: _goldBright,
          elevation: 0,
          title: const Row(
            children: [
              _SealChop(size: 24),
              SizedBox(width: 10),
              Text('万世铭', style: TextStyle(color: _ink, letterSpacing: 4, fontSize: 18, fontWeight: FontWeight.w600)),
            ],
          ),
          actions: [
            TextButton.icon(
              onPressed: _busy ? null : () => unawaited(_openEditor()),
              icon: const Icon(Icons.edit_note, color: _goldBright, size: 18),
              label: const Text('刻下一铭', style: TextStyle(color: _goldBright, letterSpacing: 1.5)),
            ),
            const SizedBox(width: 6),
          ],
        ),
        body: Listener(
          behavior: HitTestBehavior.translucent,
          onPointerHover: (e) => _updateLight(context, e.position),
          onPointerMove: (e) => _updateLight(context, e.position),
          child: Stack(
            children: [
              Positioned.fill(
                child: IgnorePointer(
                  child: AnimatedBuilder(
                    animation: _ambientCtrl,
                    builder: (_, _) => CustomPaint(
                      painter: _ObsidianGoldCanvasPainter(
                        program: _goldProgram,
                        phase: _ambientCtrl.value * 24.0,
                        pulse: _cadenceStreak > 0 ? 1.0 : 0.35,
                        light: _lightPos,
                      ),
                    ),
                  ),
                ),
              ),
              ListView(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 56),
                children: [
                  _TactileGoldCard(
                    accentGlow: true,
                    child: _buildHeroPlaque(starredCount, goals.length),
                  ),
                if (_statusMsg != null) ...[
                  const SizedBox(height: 10),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    decoration: BoxDecoration(
                      color: const Color(0x22C5A059),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: const Color(0x55C5A059)),
                    ),
                    child: Text(_statusMsg!, style: const TextStyle(color: _goldBright, fontSize: 12)),
                  ),
                ],
                const SizedBox(height: 16),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: ['全部', '主线战役', '抉择', '战役', '悟道', '纪元', '典藏'].map((cat) {
                    final active = _filter == cat;
                    return ChoiceChip(
                      key: ValueKey('wsm-filter-$cat'),
                      label: Text(cat),
                      selected: active,
                      selectedColor: const Color(0x38C5A059),
                      backgroundColor: _surface,
                      side: BorderSide(color: active ? _goldBright : const Color(0x2EC5A059)),
                      labelStyle: TextStyle(color: active ? _goldBright : _inkMuted, fontSize: 12),
                      onSelected: (_) => setState(() => _filter = cat),
                    );
                  }).toList(),
                ),
                if ((_filter == '全部' || _filter == '主线战役') && goals.isNotEmpty) ...[
                  const SizedBox(height: 18),
                  _buildHorizonsNestingDeck(goals),
                ],
                const SizedBox(height: 20),
                if (_loading)
                  const Center(child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator(color: _gold)))
                else if (items.isEmpty)
                  Container(
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: _surface,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: const Color(0x2CC5A059)),
                    ),
                    child: const Text('此分类暂无碑刻，可点右上角「刻下一铭」，或连续通过 3 轮 5 分钟严验收自动铭刻。',
                        style: TextStyle(color: _inkMuted, fontSize: 13, height: 1.6)),
                  )
                else
                  for (var i = 0; i < items.length; i++)
                    _buildTimelineStele(items[i], isLast: i == items.length - 1),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _updateLight(BuildContext ctx, Offset globalPos) {
    final sz = MediaQuery.sizeOf(ctx);
    if (sz.isEmpty) return;
    final next = Offset(
      (globalPos.dx / sz.width).clamp(0.05, 0.95),
      (globalPos.dy / sz.height).clamp(0.05, 0.95),
    );
    if ((next - _lightPos).distanceSquared > 0.0004 && mounted) {
      setState(() => _lightPos = next);
    }
  }

  Widget _buildHeroPlaque(int starredCount, int goalsCount) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [Color(0xD9151319), Color(0xE609090C)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0x55C5A059)),
        boxShadow: const [BoxShadow(color: Color(0x44000000), blurRadius: 18, offset: Offset(0, 8))],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Text(
                'CHRONICLE OF TEN THOUSAND EPOCHS',
                style: TextStyle(color: _gold, fontSize: 10, letterSpacing: 2.6, fontWeight: FontWeight.w600),
              ),
              Spacer(),
              _SealChop(size: 20),
            ],
          ),
          const SizedBox(height: 10),
          const Text(
            '记长路所行之真举，每逢 3 轮 5 分钟严验收自动刻石，直挂远期主线。',
            style: TextStyle(color: _ink, fontSize: 13.5, letterSpacing: 0.6, height: 1.55),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              _statBox('已铭石碑', '${_steles.length}'),
              _statBox('5m连胜', '$_cadenceStreak 轮', note: '今日 $_verifiedToday 轮'),
              _statBox('主线目标', '$goalsCount'),
              _statBox('朱印典藏', '$starredCount'),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildHorizonsNestingDeck(List<Map<String, dynamic>> goals) {
    return Container(
      key: const ValueKey('wsm-horizons-deck'),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0x44C5A059)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.flag_outlined, color: _goldBright, size: 16),
              SizedBox(width: 8),
              Text(
                '主线战役双向嵌套 · 一键压入 5 分钟严验收',
                style: TextStyle(color: _goldBright, fontSize: 12.5, fontWeight: FontWeight.w600, letterSpacing: 0.8),
              ),
            ],
          ),
          const SizedBox(height: 12),
          for (final g in goals.take(4)) ...[
            Container(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
              margin: const EdgeInsets.only(bottom: 10),
              decoration: BoxDecoration(
                color: const Color(0xFF0A0A0E),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: const Color(0x33C5A059)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: const Color(0x22C5A059),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          '${g['_scopeLabel'] ?? '战役'}',
                          style: const TextStyle(color: _goldBright, fontSize: 10),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '${g['title'] ?? g['name'] ?? '未命名目标'}',
                          style: const TextStyle(color: _ink, fontSize: 14, fontWeight: FontWeight.w600),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: ((g['progress'] as num?)?.toDouble() ?? 0.25).clamp(0.05, 1.0),
                      minHeight: 4,
                      backgroundColor: const Color(0xFF1D1B18),
                      valueColor: const AlwaysStoppedAnimation<Color>(_goldBright),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerRight,
                    child: OutlinedButton.icon(
                      key: ValueKey('wsm-launch-goal-${g['id'] ?? g['title'] ?? ''}'),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: _goldBright,
                        side: const BorderSide(color: Color(0x66C5A059)),
                        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                        minimumSize: const Size(0, 32),
                      ),
                      onPressed: _busy ? null : () => unawaited(_launchCadenceFromGoal(g)),
                      icon: const Icon(Icons.bolt, size: 15),
                      label: const Text('以此开启 5m 严验收', style: TextStyle(fontSize: 11.5)),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildTimelineStele(Map<String, dynamic> s, {required bool isLast}) {
    final starred = s['starred'] == true;
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: 24,
            child: Column(
              children: [
                const SizedBox(height: 18),
                Transform.rotate(
                  angle: math.pi / 4,
                  child: Container(
                    width: starred ? 11 : 8,
                    height: starred ? 11 : 8,
                    decoration: BoxDecoration(
                      color: starred ? _sealRed : _gold,
                      border: Border.all(color: _goldBright, width: 1.2),
                    ),
                  ),
                ),
                if (!isLast)
                  Expanded(
                    child: Container(
                      width: 1.2,
                      margin: const EdgeInsets.only(top: 6),
                      color: const Color(0x38C5A059),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: _TactileGoldCard(
                accentGlow: starred,
                child: InkWell(
                  borderRadius: BorderRadius.circular(12),
                  onTap: () => unawaited(_openRubbing(s)),
                  child: Container(
                  padding: const EdgeInsets.all(18),
                  decoration: BoxDecoration(
                    color: _surface,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: starred ? const Color(0x77C5A059) : const Color(0x2CC5A059),
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Text(
                            '${s['date'] ?? ''}',
                            style: const TextStyle(color: _gold, fontSize: 11.5, fontFamily: 'monospace'),
                          ),
                          if ('${s['epoch'] ?? ''}'.isNotEmpty) ...[
                            const SizedBox(width: 8),
                            Flexible(
                              child: Text(
                                '· ${s['epoch']}',
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(color: _inkMuted, fontSize: 11.5),
                              ),
                            ),
                          ],
                          const Spacer(),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                            decoration: BoxDecoration(
                              color: const Color(0x1AC5A059),
                              borderRadius: BorderRadius.circular(3),
                              border: Border.all(color: const Color(0x33C5A059)),
                            ),
                            child: Text('${s['category'] ?? '抉择'}', style: const TextStyle(color: _gold, fontSize: 10.5)),
                          ),
                          if (starred) ...[
                            const SizedBox(width: 6),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                              decoration: BoxDecoration(color: _sealRed, borderRadius: BorderRadius.circular(2)),
                              child: const Text('典藏', style: TextStyle(color: _ink, fontSize: 10)),
                            ),
                          ],
                        ],
                      ),
                      const SizedBox(height: 10),
                      Text(
                        '${s['title'] ?? ''}',
                        style: const TextStyle(color: _ink, fontSize: 16.5, fontWeight: FontWeight.w600, height: 1.4),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '${s['body'] ?? ''}',
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(color: _inkMuted, fontSize: 13.5, height: 1.7),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _statBox(String label, String val, {String? note}) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: const Color(0xAA0C0C10),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0x38C5A059)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(val, style: const TextStyle(color: _goldBright, fontSize: 16, fontWeight: FontWeight.w700)),
                const SizedBox(width: 6),
                Text(label, style: const TextStyle(color: _inkMuted, fontSize: 11)),
              ],
            ),
            if (note != null) ...[
              const SizedBox(height: 2),
              Text(note, style: const TextStyle(color: _gold, fontSize: 10)),
            ],
          ],
        ),
      );
}

class _ObsidianGoldCanvasPainter extends CustomPainter {
  _ObsidianGoldCanvasPainter({
    required this.program,
    required this.phase,
    required this.pulse,
    required this.light,
  });

  final ui.FragmentProgram? program;
  final double phase;
  final double pulse;
  final Offset light;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final prog = program;
    if (prog != null) {
      final shader = prog.fragmentShader();
      shader.setFloat(0, size.width);
      shader.setFloat(1, size.height);
      shader.setFloat(2, phase);
      shader.setFloat(3, pulse);
      shader.setFloat(4, light.dx);
      shader.setFloat(5, light.dy);
      canvas.drawRect(Offset.zero & size, Paint()..shader = shader);
      return;
    }

    // 纯 Flutter 细腻玄墨金箔降级渲染
    final bgPaint = Paint()
      ..shader = const LinearGradient(
        colors: [Color(0xFF0A090C), Color(0xFF060608), Color(0xFF0D0B09)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      ).createShader(Offset.zero & size);
    canvas.drawRect(Offset.zero & size, bgPaint);

    final veinPaint = Paint()
      ..color = const Color(0x1AC5A059)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.0;
    final path = Path()
      ..moveTo(0, size.height * 0.22)
      ..quadraticBezierTo(size.width * 0.5, size.height * (0.14 + 0.04 * math.sin(phase)), size.width, size.height * 0.28);
    canvas.drawPath(path, veinPaint);
  }

  @override
  bool shouldRepaint(covariant _ObsidianGoldCanvasPainter oldDelegate) =>
      oldDelegate.program != program ||
      oldDelegate.phase != phase ||
      oldDelegate.pulse != pulse ||
      oldDelegate.light != light;
}

/// 轻量 3D 视差金箔交互容器（手势滑动实时微倾 + 金箔镜面流光，松手回正）。
class _TactileGoldCard extends StatefulWidget {
  const _TactileGoldCard({required this.child, this.accentGlow = false});

  final Widget child;
  final bool accentGlow;

  @override
  State<_TactileGoldCard> createState() => _TactileGoldCardState();
}

class _TactileGoldCardState extends State<_TactileGoldCard> {
  Offset _tilt = Offset.zero;

  void _onHoverOrDrag(Offset localPos, Size sz) {
    if (sz.isEmpty) return;
    final nx = ((localPos.dx / sz.width) - 0.5).clamp(-0.5, 0.5);
    final ny = ((localPos.dy / sz.height) - 0.5).clamp(-0.5, 0.5);
    setState(() => _tilt = Offset(nx, ny));
  }

  @override
  Widget build(BuildContext context) {
    final rotY = _tilt.dx * 0.07;
    final rotX = -_tilt.dy * 0.06;
    return Listener(
      onPointerMove: (e) {
        final boxSize = context.size;
        _onHoverOrDrag(e.localPosition, boxSize == null || boxSize.isEmpty ? const Size(320, 160) : boxSize);
      },
      onPointerUp: (_) => setState(() => _tilt = Offset.zero),
      onPointerCancel: (_) => setState(() => _tilt = Offset.zero),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOutCubic,
        transformAlignment: Alignment.center,
        transform: Matrix4.identity()
          ..setEntry(3, 2, 0.0011)
          ..rotateX(rotX)
          ..rotateY(rotY),
        foregroundDecoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          gradient: LinearGradient(
            begin: Alignment(-0.8 + _tilt.dx, -0.8 + _tilt.dy),
            end: Alignment(0.8 + _tilt.dx, 0.8 + _tilt.dy),
            colors: [
              Colors.transparent,
              Color(widget.accentGlow ? 0x1EE8CF82 : 0x0FE8CF82),
              Colors.transparent,
            ],
          ),
        ),
        child: widget.child,
      ),
    );
  }
}

/// 精雕双线朱砂篆印。
class _SealChop extends StatelessWidget {
  const _SealChop({this.size = 24});

  final double size;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: Size.square(size),
      painter: _SealChopPainter(),
      child: SizedBox.square(
        dimension: size,
        child: Center(
          child: Text(
            '铭',
            style: TextStyle(
              color: const Color(0xFFFAF4EA),
              fontSize: size * 0.5,
              fontWeight: FontWeight.w800,
              height: 1.0,
            ),
          ),
        ),
      ),
    );
  }
}

class _SealChopPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final rrect = RRect.fromRectAndRadius(rect, const Radius.circular(3.5));
    canvas.drawRRect(rrect, Paint()..color = const Color(0xFFA63429));
    canvas.drawRRect(
      rrect.deflate(1.5),
      Paint()
        ..color = const Color(0x99FAF4EA)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 0.8,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/// 放在主工作台 / 长期战略角落的低调玄墨朱砂「铭」字小方印入口。
class WanshimingCornerSealButton extends StatelessWidget {
  const WanshimingCornerSealButton({super.key, this.channel});

  final ProactiveChannel? channel;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: '万世铭 · 长周期举措碑林',
      child: InkWell(
        key: const ValueKey('wanshiming-corner-seal'),
        borderRadius: BorderRadius.circular(6),
        onTap: () => unawaited(WanshimingPage.open(context, channel: channel)),
        child: Container(
          width: 28,
          height: 28,
          alignment: Alignment.center,
          margin: const EdgeInsets.symmetric(horizontal: 4),
          decoration: BoxDecoration(
            color: const Color(0xFF141114),
            borderRadius: BorderRadius.circular(5),
            border: Border.all(color: const Color(0x99C5A059), width: 1),
            boxShadow: const [BoxShadow(color: Color(0x33A63429), blurRadius: 6)],
          ),
          child: const _SealChop(size: 22),
        ),
      ),
    );
  }
}
