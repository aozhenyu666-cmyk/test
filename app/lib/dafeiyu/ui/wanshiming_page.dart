import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../channels/proactive_state.dart';

/// 低调内嵌于大肥鱼角落的《万世铭》博物馆级玄墨金箔长卷。
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

class _WanshimingPageState extends State<WanshimingPage> {
  static const _bg = Color(0xFF070709);
  static const _surface = Color(0xFF101015);
  static const _ink = Color(0xFFECE7DC);
  static const _inkMuted = Color(0xFF8E887B);
  static const _gold = Color(0xFFC5A059);
  static const _goldBright = Color(0xFFE8CF82);
  static const _sealRed = Color(0xFFA63429);

  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();

  bool _loading = true;
  bool _busy = false;
  String _filter = '全部';
  List<Map<String, dynamic>> _steles = const [];
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
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    final res = await _channel.v6Op({'action': 'wanshiming_list'}).catchError((_) => null);
    if (!mounted) return;
    final rawList = res?['steles'];
    final parsed = rawList is List
        ? [for (final x in rawList) if (x is Map) _asMap(x)]
        : _fallbackSteles;
    setState(() {
      _loading = false;
      _steles = parsed.isEmpty ? _fallbackSteles : parsed;
    });
  }

  static Map<String, dynamic> _asMap(Map raw) => {
        for (final e in raw.entries)
          if (e.key is String) e.key as String: e.value,
      };

  List<Map<String, dynamic>> get _filtered {
    return _steles.where((s) {
      if (_filter == '典藏') return s['starred'] == true;
      if (_filter != '全部') return '${s['category'] ?? ''}' == _filter;
      return true;
    }).toList();
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
      backgroundColor: _surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(18)),
        side: BorderSide(color: Color(0x44C5A059)),
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
                        dropdownColor: _surface,
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
          backgroundColor: _surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
            side: const BorderSide(color: Color(0x55C5A059)),
          ),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '${stele['date'] ?? ''}${stele['epoch'] != null && '${stele['epoch']}'.isNotEmpty ? ' · ${stele['epoch']}' : ''}',
                          style: const TextStyle(color: _gold, fontSize: 12, letterSpacing: 1.5),
                        ),
                      ),
                      if (stele['starred'] == true)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                          decoration: BoxDecoration(color: _sealRed, borderRadius: BorderRadius.circular(3)),
                          child: const Text('典藏', style: TextStyle(color: _ink, fontSize: 10, fontWeight: FontWeight.w600)),
                        ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '${stele['title'] ?? ''}',
                    style: const TextStyle(color: _ink, fontSize: 20, fontWeight: FontWeight.w600, height: 1.4),
                  ),
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
    final starredCount = _steles.where((s) => s['starred'] == true).length;

    return Theme(
      data: ThemeData.dark().copyWith(scaffoldBackgroundColor: _bg),
      child: Scaffold(
        backgroundColor: _bg,
        appBar: AppBar(
          backgroundColor: _bg,
          foregroundColor: _goldBright,
          elevation: 0,
          title: Row(
            children: [
              Container(
                width: 24,
                height: 24,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: _sealRed,
                  borderRadius: BorderRadius.circular(3),
                  border: Border.all(color: const Color(0x99F5EFE3), width: 0.8),
                ),
                child: const Text('铭', style: TextStyle(color: Color(0xFFFAF4EA), fontSize: 12, fontWeight: FontWeight.w700)),
              ),
              const SizedBox(width: 10),
              const Text('万世铭', style: TextStyle(color: _ink, letterSpacing: 4, fontSize: 18, fontWeight: FontWeight.w600)),
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
        body: ListView(
          padding: const EdgeInsets.fromLTRB(18, 12, 18, 48),
          children: [
            Container(
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [Color(0xFF141318), Color(0xFF0A0A0D)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: const Color(0x36C5A059)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'STELE OF TEN THOUSAND EPOCHS',
                    style: TextStyle(color: _gold, fontSize: 10, letterSpacing: 2.5),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    '记长路所行之真举，存百代可鉴之回响。',
                    style: TextStyle(color: _inkMuted, fontSize: 13.5, letterSpacing: 1),
                  ),
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      _statBox('已铭石碑', '${_steles.length}'),
                      const SizedBox(width: 12),
                      _statBox('朱印典藏', '$starredCount'),
                    ],
                  ),
                ],
              ),
            ),
            if (_statusMsg != null) ...[
              const SizedBox(height: 10),
              Text(_statusMsg!, style: const TextStyle(color: _goldBright, fontSize: 12)),
            ],
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: ['全部', '抉择', '战役', '悟道', '纪元', '典藏'].map((cat) {
                final active = _filter == cat;
                return ChoiceChip(
                  label: Text(cat),
                  selected: active,
                  selectedColor: const Color(0x33C5A059),
                  backgroundColor: _surface,
                  side: BorderSide(color: active ? _goldBright : const Color(0x2EC5A059)),
                  labelStyle: TextStyle(color: active ? _goldBright : _inkMuted, fontSize: 12),
                  onSelected: (_) => setState(() => _filter = cat),
                );
              }).toList(),
            ),
            const SizedBox(height: 18),
            if (_loading)
              const Center(child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator(color: _gold)))
            else
              for (final s in items)
                Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(12),
                    onTap: () => unawaited(_openRubbing(s)),
                    child: Container(
                      padding: const EdgeInsets.all(18),
                      decoration: BoxDecoration(
                        color: _surface,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(
                          color: s['starred'] == true ? const Color(0x66C5A059) : const Color(0x2CC5A059),
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
                                Text('· ${s['epoch']}', style: const TextStyle(color: _inkMuted, fontSize: 11.5)),
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
                              if (s['starred'] == true) ...[
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
                            style: const TextStyle(color: _ink, fontSize: 17, fontWeight: FontWeight.w600, height: 1.4),
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
          ],
        ),
      ),
    );
  }

  Widget _statBox(String label, String val) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0x33C5A059)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(val, style: const TextStyle(color: _goldBright, fontSize: 18, fontWeight: FontWeight.w600)),
            const SizedBox(width: 8),
            Text(label, style: const TextStyle(color: _inkMuted, fontSize: 11.5)),
          ],
        ),
      );
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
          child: Container(
            width: 22,
            height: 22,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: const Color(0xFFA63429),
              borderRadius: BorderRadius.circular(3),
            ),
            child: const Text(
              '铭',
              style: TextStyle(color: Color(0xFFFAF4EA), fontSize: 12, fontWeight: FontWeight.w700, height: 1.1),
            ),
          ),
        ),
      ),
    );
  }
}
