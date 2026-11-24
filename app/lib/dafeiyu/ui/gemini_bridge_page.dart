import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../ui/common/dispose_after_route.dart';
import '../../ui/common/list_page_row.dart';
import '../../ui/common/overview_card.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../channels/proactive_state.dart';
import 'live_core_atlas_page.dart';

enum _GeminiBridgeScene { icebreak, lockBounce, eveningCards, bossAudit }

/// Gemini 桥：电脑上跑 tooling/gemini-bridge（Gemini 的自定义应用 = MCP），手机引擎每 ~20 秒主动去同步。
///
/// 除了地址/密钥连接与同步，还提供「主动触发矩阵状态 & 4 场景一键唤起」。
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
  Map<String, dynamic>? _today;
  Map<String, Object?>? _wall;
  Map<String, dynamic>? _bossBrief;
  int _snoozeUntilMs = 0;
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
    unawaited(_channel.todayStatus().then((t) {
      if (mounted && t != null) setState(() => _today = t);
    }).catchError((_) {}));
    unawaited(_channel.wallStatus().then((w) {
      if (mounted && w != null) setState(() => _wall = w);
    }).catchError((_) {}));
    unawaited(_channel.bossBrief().then((b) {
      if (mounted && b != null) setState(() => _bossBrief = b);
    }).catchError((_) {}));
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

  ({String url, String key}) _parseBridgeBundle(String raw) {
    final text = raw.trim();
    var url = '';
    final urlMatch = RegExp(r'https?://[^\s\x27"<>]+', caseSensitive: false).firstMatch(text);
    if (urlMatch != null) {
      url = urlMatch.group(0)!
          .replaceFirst(RegExp(r'[.,;)\]}]+$'), '')
          .replaceFirst(RegExp(r'(/mcp|/bridge/sync|/bridge/local/today|/healthz)?/*$', caseSensitive: false), '');
    }
    var key = '';
    final dfyMatch = RegExp(r'dfy_[A-Za-z0-9_-]{6,}').firstMatch(text);
    if (dfyMatch != null) {
      key = dfyMatch.group(0)!;
    } else {
      final tokenMatch = RegExp(r'(?:key|token|密钥)\s*[:=]\s*([^\s\x27"]+)', caseSensitive: false).firstMatch(text);
      if (tokenMatch != null) key = tokenMatch.group(1)!.trim();
    }
    return (url: url, key: key);
  }

  Future<void> _applyBridgeBundle(String raw) async {
    final parsed = _parseBridgeBundle(raw);
    if (parsed.url.isEmpty && parsed.key.isEmpty) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('没识别出桥地址（https://...）或手机密钥（dfy_...）')),
      );
      return;
    }
    setState(() {
      if (parsed.url.isNotEmpty) _url.text = parsed.url;
      if (parsed.key.isNotEmpty) _key.text = parsed.key;
    });
    final hasKeyNow = parsed.key.isNotEmpty || _key.text.trim().isNotEmpty || ('${_data['keyTail'] ?? ''}'.isNotEmpty);
    if (_url.text.trim().isNotEmpty && hasKeyNow) {
      await _save();
    } else {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(parsed.url.isNotEmpty ? '已填入桥地址，还缺手机密钥（dfy_...）' : '已填入密钥，还缺桥地址（https://...）')),
      );
    }
  }

  Future<void> _pasteBridgeConfig() async {
    final clip = await Clipboard.getData(Clipboard.kTextPlain);
    final raw = (clip?.text ?? '').trim();
    if (raw.isNotEmpty) {
      final parsed = _parseBridgeBundle(raw);
      if (parsed.url.isNotEmpty || parsed.key.isNotEmpty) {
        await _applyBridgeBundle(raw);
        return;
      }
    }
    if (!mounted) return;
    final pasteCtl = TextEditingController(text: raw);
    final submitted = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('一键导入电脑桥配置'),
        content: TextField(
          key: const ValueKey('bridge-paste-dialog-input'),
          controller: pasteCtl,
          maxLines: 5,
          decoration: const InputDecoration(
            hintText: '粘贴电脑终端输出的桥地址（https://...:8443）和手机密钥（dfy_...）',
            border: OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, pasteCtl.text.trim()),
            child: const Text('识别并连接'),
          ),
        ],
      ),
    );
    disposeAfterRoute(pasteCtl);
    if (submitted != null && submitted.isNotEmpty) {
      await _applyBridgeBundle(submitted);
    }
  }

  String _diagnoseBridgeError(String err) {
    final lower = err.toLowerCase();
    if (lower.contains('401') || lower.contains('unauthorized') || lower.contains('token') || lower.contains('key')) {
      return '$err\n排查：密钥和电脑桥不一致，请重新点击「一键粘贴电脑桥配置」导入最新 dfy_ 密钥。';
    }
    if (lower.contains('404')) {
      return '$err\n排查：桥地址不要带 /mcp 或路径尾巴，填域名（如 https://xxx.ts.net:8443）即可。';
    }
    if (lower.contains('timeout') ||
        lower.contains('timed out') ||
        lower.contains('socket') ||
        lower.contains('connection') ||
        lower.contains('refused') ||
        lower.contains('handshake') ||
        lower.contains('failed host')) {
      return '$err\n排查：请确认电脑端 gemini-bridge 在运行，且 Tailscale Funnel 端口（常见为 :8443）与桥地址一致。';
    }
    return err;
  }

  String _ago(Object? at) {
    final t = (at as num?)?.toInt() ?? 0;
    if (t <= 0) return '从没';
    final s = DateTime.now().millisecondsSinceEpoch - t;
    if (s < 60000) return '${(s / 1000).round()} 秒前';
    if (s < 3600000) return '${(s / 60000).round()} 分钟前';
    return '${(s / 3600000).round()} 小时前';
  }

  String _scenePrompt(_GeminiBridgeScene scene) {
    int numOf(Object? v, [int d = 0]) => v is num ? v.round() : d;
    final charts = _today?['charts'] is Map ? _today!['charts'] as Map : const {};
    final buckets = charts['buckets'] is Map ? charts['buckets'] as Map : const {};
    final briefObj = _today?['brief'] is Map ? _today!['brief'] as Map : const {};
    final workMin = numOf(buckets['mainlineMin']) + numOf(buckets['videoStudyMin']) + numOf(buckets['geminiMin'] ?? _today?['geminiMinutesToday']);
    final resolvedWork = workMin > 0 ? workMin : numOf(briefObj['focusMin']);
    final funMin = buckets['funMin'] is num ? numOf(buckets['funMin']) : numOf(briefObj['funMin']);
    final running = _today?['running'] is Map ? '${(_today!['running'] as Map)['title'] ?? ''}'.trim() : '';
    final tasksRaw = _today?['tasks'] is List ? _today!['tasks'] as List : const [];
    final tasksText = [
      for (final x in tasksRaw.take(3))
        if (x is Map && '${x['title'] ?? ''}'.trim().isNotEmpty) '${x['title']}',
    ].join('；');
    final lockWhy = '${_wall?['why'] ?? ''}'.trim();
    final bridgeOnline = _data['healthy'] == true;
    final bridgeHint = bridgeOnline
        ? '（大肥鱼桥已在线，可直接调 dafeiyu_today / dafeiyu_wall 核对最新数据）'
        : '（如桥未通请直接基于下列现场数据回答）';

    switch (scene) {
      case _GeminiBridgeScene.icebreak:
        return [
          '【场景：2 分钟破冰规划 · 定下今天第一步】$bridgeHint',
          '- 当前现场：${running.isNotEmpty ? '进行中「$running」' : '当前无活跃专注局'} · 今日已专注 ${resolvedWork}m / 娱乐 ${funMin}m',
          if (tasksText.isNotEmpty) '- 今日候选任务：$tasksText',
          '请只用 2 分钟：帮我从当下拖延/漂移中切出来，直接定死今天接下来 25 分钟唯一的第一步动作、允许用的 App 和到点交付物，别泛泛鼓励。',
        ].join('\n');
      case _GeminiBridgeScene.lockBounce:
        return [
          '【场景：30 秒撞锁卡点诊断】$bridgeHint',
          '- 现场锁态：${_wall?['locked'] == true ? '锁定中（${lockWhy.isEmpty ? '门禁规则拦截' : lockWhy}）' : '未锁定/刚被拦'} · 今日娱乐 ${funMin}m',
          if (running.isNotEmpty) '- 当前在做的局：$running',
          '请用 30 秒做卡点诊断：1) 我刚连续撞锁被拦，究竟真要解决什么问题，还是在逃避当前任务？2) 给出一个 2 分钟可立刻回工位的替代动作。',
        ].join('\n');
      case _GeminiBridgeScene.eveningCards:
        return [
          '【场景：晚间笔记与今日思考压成明天题库卡 / 因果卡】$bridgeHint',
          '- 今日现场：累计工作 ${resolvedWork}m · 今日重点：$tasksText',
          '请逼我用自己的话复述今天最关键的 1–2 个知识点或踩坑机制，并直接帮我压成：1) 可导入 DSH 锁屏题库的 JSON/文本题库卡（选择/知识卡/小问题）；2) 一张「触发→机制→下次行动」因果卡。',
        ].join('\n');
      case _GeminiBridgeScene.bossAudit:
        final remotePrompt = '${_bossBrief?['bossPrompt'] ?? ''}'.trim();
        if (remotePrompt.isNotEmpty) return remotePrompt;
        final todayPrompt = '${(_today?['bossBrief'] is Map ? (_today!['bossBrief'] as Map)['bossPrompt'] : '') ?? ''}'.trim();
        if (todayPrompt.isNotEmpty) return todayPrompt;
        return [
          '【角色指令：冷血主管 / 老板全局审视，零寒暄、严禁只盯单一微小时段】$bridgeHint',
          '- 今日工作 ${resolvedWork}m vs 娱乐 ${funMin}m · 门禁：${_wall?['locked'] == true ? '锁定（$lockWhy）' : '放行'}${tasksText.isNotEmpty ? ' · 计划：$tasksText' : ''}',
          '请先查客观事实，然后直接对我提出 3 个直击全局逃避模式的尖锐追问（承诺跳票、伪忙碌/娱乐占比、撞锁模式），并下达未来 24 小时唯一止损硬指令。',
        ].join('\n');
    }
  }

  Future<void> _triggerScene(_GeminiBridgeScene scene) async {
    final topic = switch (scene) {
      _GeminiBridgeScene.icebreak => '2 分钟破冰规划',
      _GeminiBridgeScene.lockBounce => '30 秒撞锁卡点诊断',
      _GeminiBridgeScene.eveningCards => '晚间笔记压成题库卡',
      _GeminiBridgeScene.bossAudit => '老板全局审视质询',
    };
    final prompt = _scenePrompt(scene);
    await Clipboard.setData(ClipboardData(text: prompt));
    final ok = await _channel.launchGeminiProactive(
      topic: topic,
      brief: prompt,
      mode: 'countdown_3s',
      preferVoice: true,
    );
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(
        content: Text(ok ? '已唤起 Gemini「$topic」（上下文已写入剪贴板）' : '已复制「$topic」现场 Prompt（可切去 Gemini 粘贴）'),
      ),
    );
  }

  Future<void> _snoozeAuto() async {
    final until = DateTime.now().millisecondsSinceEpoch + 30 * 60000;
    setState(() => _snoozeUntilMs = until);
    unawaited(_channel.storylineOp({'action': 'snooze_gemini', 'minutes': 30}).catchError((_) => null));
    if (!mounted) return;
    final dt = DateTime.fromMillisecondsSinceEpoch(until);
    final hhmm = '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(content: Text('已将自动触发延后 30 分钟（至 $hhmm，手动唤起不受限制）')),
    );
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
      actions: [
        GlassIconButton(
          key: const ValueKey('bridge-open-atlas-btn'),
          tooltip: 'Live 窥视核心与图谱',
          icon: AppIcons.diagram,
          onPressed: () => unawaited(LiveCoreAtlasPage.open(context, channel: _channel)),
        ),
        GlassIconButton(tooltip: '立即同步', icon: AppIcons.refresh, onPressed: _busy || !configured ? null : () => unawaited(_sync())),
      ],
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
                subtitle: _diagnoseBridgeError(err),
                subtitleMaxLines: 5,
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
                decoration: const InputDecoration(labelText: '桥的地址', hintText: 'https://你的电脑名.你的tailnet.ts.net:8443', isDense: true),
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
              child: Wrap(
                spacing: 10,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  FilledButton(
                    key: const ValueKey('bridge-save'),
                    onPressed: _busy ? null : () => unawaited(_save()),
                    child: Text(_busy ? '连接中…' : '保存并连接'),
                  ),
                  OutlinedButton.icon(
                    key: const ValueKey('bridge-paste-bundle-btn'),
                    onPressed: _busy ? null : () => unawaited(_pasteBridgeConfig()),
                    icon: const Icon(Icons.content_paste_go, size: 18),
                    label: const Text('一键粘贴电脑桥配置'),
                  ),
                ],
              ),
            ),
          ],
        ),
        _triggerMatrixCard(colors),
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

  Widget _triggerMatrixCard(DshColors colors) {
    final theme = Theme.of(context);
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final snoozed = _snoozeUntilMs > nowMs;
    final snoozeMinLeft = snoozed ? ((_snoozeUntilMs - nowMs) / 60000).ceil() : 0;
    final charts = _today?['charts'] is Map ? _today!['charts'] as Map : const {};
    final buckets = charts['buckets'] is Map ? charts['buckets'] as Map : const {};
    final gemMin = (buckets['geminiMin'] as num?)?.round() ?? (_today?['geminiMinutesToday'] as num?)?.round() ?? 0;
    final funMin = (buckets['funMin'] as num?)?.round() ?? 0;

    return ListPageCard(
      key: const ValueKey('gemini-trigger-matrix-card'),
      title: '主动触发矩阵状态 & 4 场景一键唤起',
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 20),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '分级冷却：急卡点 10m · 常规 20m · 日上限 10 次 · 绝不强抢屏',
                      style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                    ),
                  ),
                  const SizedBox(width: 8),
                  StatusChip(
                    tone: snoozed ? StatusTone.warn : (_data['healthy'] == true ? StatusTone.ok : StatusTone.idle),
                    label: snoozed ? '已延后 ${snoozeMinLeft}m' : (_data['healthy'] == true ? '矩阵就绪' : '本地兜底'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      _today != null ? '现场：Gemini ${gemMin}m · 娱乐 ${funMin}m' : '点击下方场景自动带入现场上下文并复制/唤起',
                      style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
                  ),
                  OutlinedButton.icon(
                    key: const ValueKey('gemini-snooze-30m'),
                    onPressed: () => unawaited(_snoozeAuto()),
                    icon: const Icon(AppIcons.time, size: 15),
                    label: const Text('延后自动 30m'),
                  ),
                ],
              ),
            ],
          ),
        ),
        ListPageRow(
          key: const ValueKey('gemini-scene-icebreak'),
          icon: AppIcons.play,
          iconColor: theme.colorScheme.primary,
          title: '破冰规划',
          subtitle: '白天停滞/碎跳时，2 分钟定死今天唯一第一步与允许 App',
          subtitleMaxLines: 2,
          chevron: true,
          onTap: () => unawaited(_triggerScene(_GeminiBridgeScene.icebreak)),
        ),
        ListPageRow(
          key: const ValueKey('gemini-scene-lock-bounce'),
          icon: AppIcons.warning,
          iconColor: colors.warning,
          title: '撞锁卡点诊断',
          subtitle: '短时连续撞锁被拦时，30 秒拆解卡点（急冷却 10m）',
          subtitleMaxLines: 2,
          chevron: true,
          onTap: () => unawaited(_triggerScene(_GeminiBridgeScene.lockBounce)),
        ),
        ListPageRow(
          key: const ValueKey('gemini-scene-evening-cards'),
          icon: AppIcons.skill,
          iconColor: colors.success,
          title: '晚间笔记变题库',
          subtitle: '收局后或 21:30+，把今日思考压成明天题库卡与因果卡',
          subtitleMaxLines: 2,
          chevron: true,
          onTap: () => unawaited(_triggerScene(_GeminiBridgeScene.eveningCards)),
        ),
        ListPageRow(
          key: const ValueKey('gemini-scene-boss-audit'),
          icon: AppIcons.report,
          iconColor: colors.danger,
          title: '主管全局审视',
          subtitle: '冷血老板视角先查 7 日全局账本，再提 3 个直击逃避的尖锐追问',
          subtitleMaxLines: 2,
          chevron: true,
          onTap: () => unawaited(_triggerScene(_GeminiBridgeScene.bossAudit)),
        ),
      ],
    );
  }
}
