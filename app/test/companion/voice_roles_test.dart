// 第八轮 U2：外接语音这一档「一张嘴、三个角色」的规矩，和搬过来的解析函数。
//
// 不出声、不发请求：CompanionRoles 里 external / settingsFor 只看设置，不碰播放器。
// 真正播放（系统 TTS / 取回来的音频）只能上真机验证。

import 'package:dsh_app/companion/companion_voice.dart';
import 'package:dsh_app/companion/companion_voice_source.dart';
import 'package:dsh_app/companion/voice_roles.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Composes a [CompanionRoles] without letting it speak: the client and the
/// folder are only reached by the playback queue, which no case here triggers.
CompanionSpeaker _quietSpeaker() => CompanionSpeaker(
  CompanionVoice(const MethodChannel('test/companion_voice')),
  () => throw StateError('这些用例不该发请求'),
  () async => throw StateError('这些用例不该写文件'),
);

/// A preset service, filled in and turned on.
const _preset = CompanionVoiceSettings(
  on: true,
  source: CompanionVoiceSource.preset,
  preset: 'mimo',
  presetKeys: {'mimo': 'key'},
  presetVoices: {'mimo': '冰糖'},
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  SharedPreferences.setMockInitialValues({});

  group('这一句会不会走外接服务', () {
    bool external(CompanionVoiceSettings? settings) => CompanionRoles(_quietSpeaker(), settings, const {}).external;

    test('还没读出设置：手机自带', () => expect(external(null), isFalse));
    test('开关关着：手机自带', () => expect(external(_preset.copyWith(on: false)), isFalse));
    test('选了手机自带：手机自带', () => expect(external(const CompanionVoiceSettings(on: true)), isFalse));
    test('外接服务没填密钥：手机自带', () {
      expect(external(_preset.copyWith(presetKeys: const {})), isFalse);
    });
    test('填好并打开了：外接服务', () => expect(external(_preset), isTrue));
  });

  group('每个角色挑自己的音色', () {
    test('不走外接服务时，给的是手机自带那一份', () {
      final roles = CompanionRoles(_quietSpeaker(), _preset.copyWith(on: false), const {VoiceRole.official: '茉莉'});
      expect(roles.settingsFor(VoiceRole.official).source, CompanionVoiceSource.system);
    });

    test('预设服务：各说各的，别处那份设置不动', () {
      final roles = CompanionRoles(_quietSpeaker(), _preset, const {
        VoiceRole.official: '茉莉',
        VoiceRole.strategist: '苏打',
        VoiceRole.companion: '白桦',
      });
      expect(roles.settingsFor(VoiceRole.official).presetVoice, '茉莉');
      expect(roles.settingsFor(VoiceRole.strategist).presetVoice, '苏打');
      expect(roles.settingsFor(VoiceRole.companion).presetVoice, '白桦');
      expect(_preset.presetVoice, '冰糖');
    });

    test('没选过音色：跟随服务默认', () {
      final roles = CompanionRoles(_quietSpeaker(), _preset, const {});
      expect(roles.settingsFor(VoiceRole.official).presetVoice, '冰糖');
    });

    test('OpenAI 兼容服务只有一档声音，按角色换', () {
      const openai = CompanionVoiceSettings(on: true, source: CompanionVoiceSource.openai, openaiVoice: 'alloy');
      final roles = CompanionRoles(_quietSpeaker(), openai, const {VoiceRole.companion: 'coral'});
      expect(roles.settingsFor(VoiceRole.official).openaiVoice, 'alloy');
      expect(roles.settingsFor(VoiceRole.companion).openaiVoice, 'coral');
    });
  });

  group('三个角色的音色存在本地', () {
    test('写进去的，下一次启动还读得出来', () async {
      SharedPreferences.setMockInitialValues({});
      final first = ProviderContainer();
      addTearDown(first.dispose);
      await first.read(voiceRoleProvider.notifier).set(VoiceRole.companion, '茉莉');
      expect(first.read(voiceRoleProvider)[VoiceRole.companion], '茉莉');

      final second = ProviderContainer();
      addTearDown(second.dispose);
      second.read(voiceRoleProvider);
      await pumpEventQueue();
      expect(second.read(voiceRoleProvider)[VoiceRole.companion], '茉莉');
    });

    test('清空一个角色就是跟随默认', () async {
      SharedPreferences.setMockInitialValues({});
      final container = ProviderContainer();
      addTearDown(container.dispose);
      final roles = container.read(voiceRoleProvider.notifier);
      await roles.set(VoiceRole.official, '苏打');
      await roles.set(VoiceRole.official, '');
      expect(container.read(voiceRoleProvider).containsKey(VoiceRole.official), isFalse);
    });
  });

  group('外接服务回来的字节', () {
    test('按头几个字认格式', () {
      Uint8List bytes(List<int> head) => Uint8List.fromList(head);
      expect(companionAudioKind(bytes('RIFF0000WAVE'.codeUnits)), 'wav');
      expect(companionAudioKind(bytes('ID3'.codeUnits)), 'mp3');
      expect(companionAudioKind(bytes([0xFF, 0xFB, 0x90])), 'mp3');
      expect(companionAudioKind(bytes('OggS'.codeUnits)), 'ogg');
      expect(companionAudioKind(bytes('fLaC'.codeUnits)), 'flac');
      expect(companionAudioKind(bytes([0, 0, 0, 0, ...'ftyp'.codeUnits])), 'm4a');
      // 报错页面当成音频回来（JSON / HTML），认不出就是 bin。
      expect(companionAudioKind(bytes('{'.codeUnits)), 'bin');
    });

    test('不是 16 位 PCM WAV 就没有口型数据', () {
      expect(companionLoudness(Uint8List.fromList('OggS'.codeUnits)), isNull);
    });
  });

  group('一句话念到哪里', () {
    test('半句先不念，等下半个句号', () {
      expect(companionSpeakable('第一句。第二', 0, final_: false), ('第一句。', 4));
    });

    test('说完了就全念', () {
      expect(companionSpeakable('第一句。第二', 0, final_: true), ('第一句。第二', 6));
    });
  });
}
