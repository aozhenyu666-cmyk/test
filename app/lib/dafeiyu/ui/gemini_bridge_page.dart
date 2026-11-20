import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';

/// Gemini 桥：电脑上跑 tooling/gemini-bridge（Gemini 的自定义应用 = MCP），手机引擎每 ~20 秒主动去同步。
///
/// 这一页只做三件事：填地址和密钥、看连没连上、立即同步一次。搭建步骤在仓库 tooling/gemini-bridge/README.md。
class GeminiBridgePage extends StatefulWidget {
  const GeminiBridgePage({super.key, this.channel});

  final ProactiveChannel? channel;

  @override
  State<GeminiBridgePage> createState() => _GeminiBridgePageState();
}

class _GeminiBridgePageState extends State<GeminiBridgePage> {
  late final ProactiveChannel _channel = widget.channel ?? ProactiveChannel();
  final TextEditingController _url = TextEditingController();
  final TextEditingController _key = TextEditingController();
  Map<String, dynamic> _data = const {};
  bool _loading = true;
  bool _busy = false;
  bool _failed = false;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    unawaited(_load(first: true));
    _poll = Timer.periodic(const Duration(seconds: 15), (_) {
      if (mounted && !_busy) unawaited(_load());
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _url.dispose();
    _key.dispose();
    super.dispose();
  }

  Future<void> _load({bool first = false}) async {
    final r = await _channel.bridgeOp({'action': 'status'});
    if (!mounted) return;
    setState(() {
      _loading = false;
      _failed = r == null || r['ok'] != true;
      if (!_failed) {
        _data = r!;
        if (first && _url.text.isEmpty) _url.text = '${r['url'] ?? ''}';
      }
    });
  }

  Future<void> _save() async {
    final url = _url.text.trim();
    final key = _key.text.trim();
    if (url.isEmpty || (key.isEmpty && (_data['keyTail'] ?? '') == '')) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('地址和手机密钥都要填（电脑上启动桥时打出来的那两行）')));
      return;
    }
    setState(() => _busy = true);
    final r = await _channel.bridgeOp({'action': 'config', 'url': url, if (key.isNotEmpty) 'key': key});
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (r != null && r['ok'] == true) {
        _data = r;
        _key.clear();
      }
    });
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(r == null
          ? '引擎没应答，没存上'
          : r['ok'] != true
              ? '${r['text'] ?? '没存上'}'
              : r['synced'] == true
                  ? '连上了：Gemini 现在看得到你的今天'
                  : '存好了，但这次没连上：${r['lastError'] ?? r['error'] ?? ''}'),
    ));
  }

  Future<void> _sync() async {
    setState(() => _busy = true);
    final r = await _channel.bridgeOp({'action': 'sync'});
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (r != null && r['ok'] == true) _data = r;
    });
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(r?['synced'] == true ? '同步好了' : '没连上：${r?['lastError'] ?? '引擎没应答'}'),
    ));
  }

  String _ago(Object? at) {
    final t = (at as num?)?.toInt() ?? 0;
    if (t <= 0) return '从没';
    final s = DateTime.now().millisecondsSinceEpoch - t;
    if (s < 60000) return '${(s / 1000).round()} 秒前';
    if (s < 3600000) return '${(s / 60000).round()} 分钟前';
    return '${(s / 3600000).round()} 小时前';
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final configured = _data['configured'] == true;
    final healthy = _data['healthy'] == true;
    final err = '${_data['lastError'] ?? ''}';

    return AppPage.list(
      title: 'Gemini 桥',
      subtitle: const Text('Gemini 的自定义应用连到大肥鱼'),
      actions: [GlassIconButton(tooltip: '立即同步', icon: AppIcons.refresh, onPressed: _busy || !configured ? null : () => unawaited(_sync()))],
      children: [
        OverviewCard(
          leading: const OverviewGlyph(AppIcons.link, tint: 2),
          title: Text(!configured ? '还没连' : (healthy ? '连着' : '断了')),
          subtitle: Text(
            _loading
                ? '读取中…'
                : _failed
                    ? '引擎没应答'
                    : !configured
                        ? '先在电脑上把桥跑起来，再把地址和手机密钥填在下面'
                        : '上次同步成功 ${_ago(_data['lastOkAt'])} · 处理过 Gemini 的 ${_data['applied'] ?? 0} 条命令 · 今天回执 ${_data['receiptsToday'] ?? 0} 条',
          ),
          trailing: configured
              ? StatusChip(tone: healthy ? StatusTone.ok : StatusTone.warn, label: healthy ? '在线' : '离线')
              : null,
        ),
        if (configured && err.isNotEmpty)
          ListPageCard(
            margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              ListPageRow(
                key: const ValueKey('bridge-error'),
                icon: AppIcons.warning,
                iconColor: colors.warning,
                title: '最近一次没连上',
                subtitle: err,
                subtitleMaxLines: 3,
              ),
            ],
          ),
        ListPageCard(
          title: '连接',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: TextField(
                key: const ValueKey('bridge-url'),
                controller: _url,
                keyboardType: TextInputType.url,
                autocorrect: false,
                decoration: const InputDecoration(labelText: '桥的地址', hintText: 'https://你的电脑名.你的tailnet.ts.net', isDense: true),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
              child: TextField(
                key: const ValueKey('bridge-key'),
                controller: _key,
                autocorrect: false,
                obscureText: true,
                inputFormatters: [FilteringTextInputFormatter.deny(RegExp(r'\s'))],
                decoration: InputDecoration(
                  labelText: '手机密钥',
                  hintText: (_data['keyTail'] ?? '') != '' ? '已存（尾号 ${_data['keyTail']}），要换再填' : 'dfy_…（电脑上启动桥时打出来的）',
                  isDense: true,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 14),
              child: FilledButton(
                key: const ValueKey('bridge-save'),
                onPressed: _busy ? null : () => unawaited(_save()),
                child: Text(_busy ? '连接中…' : '保存并连接'),
              ),
            ),
          ],
        ),
        ListPageCard(
          title: 'Gemini 能做什么',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
          children: const [
            ListPageRow(icon: AppIcons.preview, title: '看', subtitle: '今天的时间轴验收点、进度、是不是锁着、余额、截止日、公约；电脑上的笔记（日记 / 私密不读）', subtitleMaxLines: 3),
            ListPageRow(icon: AppIcons.verified, title: '交回执', subtitle: '问过至少 2 个问题、判「过」的回执才算进验收点', subtitleMaxLines: 2),
            ListPageRow(icon: AppIcons.add, title: '加验收点、提议规矩', subtitle: '只能加；规矩要你在「公约」页批', subtitleMaxLines: 2),
            ListPageRow(icon: AppIcons.lock, title: '不能', subtitle: '解锁、加娱乐额度、豁免、改小、删除——桥上根本没有这些工具', subtitleMaxLines: 2),
          ],
        ),
        ListPageCard(
          title: '怎么搭',
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          children: const [
            ListPageRow(icon: AppIcons.computer, title: '1. 电脑上跑桥', subtitle: 'node tooling/gemini-bridge/server.mjs --public-url https://…ts.net --vault 你的笔记库', subtitleMaxLines: 3),
            ListPageRow(icon: AppIcons.web, title: '2. 开到公网', subtitle: '另开终端：tailscale funnel 8787', subtitleMaxLines: 2),
            ListPageRow(icon: AppIcons.askAi, title: '3. Gemini 里加自定义应用', subtitle: '网页版 设置 → 应用 → 添加自定义应用，地址填 https://…ts.net/mcp，授权页输配对口令', subtitleMaxLines: 3),
            ListPageRow(icon: AppIcons.thisPhone, title: '4. 手机上填这一页', subtitle: '地址（不带 /mcp）+ 手机密钥，保存并连接', subtitleMaxLines: 2),
          ],
        ),
      ],
    );
  }
}
