import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../channels/proactive_state.dart';

/// 低调内嵌于大肥鱼角落的《万世铭 · 长期核心路径星图》
/// 极简 Claude 编辑风粗体排版 × 交互式星辰发光聚焦图谱，专为长期大节点设计，绝不混入日常流水账。
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
  // Claude 极简暖墨羊皮纸深色编辑风
  static const _bg = Color(0xFF141311);
  static const _surface = Color(0xFF1E1C19);
  static const _surfaceRaised = Color(0xFF26231F);
  static const _ink = Color(0xFFF6F2EA);
  static const _inkBody = Color(0xFFE4DDD1);
  static const _inkMuted = Color(0xFFB3AA9B);
  static const _accent = Color(0xFFE5A962);
  static const _accentBright = Color(0xFFFFF2D6);
  static const _border = Color(0x2AF6F2EA);

  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  late final AnimationController _pulseCtrl = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 8),
  )..repeat();

  bool _loading = true;
  bool _busy = false;
  List<Map<String, dynamic>> _steles = const [];
  String? _selectedId;
  Offset? _hoverNorm;
  String? _statusMsg;

  static const _fallbackSteles = <Map<String, dynamic>>[
    {
      'id': 'stele-foundation-obsidian',
      'title': '立实证底律：先查基准率，不作伪闭环',
      'date': '2026-11-19',
      'stage': '筑基 · 底律',
      'epoch': '丙午 · 筑基',
      'category': '悟道',
      'body': '一切重大判断先查同类大样本基准率与个人真实行为分布，用反事实剃刀只抓唯一卡脖子变量；缺数据时先跑最小采数实验。',
      'echo': '区分廉价口头表态与高成本真实投入，不靠堆砌道理自欺。',
      'links': ['stele-v6-scheduler', 'stele-live-keeper'],
      'starred': true,
    },
    {
      'id': 'stele-live-keeper',
      'title': 'Gemini Live 真通话与硬件感知常驻',
      'date': '2026-11-24',
      'stage': '破壁 · 外脑',
      'epoch': '丙午 · 破壁',
      'category': '战役',
      'body': '用音频通话态、语音麦、摄像头占用与投影副屏锁住 Live 在线状态；掉回文字页或秒退时自动语音破冰拉回。',
      'echo': '高强度交互靠硬件真实态护航，而非脆弱文字提示。',
      'links': ['stele-foundation-obsidian', 'stele-v6-scheduler'],
      'starred': true,
    },
    {
      'id': 'stele-v6-scheduler',
      'title': '固执调度器与 5m 知识节拍大融合',
      'date': '2026-11-27',
      'stage': '锻刃 · 体系',
      'epoch': '丙午 · 锻刃',
      'category': '纪元',
      'body': '日常刷题与行动必须先申报具体内容方纳入体系；长卷只留决定航向的长期星辰坐标，日常交由临时库消化。',
      'echo': '日常归日常，长卷只留核心轨迹。',
      'links': ['stele-foundation-obsidian', 'stele-live-keeper'],
      'starred': true,
    },
  ];

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _pulseCtrl.dispose();
    super.dispose();
  }

  static Map<String, dynamic> _asMap(Map raw) => {
        for (final e in raw.entries)
          if (e.key is String) e.key as String: e.value,
      };

  Future<void> _refresh() async {
    final res = await _channel.v6Op({'action': 'wanshiming_list'}).catchError((_) => null);
    if (!mounted) return;
    final rawList = res?['steles'];
    final parsed = rawList is List
        ? [for (final x in rawList) if (x is Map) _asMap(x)]
        : _fallbackSteles;
    final list = parsed.isEmpty ? _fallbackSteles : parsed;
    setState(() {
      _loading = false;
      _steles = list;
      if (_selectedId == null || !list.any((s) => '${s['id'] ?? ''}' == _selectedId)) {
        _selectedId = '${list.first['id'] ?? ''}';
      }
    });
  }

  int get _focusedIndex {
    if (_steles.isEmpty) return -1;
    final idx = _steles.indexWhere((s) => '${s['id'] ?? ''}' == _selectedId);
    return idx >= 0 ? idx : 0;
  }

  Set<int> _neighborIndicesOf(int centerIdx) {
    final result = <int>{};
    if (centerIdx < 0 || centerIdx >= _steles.length) return result;
    final center = _steles[centerIdx];
    final centerId = '${center['id'] ?? ''}';
    final explicitLinks = center['links'] is List
        ? {for (final l in (center['links'] as List)) '$l'}
        : const <String>{};

    for (var i = 0; i < _steles.length; i++) {
      if (i == centerIdx) continue;
      final item = _steles[i];
      final id = '${item['id'] ?? ''}';
      final reverseLinks = item['links'] is List
          ? {for (final l in (item['links'] as List)) '$l'}
          : const <String>{};
      if (explicitLinks.contains(id) ||
          reverseLinks.contains(centerId) ||
          (i - centerIdx).abs() == 1) {
        result.add(i);
      }
    }
    return result;
  }

  List<Offset> _computeStarPositions(Size size) {
    final n = _steles.length;
    if (n == 0) return const [];
    if (n == 1) return [Offset(size.width * 0.5, size.height * 0.5)];

    final padX = math.min(58.0, size.width * 0.16);
    final padY = 44.0;
    final usableW = math.max(80.0, size.width - padX * 2);
    final usableH = math.max(80.0, size.height - padY * 2);

    // 柔和 S 形星座路径分布，确保节点舒展且视觉聚焦流畅
    return List<Offset>.generate(n, (i) {
      final t = i / (n - 1);
      final waveX = 0.5 + 0.34 * math.sin(t * math.pi * 1.45 + 0.45);
      final x = padX + usableW * waveX.clamp(0.08, 0.92);
      final y = padY + usableH * t;
      return Offset(x, y);
    });
  }

  void _selectIndex(int idx) {
    if (idx < 0 || idx >= _steles.length) return;
    final nextId = '${_steles[idx]['id'] ?? ''}';
    if (nextId.isNotEmpty && nextId != _selectedId) {
      setState(() => _selectedId = nextId);
    }
  }

  Future<void> _openEditor([Map<String, dynamic>? existing]) async {
    final titleCtrl = TextEditingController(text: '${existing?['title'] ?? ''}');
    final stageCtrl = TextEditingController(text: '${existing?['stage'] ?? existing?['epoch'] ?? '破局 · 主干'}');
    final bodyCtrl = TextEditingController(text: '${existing?['body'] ?? ''}');
    final echoCtrl = TextEditingController(text: '${existing?['echo'] ?? ''}');
    String cat = '${existing?['category'] ?? '战役'}';
    bool starred = existing?['starred'] != false;
    final existingId = '${existing?['id'] ?? ''}';
    final selectedLinks = <String>{
      if (existing?['links'] is List)
        for (final l in (existing!['links'] as List))
          if ('$l'.isNotEmpty && '$l' != existingId) '$l',
    };

    final otherSteles = _steles.where((s) => '${s['id'] ?? ''}' != existingId).toList();

    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: _surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
        side: BorderSide(color: _border),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(20, 20, 20, MediaQuery.viewInsetsOf(ctx).bottom + 24),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  existing == null ? '刻录长期路径节点' : '修订星图节点',
                  style: const TextStyle(color: _accentBright, fontSize: 19, fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 6),
                const Text(
                  '仅存决定长期航向的重要节点与心法，日常刷题记录请留在姐妹临时库中。',
                  style: TextStyle(color: _inkMuted, fontSize: 12.5, height: 1.45),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: titleCtrl,
                  style: const TextStyle(color: _ink, fontWeight: FontWeight.w700, fontSize: 15),
                  decoration: _inputDeco('节点标题 *'),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: const ['抉择', '战役', '悟道', '纪元'].contains(cat) ? cat : '战役',
                        dropdownColor: _surfaceRaised,
                        style: const TextStyle(color: _ink, fontWeight: FontWeight.w600),
                        decoration: _inputDeco('节点性质'),
                        items: const ['战役', '抉择', '悟道', '纪元']
                            .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                            .toList(),
                        onChanged: (v) => setSheetState(() => cat = v ?? '战役'),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: TextField(
                        controller: stageCtrl,
                        style: const TextStyle(color: _ink, fontWeight: FontWeight.w600),
                        decoration: _inputDeco('所属星域/阶段'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: bodyCtrl,
                  maxLines: 4,
                  style: const TextStyle(color: _inkBody, height: 1.5),
                  decoration: _inputDeco('核心路径与判断准则 *'),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: echoCtrl,
                  maxLines: 2,
                  style: const TextStyle(color: _inkBody),
                  decoration: _inputDeco('代价与航向回响（选填）'),
                ),
                if (otherSteles.isNotEmpty) ...[
                  const SizedBox(height: 14),
                  const Text(
                    '关联周围星点（点击主星后将一并点亮）',
                    style: TextStyle(color: _inkMuted, fontSize: 12.5, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 6,
                    children: otherSteles.map((other) {
                      final oid = '${other['id'] ?? ''}';
                      final active = selectedLinks.contains(oid);
                      return FilterChip(
                        label: Text(
                          '${other['title'] ?? ''}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: active ? _accentBright : _inkMuted,
                            fontSize: 12,
                            fontWeight: active ? FontWeight.w700 : FontWeight.w500,
                          ),
                        ),
                        selected: active,
                        selectedColor: const Color(0x38E5A962),
                        backgroundColor: _bg,
                        side: BorderSide(color: active ? _accent : _border),
                        onSelected: (v) => setSheetState(() {
                          if (v) {
                            selectedLinks.add(oid);
                          } else {
                            selectedLinks.remove(oid);
                          }
                        }),
                      );
                    }).toList(),
                  ),
                ],
                const SizedBox(height: 10),
                CheckboxListTile(
                  value: starred,
                  activeColor: _accent,
                  checkColor: _bg,
                  contentPadding: EdgeInsets.zero,
                  title: const Text('列为一等明星（星图中更亮）', style: TextStyle(color: _inkMuted, fontSize: 13)),
                  onChanged: (v) => setSheetState(() => starred = v == true),
                ),
                const SizedBox(height: 8),
                FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: _accent,
                    foregroundColor: _bg,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                  onPressed: () => Navigator.of(ctx).pop(true),
                  child: const Text('点亮并保存节点', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15)),
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
        if (existingId.isNotEmpty) 'id': existingId,
        'title': titleCtrl.text.trim(),
        'category': cat,
        'stage': stageCtrl.text.trim(),
        'epoch': stageCtrl.text.trim(),
        'body': bodyCtrl.text.trim(),
        'echo': echoCtrl.text.trim(),
        'links': selectedLinks.toList(),
        'starred': starred,
      });
      await _refresh();
      if (mounted) {
        final savedItem = res?['item'];
        setState(() {
          _busy = false;
          if (savedItem is Map && savedItem['id'] != null) {
            _selectedId = '${savedItem['id']}';
          }
          _statusMsg = res?['ok'] == true ? '✓ 已点亮长期路径星点' : '已暂存（大肥鱼引擎未连）';
        });
      }
    }
    titleCtrl.dispose();
    stageCtrl.dispose();
    bodyCtrl.dispose();
    echoCtrl.dispose();
  }

  Future<void> _deleteNode(Map<String, dynamic> node) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: _surface,
        title: const Text('移除此星图节点？', style: TextStyle(color: _ink, fontWeight: FontWeight.w800)),
        content: Text('将移出「${node['title'] ?? ''}」，不会影响临时数据库记录。', style: const TextStyle(color: _inkMuted)),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('保留')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: const Color(0xFFA63429)),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('移除'),
          ),
        ],
      ),
    );
    if (confirm == true) {
      await _channel.v6Op({'action': 'wanshiming_delete', 'id': node['id']});
      await _refresh();
      if (mounted) setState(() => _statusMsg = '已移除节点');
    }
  }

  static InputDecoration _inputDeco(String label) => InputDecoration(
        labelText: label,
        labelStyle: const TextStyle(color: _inkMuted, fontSize: 13),
        filled: true,
        fillColor: _bg,
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: const BorderSide(color: _border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: const BorderSide(color: _accent, width: 1.5),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final focusIdx = _focusedIndex;
    final focused = focusIdx >= 0 && focusIdx < _steles.length ? _steles[focusIdx] : null;
    final neighbors = focusIdx >= 0 ? _neighborIndicesOf(focusIdx) : const <int>{};

    return Theme(
      data: ThemeData.dark().copyWith(scaffoldBackgroundColor: _bg),
      child: Scaffold(
        backgroundColor: _bg,
        appBar: AppBar(
          backgroundColor: _bg,
          foregroundColor: _ink,
          elevation: 0,
          titleSpacing: 4,
          title: const Row(
            children: [
              _SealChop(size: 24),
              SizedBox(width: 10),
              Text(
                '万世铭',
                style: TextStyle(color: _ink, letterSpacing: 2.5, fontSize: 20, fontWeight: FontWeight.w800),
              ),
            ],
          ),
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 10),
              child: FilledButton.icon(
                key: const ValueKey('wsm-add-node-btn'),
                style: FilledButton.styleFrom(
                  backgroundColor: _surfaceRaised,
                  foregroundColor: _accentBright,
                  side: const BorderSide(color: _border),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                ),
                onPressed: _busy ? null : () => unawaited(_openEditor()),
                icon: const Icon(Icons.add, size: 18),
                label: const Text('刻录节点', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
              ),
            ),
          ],
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator(color: _accent))
            : ListView(
                padding: const EdgeInsets.fromLTRB(18, 6, 18, 48),
                children: [
                  const Text(
                    '长期核心路径星图',
                    style: TextStyle(color: _ink, fontSize: 24, fontWeight: FontWeight.w800, height: 1.25),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    '不记日常流水账 · 靠近每颗星点即可点亮微光，点中主星即可聚焦周围关联轨迹',
                    style: TextStyle(color: _inkMuted, fontSize: 13.5, fontWeight: FontWeight.w500, height: 1.5),
                  ),
                  if (_statusMsg != null) ...[
                    const SizedBox(height: 10),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                      decoration: BoxDecoration(
                        color: _surface,
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: const Color(0x55E5A962)),
                      ),
                      child: Text(_statusMsg!, style: const TextStyle(color: _accentBright, fontSize: 13, fontWeight: FontWeight.w600)),
                    ),
                  ],
                  const SizedBox(height: 16),
                  _buildConstellationCanvas(focusIdx, neighbors),
                  const SizedBox(height: 18),
                  if (focused != null) _buildFocusEditorialCard(focused, neighbors),
                ],
              ),
      ),
    );
  }

  Widget _buildConstellationCanvas(int focusIdx, Set<int> neighbors) {
    return Container(
      key: const ValueKey('wsm-star-map'),
      height: 290,
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: _border),
      ),
      clipBehavior: Clip.antiAlias,
      child: LayoutBuilder(
        builder: (ctx, constraints) {
          final size = Size(constraints.maxWidth, constraints.maxHeight);
          final positions = _computeStarPositions(size);

          return MouseRegion(
            onHover: (e) => setState(() => _hoverNorm = Offset(
              (e.localPosition.dx / size.width).clamp(0.0, 1.0),
              (e.localPosition.dy / size.height).clamp(0.0, 1.0),
            )),
            onExit: (_) => setState(() => _hoverNorm = null),
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onPanUpdate: (d) => setState(() => _hoverNorm = Offset(
                (d.localPosition.dx / size.width).clamp(0.0, 1.0),
                (d.localPosition.dy / size.height).clamp(0.0, 1.0),
              )),
              onPanEnd: (_) => setState(() => _hoverNorm = null),
              onTapUp: (d) {
                if (positions.isEmpty) return;
                int? hit;
                double bestD2 = 52 * 52;
                for (var i = 0; i < positions.length; i++) {
                  final d2 = (positions[i] - d.localPosition).distanceSquared;
                  if (d2 < bestD2) {
                    bestD2 = d2;
                    hit = i;
                  }
                }
                if (hit != null) _selectIndex(hit);
              },
              child: Stack(
                children: [
                  Positioned.fill(
                    child: AnimatedBuilder(
                      animation: _pulseCtrl,
                      builder: (_, _) => CustomPaint(
                        painter: _LuminousStarMapPainter(
                          steles: _steles,
                          positions: positions,
                          focusIdx: focusIdx,
                          neighbors: neighbors,
                          hoverNorm: _hoverNorm,
                          phase: _pulseCtrl.value * math.pi * 2,
                        ),
                      ),
                    ),
                  ),
                  // 清晰可读的粗体星标锚点（点击任意标牌同样聚焦该星与周边节点）
                  for (var i = 0; i < positions.length; i++)
                    _buildStarLabelChip(i, positions[i], size, i == focusIdx, neighbors.contains(i)),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildStarLabelChip(int idx, Offset pos, Size size, bool isFocus, bool isNeighbor) {
    final s = _steles[idx];
    final rightSide = pos.dx < size.width * 0.56;
    final left = rightSide ? pos.dx + 16 : math.max(8.0, pos.dx - 164);
    final top = (pos.dy - 14).clamp(8.0, size.height - 34);
    final dimmed = !isFocus && !isNeighbor;

    return Positioned(
      left: left,
      top: top,
      child: GestureDetector(
        key: ValueKey('wsm-star-node-${s['id'] ?? idx}'),
        onTap: () => _selectIndex(idx),
        child: Container(
          constraints: const BoxConstraints(maxWidth: 156),
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
          decoration: BoxDecoration(
            color: isFocus
                ? const Color(0xE82A251D)
                : (isNeighbor ? const Color(0xCC221F1A) : const Color(0x99181613)),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: isFocus
                  ? _accentBright
                  : (isNeighbor ? const Color(0x88E5A962) : const Color(0x22F6F2EA)),
              width: isFocus ? 1.3 : 1.0,
            ),
          ),
          child: Text(
            '${s['title'] ?? ''}',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: isFocus ? _accentBright : (dimmed ? const Color(0x70B3AA9B) : _ink),
              fontSize: isFocus ? 12.5 : 11.5,
              fontWeight: isFocus ? FontWeight.w800 : (isNeighbor ? FontWeight.w700 : FontWeight.w500),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFocusEditorialCard(Map<String, dynamic> s, Set<int> neighbors) {
    final stage = '${s['stage'] ?? s['epoch'] ?? s['category'] ?? '长期路径'}';
    final date = '${s['date'] ?? ''}'.trim();
    final echo = '${s['echo'] ?? ''}'.trim();

    return Container(
      key: const ValueKey('wsm-focus-card'),
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0x55E5A962)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                date.isNotEmpty ? '$stage  ·  $date' : stage,
                style: const TextStyle(
                  color: _accent,
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.6,
                ),
              ),
              const Spacer(),
              if (s['starred'] == true) const Icon(Icons.auto_awesome, color: _accentBright, size: 17),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            '${s['title'] ?? ''}',
            style: const TextStyle(
              color: _ink,
              fontSize: 22,
              fontWeight: FontWeight.w800,
              height: 1.34,
            ),
          ),
          const SizedBox(height: 14),
          Text(
            '${s['body'] ?? ''}',
            style: const TextStyle(
              color: _inkBody,
              fontSize: 15.5,
              fontWeight: FontWeight.w500,
              height: 1.78,
            ),
          ),
          if (echo.isNotEmpty) ...[
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.fromLTRB(14, 10, 12, 10),
              decoration: const BoxDecoration(
                color: _bg,
                border: Border(left: BorderSide(color: _accent, width: 3)),
              ),
              child: Text(
                echo,
                style: const TextStyle(color: _inkMuted, fontSize: 14, fontWeight: FontWeight.w600, height: 1.6),
              ),
            ),
          ],
          if (neighbors.isNotEmpty) ...[
            const SizedBox(height: 20),
            const Divider(color: _border, height: 1),
            const SizedBox(height: 14),
            const Text(
              '周围关联星点（点击切换聚焦）',
              style: TextStyle(color: _inkMuted, fontSize: 12.5, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 10),
            Wrap(
              key: const ValueKey('wsm-neighbors-strip'),
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final ni in neighbors)
                  ActionChip(
                    key: ValueKey('wsm-neighbor-$ni'),
                    avatar: const Icon(Icons.star_rounded, size: 16, color: _accentBright),
                    backgroundColor: _surfaceRaised,
                    side: const BorderSide(color: Color(0x66E5A962)),
                    label: Text(
                      '${_steles[ni]['title'] ?? ''}',
                      style: const TextStyle(color: _ink, fontSize: 12.5, fontWeight: FontWeight.w700),
                    ),
                    onPressed: () => _selectIndex(ni),
                  ),
              ],
            ),
          ],
          const SizedBox(height: 18),
          const Divider(color: _border, height: 1),
          const SizedBox(height: 10),
          Wrap(
            spacing: 10,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: _accentBright,
                  side: const BorderSide(color: _border),
                ),
                onPressed: _busy ? null : () => unawaited(_openEditor(s)),
                icon: const Icon(Icons.edit_outlined, size: 16),
                label: const Text('修订节点', style: TextStyle(fontWeight: FontWeight.w700)),
              ),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  foregroundColor: _inkMuted,
                  side: const BorderSide(color: _border),
                ),
                onPressed: () {
                  final md = '## ${s['title']}\n\n- 阶段：`$stage`\n\n${s['body']}\n';
                  unawaited(Clipboard.setData(ClipboardData(text: md)));
                  setState(() => _statusMsg = '✓ 已复制节点 Markdown');
                },
                icon: const Icon(Icons.copy_all_outlined, size: 16),
                label: const Text('复制原文', style: TextStyle(fontWeight: FontWeight.w600)),
              ),
              TextButton(
                style: TextButton.styleFrom(foregroundColor: const Color(0xFFE07A6B)),
                onPressed: _busy ? null : () => unawaited(_deleteNode(s)),
                child: const Text('移除', style: TextStyle(fontWeight: FontWeight.w600)),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _LuminousStarMapPainter extends CustomPainter {
  _LuminousStarMapPainter({
    required this.steles,
    required this.positions,
    required this.focusIdx,
    required this.neighbors,
    required this.hoverNorm,
    required this.phase,
  });

  final List<Map<String, dynamic>> steles;
  final List<Offset> positions;
  final int focusIdx;
  final Set<int> neighbors;
  final Offset? hoverNorm;
  final double phase;

  @override
  void paint(Canvas canvas, Size size) {
    // 1. Subtle deep warm radial starfield backdrop
    final bgPaint = Paint()
      ..shader = const RadialGradient(
        center: Alignment(0.0, -0.15),
        radius: 1.05,
        colors: [Color(0xFF231F19), Color(0xFF151412)],
      ).createShader(Offset.zero & size);
    canvas.drawRect(Offset.zero & size, bgPaint);

    // Subtle decorative distant micro-stars
    final dustPaint = Paint()..color = const Color(0x28FFF2D6);
    for (var i = 0; i < 22; i++) {
      final dx = ((i * 73 + 19) % 100) / 100.0 * size.width;
      final dy = ((i * 41 + 11) % 100) / 100.0 * size.height;
      canvas.drawCircle(Offset(dx, dy), (i % 3 == 0) ? 1.2 : 0.7, dustPaint);
    }

    if (positions.isEmpty) return;

    // 2. Draw spine connections + focus-neighbor radiant arcs
    final spinePaint = Paint()
      ..color = const Color(0x22E5A962)
      ..strokeWidth = 1.1
      ..style = PaintingStyle.stroke;
    for (var i = 0; i < positions.length - 1; i++) {
      canvas.drawLine(positions[i], positions[i + 1], spinePaint);
    }

    if (focusIdx >= 0 && focusIdx < positions.length) {
      final center = positions[focusIdx];
      for (final ni in neighbors) {
        if (ni < 0 || ni >= positions.length) continue;
        final np = positions[ni];
        final glowLine = Paint()
          ..shader = LinearGradient(
            colors: const [Color(0xEEFFF2D6), Color(0x99E5A962)],
          ).createShader(Rect.fromPoints(center, np))
          ..strokeWidth = 2.0
          ..style = PaintingStyle.stroke;
        canvas.drawLine(center, np, glowLine);
      }
    }

    // 3. Draw luminous stars with proximity & focus brightness
    final hoverPx = hoverNorm != null
        ? Offset(hoverNorm!.dx * size.width, hoverNorm!.dy * size.height)
        : null;

    for (var i = 0; i < positions.length; i++) {
      final pos = positions[i];
      final isFocus = i == focusIdx;
      final isNeighbor = neighbors.contains(i);
      final isStarred = steles[i]['starred'] == true;

      double proximity = 0.0;
      if (hoverPx != null) {
        final dist = (hoverPx - pos).distance;
        proximity = (1.0 - (dist / 120.0)).clamp(0.0, 1.0);
      }

      final breath = 0.5 + 0.5 * math.sin(phase + i * 1.1);
      final baseBright = isFocus
          ? 1.0
          : (isNeighbor ? 0.72 : math.max(0.24, proximity * 0.92));

      final haloRadius = isFocus
          ? 34.0 + breath * 5.0
          : (isNeighbor ? 20.0 + breath * 2.5 : (13.0 + proximity * 18.0));

      final haloPaint = Paint()
        ..shader = RadialGradient(
          colors: [
            const Color(0xFFFFF2D6).withValues(alpha: (0.72 * baseBright).clamp(0.0, 1.0)),
            const Color(0xFFE5A962).withValues(alpha: (0.34 * baseBright).clamp(0.0, 1.0)),
            Colors.transparent,
          ],
          stops: const [0.0, 0.35, 1.0],
        ).createShader(Rect.fromCircle(center: pos, radius: haloRadius));
      canvas.drawCircle(pos, haloRadius, haloPaint);

      final coreRadius = isFocus ? 6.2 : (isNeighbor ? 4.4 : (isStarred ? 3.4 : 2.8));
      canvas.drawCircle(
        pos,
        coreRadius,
        Paint()
          ..color = (isFocus || isNeighbor)
              ? const Color(0xFFFFF6E4)
              : const Color(0xFFE5A962).withValues(alpha: math.max(0.45, proximity)),
      );
    }
  }

  @override
  bool shouldRepaint(covariant _LuminousStarMapPainter oldDelegate) =>
      oldDelegate.steles != steles ||
      oldDelegate.focusIdx != focusIdx ||
      oldDelegate.hoverNorm != hoverNorm ||
      oldDelegate.phase != phase;
}

class _SealChop extends StatelessWidget {
  const _SealChop({this.size = 24});

  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: const Color(0xFFA63429),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: const Color(0x99FAF4EA), width: 0.9),
      ),
      child: Text(
        '铭',
        style: TextStyle(
          color: const Color(0xFFFAF4EA),
          fontSize: size * 0.52,
          fontWeight: FontWeight.w800,
          height: 1.0,
        ),
      ),
    );
  }
}

/// 放在主工作台 / 长期战略角落的低调「铭」字星图入口按钮。
class WanshimingCornerSealButton extends StatelessWidget {
  const WanshimingCornerSealButton({super.key, this.channel});

  final ProactiveChannel? channel;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: '万世铭 · 长期核心路径星图',
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
            color: const Color(0xFF1E1C19),
            borderRadius: BorderRadius.circular(5),
            border: Border.all(color: const Color(0x99E5A962), width: 1),
          ),
          child: const _SealChop(size: 22),
        ),
      ),
    );
  }
}
