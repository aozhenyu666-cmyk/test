// 三个说话者共用的一张嘴（第八轮 U2）。
//
// 局官、大鱼、小鱼是三个角色，不是三个引擎：服务、密钥、风格共用一份设置，
// 只有音色分开存。所以这里只做两件事——按角色挑音色、把没配置的情况退回手机自带语音。
//
// 没配置（关了开关、没填密钥、或选了「手机自带」）时不报错也不静默：
// 直接把 source 换成 system 交给同一套播放器，走的就是 B5 那条系统 TTS 的路。
// 配置了外接服务才按句取音频排队播；取不到时 companion_voice.dart 自己会退回系统语音。

import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'companion_voice.dart';
import 'companion_voice_source.dart';

/// 谁在说话。三个角色，三把嗓子。
enum VoiceRole { official, strategist, companion }

const _rolesKey = 'companion_voice_roles';

/// 每个角色选的音色（音色 id 是服务方那边的字符串）。没选过就是空，随预设的第一个音色。
class VoiceRoleController extends Notifier<Map<VoiceRole, String>> {
  @override
  Map<VoiceRole, String> build() {
    unawaited(_load());
    return const {};
  }

  Future<void> _load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_rolesKey);
      if (raw == null) return;
      final map = jsonDecode(raw);
      if (map is! Map) return;
      final next = <VoiceRole, String>{};
      for (final role in VoiceRole.values) {
        final voice = map[role.name];
        if (voice is String && voice.isNotEmpty) next[role] = voice;
      }
      if (ref.mounted && next.isNotEmpty) state = next;
    } on Object {
      // 读不出来就用默认音色，不影响别的设置。
    }
  }

  Future<void> set(VoiceRole role, String voice) async {
    final next = {...state};
    if (voice.isEmpty) {
      next.remove(role);
    } else {
      next[role] = voice;
    }
    state = next;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_rolesKey, jsonEncode({
        for (final entry in next.entries) entry.key.name: entry.value,
      }));
    } on Object {
      // 这次运行里照样生效。
    }
  }
}

final voiceRoleProvider = NotifierProvider<VoiceRoleController, Map<VoiceRole, String>>(VoiceRoleController.new);

/// 一张嘴，三个角色。调用方只管说要谁说话，音色和兜底都在这里。
class CompanionRoles {
  CompanionRoles(this._speaker, this._settings, this._voices);

  final CompanionSpeaker _speaker;
  final CompanionVoiceSettings? _settings;
  final Map<VoiceRole, String> _voices;

  /// [say] 的序号：同一句由同一个角色说两次，也要是两条不同的进度事件。
  int _said = 0;

  /// 这一句会不会走外接服务。false 就是手机自带语音（B5 的兜底路）。
  bool get external {
    final settings = _settings;
    return settings != null &&
        settings.on &&
        settings.configured &&
        settings.source != CompanionVoiceSource.system;
  }

  /// [role] 该用的那份设置。
  CompanionVoiceSettings settingsFor(VoiceRole role) {
    final settings = _settings;
    // 没配置、关了、或选了手机自带：给一份 source=system 的设置，播放器自己认得这条路，
    // 不假装配好了，也不报错。
    if (settings == null || !external) return const CompanionVoiceSettings();
    final voice = _voices[role] ?? '';
    // 没选过音色：跟随服务默认，不动别的字段。
    if (voice.isEmpty) return settings;
    return switch (settings.source) {
      // 预设服务的音色是预设里的 id；OpenAI 兼容服务只有 openaiVoice 一个位置。
      CompanionVoiceSource.preset => settings.copyWith(presetVoices: {...settings.presetVoices, settings.preset: voice}),
      CompanionVoiceSource.openai => settings.copyWith(openaiVoice: voice),
      _ => settings,
    };
  }

  /// 让 [role] 说一句。[style] 是这一句的说法（局官的硬、小鱼的轻），不给就用设置里那一档。
  /// [flush] 先掐掉正在说的。
  void speak(VoiceRole role, String text, {String? style, String? id, bool flush = false}) {
    final said = text.trim();
    if (said.isEmpty) return;
    var settings = settingsFor(role);
    // 风格只有预设服务会用；一句一说的差别不改音色，所以照旧用同一个说话器。
    if (style != null && style.trim().isNotEmpty) settings = settings.copyWith(presetStyle: style.trim());
    _speaker.settings = settings;
    // 角色进 id：同一条文字由不同角色说出来时，进度事件不会互相顶掉。
    _speaker.speak(id ?? '${role.name}-${said.hashCode}', said, flush: flush);
  }

  void stop() => _speaker.stop();

  /// 让 [role] 说一句，**等它念完**（J9：念完才开始计应答时限）。
  /// null 表示念完了；否则是没念成的原因（'voice-out'）。
  ///
  /// 为什么不直接 await [CompanionSpeaker.speak]：那个只等到「开始播」。
  /// 完成事件和按字数估的时间谁先到算谁，见 [awaitCompanionSpoken]。
  Future<String?> say(VoiceRole role, String text, {String? style}) async {
    final said = text.trim();
    if (said.isEmpty) return null;
    final id = '${role.name}-${++_said}';
    final waiting = awaitCompanionSpoken(events, id, said);
    speak(role, said, style: style, id: id);
    return waiting;
  }

  Stream<CompanionVoiceEvent> get events => _speaker.events;

  /// 设置页试听：按 [role] 的音色念一句。null 表示真念了，否则是不能念的原因。
  Future<String?> preview(VoiceRole role, String text) => _speaker.preview(settingsFor(role), text);
}

/// 等 [id] 这一句念完。null＝念完了（或按字数估的那段时间到了）；'voice-out'＝报错了。
///
/// 为什么要估：手机自带语音（系统 TTS）不一定会报完成事件。真等一条可能永远不来的事件，
/// 每一拍就得干等整整一个超时；而「念完才开始计应答时限」又必须有个时刻。
/// 完成事件先到就按它，估的时间先到就按估的——两者都只是起点，判定还在局官那边。
///
/// [estimate] 不给就按字数估：中文一眼四五个字，220ms/字，长句封顶 120 字（约 26 秒）。
Future<String?> awaitCompanionSpoken(
  Stream<CompanionVoiceEvent> events,
  String id,
  String text, {
  Duration? estimate,
}) async {
  final chars = text.length > 120 ? 120 : text.length;
  final guess = estimate ?? Duration(milliseconds: 120 + 220 * chars);
  final done = Completer<String?>();
  final sub = events.listen((event) {
    if (event.id != id || done.isCompleted) return;
    final type = event.type;
    if (type == CompanionVoiceEventType.done || type == CompanionVoiceEventType.stopped) {
      done.complete(null);
    } else if (type == CompanionVoiceEventType.error) {
      done.complete('voice-out');
    }
    // 剩下三种不算念完：fallback（外接没取到、手机自己在念）、start、range（路过的进度）。
  });
  try {
    return await Future.any([done.future, Future<String?>.delayed(guess, () => null)]);
  } finally {
    await sub.cancel();
  }
}

final companionRolesProvider = Provider<CompanionRoles>((ref) {
  return CompanionRoles(
    ref.watch(companionSpeakerProvider),
    ref.watch(companionVoiceSettingsProvider),
    ref.watch(voiceRoleProvider),
  );
});
