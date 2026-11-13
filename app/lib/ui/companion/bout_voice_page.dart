// 第八轮 J9 的界面：一局语音，一个循环（念 → 听 → 判 → 下一拍）。
//
// 这一页只管看和按：状态机在 lib/companion/bout_voice.dart，判定在插件的 boutrun.js。
// 局官的嘴走 U2 的角色音色（局官那一把），耳朵走底座的 VoiceBridge（系统识别器）。
// 识别器不可用、没授权、或没听见，都退回打字——不拿「他没说话」去判他没接。

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../companion/bout_voice.dart';
import '../../companion/voice_roles.dart';
import '../../l10n/l10n.dart';
import '../../proactive/proactive_state.dart';
import '../chat/voice/voice_bridge.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';

class BoutVoicePage extends ConsumerStatefulWidget {
  const BoutVoicePage({super.key});

  @override
  ConsumerState<BoutVoicePage> createState() => _BoutVoicePageState();
}

class _BoutVoicePageState extends ConsumerState<BoutVoicePage> {
  late final BoutVoiceLoop _loop = BoutVoiceLoop(port: ProactiveBouts(), say: _say, listen: _listen);
  final _text = TextEditingController();
  String _problem = '';

  @override
  void dispose() {
    // 这里不再 stop()：那会 notify 到一个正在拆的 ListenableBuilder 上。dispose 自己会停。
    _loop.dispose();
    _text.dispose();
    super.dispose();
  }

  /// 局官的嘴：U2 的那一张，等它念完才返回。
  Future<String?> _say(String text) => ref.read(companionRolesProvider).say(VoiceRole.official, text);

  /// 局官的耳朵：系统识别器。收不到就是 null，交给打字那条路。
  Future<String?> _listen() async {
    final bridge = MethodChannelVoiceBridge();
    final caps = await bridge.capabilities();
    if (!caps.recognizer) return null;
    if (await bridge.requestPermission() != VoicePermission.granted) return null;
    final heard = Completer<String?>();
    var partial = '';
    final sub = bridge.events.listen((event) {
      if (heard.isCompleted) return;
      if (event is VoicePartial) {
        partial = event.text;
      } else if (event is VoiceFinal) {
        heard.complete(event.text.trim().isEmpty ? partial : event.text);
      } else if (event is VoiceError) {
        heard.complete(null);
      } else if (event is VoiceEnd) {
        // 有时只报中间结果、没有 final：拿最后听到的那一版（聊天那边也是这么取的）。
        heard.complete(partial.isEmpty ? null : partial);
      }
    });
    try {
      await bridge.startRecognizer(language: 'zh-CN');
      return await heard.future.timeout(const Duration(seconds: 20), onTimeout: () => null);
    } on Object {
      return null;
    } finally {
      await sub.cancel();
      try {
        await bridge.stop();
      } on Object {
        // 已经停了。
      }
    }
  }

  Future<void> _start() async {
    setState(() => _problem = '');
    await _loop.start();
    if (!mounted) return;
    // 一句都没问到：多半是没有在跑的局。照实说，不假装开了。
    if (_loop.phase == BoutVoicePhase.done && _loop.ask.isEmpty) {
      setState(() => _problem = context.l10n.boutVoiceNoBout);
    }
  }

  Future<void> _submit() async {
    final said = _text.text.trim();
    if (said.isEmpty) return;
    _text.clear();
    await _loop.submit(said);
  }

  String _phaseText(L10n l10n, BoutVoicePhase phase) => switch (phase) {
    BoutVoicePhase.speaking => l10n.boutVoiceSpeaking,
    BoutVoicePhase.listening => l10n.boutVoiceListening,
    BoutVoicePhase.judging => l10n.boutVoiceJudging,
    BoutVoicePhase.waitingAsk => l10n.boutVoiceWaitAsk,
    BoutVoicePhase.waitingText => l10n.boutVoiceWaitText,
    BoutVoicePhase.done => l10n.boutVoiceDone,
    _ => '',
  };

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    return AppPage.list(
      title: l10n.catalogBoutVoice,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          child: ListenableBuilder(
            listenable: _loop,
            builder: (context, _) {
              final phase = _loop.phase;
              final running = _loop.running;
              final waiting = phase == BoutVoicePhase.waitingText;
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(l10n.boutVoiceIntro, style: theme.textTheme.bodyMedium),
                  const SizedBox(height: 16),
                  if (_loop.ask.isNotEmpty) ...[
                    Text(l10n.boutVoiceSayLabel, style: theme.textTheme.labelMedium),
                    const SizedBox(height: 4),
                    Text(_loop.ask, style: theme.textTheme.titleMedium),
                    const SizedBox(height: 12),
                  ],
                  if (_loop.heard.isNotEmpty) ...[
                    Text(l10n.boutVoiceHeardLabel, style: theme.textTheme.labelMedium),
                    const SizedBox(height: 4),
                    Text(_loop.heard, style: theme.textTheme.bodyLarge),
                    const SizedBox(height: 12),
                  ],
                  if (_loop.fallback == 'voice-out') Text(l10n.boutVoiceFallbackOut, style: theme.textTheme.bodySmall),
                  if (_loop.fallback == 'voice-in') Text(l10n.boutVoiceFallbackIn, style: theme.textTheme.bodySmall),
                  if (_phaseText(l10n, phase).isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(_phaseText(l10n, phase), style: theme.textTheme.bodySmall),
                  ],
                  if (_problem.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(_problem, style: theme.textTheme.bodySmall),
                  ],
                  const SizedBox(height: 16),
                  if (waiting) ...[
                    TextField(
                      controller: _text,
                      autofocus: true,
                      decoration: InputDecoration(hintText: l10n.boutVoiceWaitText),
                      onSubmitted: (_) => _submit(),
                    ),
                    const SizedBox(height: 8),
                    FilledButton(onPressed: _submit, child: Text(l10n.boutVoiceSubmit)),
                    const SizedBox(height: 8),
                  ],
                  OutlinedButton.icon(
                    onPressed: running ? _loop.stop : _start,
                    icon: Icon(running ? AppIcons.stop : AppIcons.voice),
                    label: Text(running ? l10n.boutVoiceStop : l10n.boutVoiceStart),
                  ),
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}
