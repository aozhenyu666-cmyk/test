// 第八轮 J9：一条语音局的闭环（念 → 听 → 判 → 下一拍）。
//
// 这里钉的是顺序和两条退回路径，不钉判定：判过关、记拍、升级都在插件的 boutrun.js，
// 那一层有自己的 node 测试（test/bouts_voice.test.mjs）。
//
// 三条不能错的顺序：
//   1. 先念、再叫 spoken（应答时限从念完那刻算），最后才开麦；
//   2. 念不出来也要叫 spoken——那一句已经显示在屏幕上，算送到他面前；
//   3. 没听见就停在「等一句话」，不替他判「没接」。

import 'dart:async';

import 'package:flutter_test/flutter_test.dart';

import 'package:dsh_app/companion/bout_voice.dart';
import 'package:dsh_app/companion/companion_voice.dart';
import 'package:dsh_app/companion/voice_roles.dart';

class FakePort implements BoutVoicePort {
  FakePort({this.asks = const [], this.replies = const []});

  final List<BoutAsk?> asks;
  final List<BoutAnswer?> replies;

  int askCount = 0;
  final List<int> spokenAt = [];
  final List<String> answered = [];
  final List<String> fallbacks = [];

  @override
  Future<BoutAsk?> ask() async {
    final i = askCount++;
    return i < asks.length ? asks[i] : null;
  }

  @override
  Future<void> spoken(int at) async => spokenAt.add(at);

  @override
  Future<BoutAnswer?> answer(String text) async {
    answered.add(text);
    final i = answered.length - 1;
    return i < replies.length ? replies[i] : null;
  }

  @override
  Future<void> fallback(String reason) async => fallbacks.add(reason);
}

const _now = 1700000000000;
DateTime _clock() => DateTime.fromMillisecondsSinceEpoch(_now);

BoutVoiceLoop makeLoop(
  FakePort port, {
  List<String?> speakReasons = const [],
  List<String?> heard = const [],
}) {
  final said = <String>[];
  var speakIndex = 0;
  var hearIndex = 0;
  final loop = BoutVoiceLoop(
    port: port,
    now: _clock,
    say: (text) async {
      said.add(text);
      final i = speakIndex++;
      return i < speakReasons.length ? speakReasons[i] : null;
    },
    listen: () async {
      final i = hearIndex++;
      return i < heard.length ? heard[i] : null;
    },
    // 周期步真的等会跑十分钟：测试里只记下等了多久。
    wait: (d) async => null,
  );
  return loop;
}

void main() {
  test('一拍走完：先念、再报念完、再听、再判；下一句由答复带回来，不再问一遍', () async {
    final port = FakePort(
      asks: [const BoutAsk(say: '现在到哪一步了？', stepId: '一拍', limitSec: 90)],
      replies: [
        const BoutAnswer(accepted: true, say: '做完这半页，还剩两段。'),
        const BoutAnswer(accepted: true, closed: true),
      ],
    );
    final loop = makeLoop(port, heard: ['写完第三段了', '还剩两段']);

    await loop.start();

    expect(loop.ask, '做完这半页，还剩两段。');
    expect(port.askCount, 1, reason: '第二句是 answer 带回来的，不该再 ask 一次');
    expect(port.answered, ['写完第三段了', '还剩两段']);
    expect(port.spokenAt, [_now, _now], reason: '每一拍念完都报一次，起点是同一刻（测试里时钟不走）');
    expect(loop.phase, BoutVoicePhase.done);
    expect(loop.running, isFalse);
  });

  test('收局就停：答复说 closed，不再问下一拍', () async {
    final port = FakePort(
      asks: [const BoutAsk(say: '最后一条？')],
      replies: [const BoutAnswer(accepted: true, closed: true)],
    );
    final loop = makeLoop(port, heard: ['交了']);
    await loop.start();
    expect(port.askCount, 1);
    expect(loop.phase, BoutVoicePhase.done);
  });

  test('念不出来：记一笔 voice-out，照样报念完（这一句已经在他眼前），然后开麦', () async {
    final port = FakePort(
      asks: [const BoutAsk(say: '现在到哪一步了？')],
      replies: [const BoutAnswer(accepted: true, closed: true)],
    );
    final loop = makeLoop(port, speakReasons: ['voice-out'], heard: ['写完了']);

    await loop.start();

    expect(port.fallbacks, ['voice-out']);
    expect(port.spokenAt, [_now], reason: '念不出来也算送到了：时限不能因此不计');
    expect(port.answered, ['写完了']);
    expect(loop.fallback, 'voice-out');
  });

  test('没听见：停在等一句话，不替他判「没接」', () async {
    final port = FakePort(asks: [const BoutAsk(say: '现在到哪一步了？')]);
    final loop = makeLoop(port, heard: [null]);

    await loop.start();

    expect(loop.phase, BoutVoicePhase.waitingText);
    expect(port.answered, isEmpty, reason: '没听见不是没接：这一段不交上去判');
    expect(port.fallbacks, ['voice-in'], reason: '退回打字要记账，面板能看见为什么');
    expect(loop.running, isTrue, reason: '还等他打一句，或者按停');
  });

  test('打一句接着走：submit 之后交上去、收局', () async {
    final port = FakePort(
      asks: [const BoutAsk(say: '现在到哪一步了？')],
      replies: [const BoutAnswer(accepted: true, closed: true)],
    );
    final loop = makeLoop(port, heard: [null]);
    await loop.start();
    expect(loop.phase, BoutVoicePhase.waitingText);

    await loop.submit('写完第三段了');

    expect(port.answered, ['写完第三段了']);
    expect(loop.heard, '写完第三段了');
    expect(loop.phase, BoutVoicePhase.done);
  });

  test('等文字的空白不交：submit 空串什么也不发生', () async {
    final port = FakePort(asks: [const BoutAsk(say: '现在到哪一步了？')]);
    final loop = makeLoop(port, heard: [null]);
    await loop.start();
    await loop.submit('   ');
    expect(port.answered, isEmpty);
    expect(loop.phase, BoutVoicePhase.waitingText);
  });

  test('周期步：答复不带下一句、只带到点时刻，就等到点再问同一句', () async {
    final port = FakePort(
      asks: [
        const BoutAsk(say: '做到哪了？'),
        const BoutAsk(say: '做到哪了？'),
      ],
      replies: [
        // nextAskAt 在「现在」之后 10 分钟。
        const BoutAnswer(accepted: true, nextAskAt: _now + 600000),
        const BoutAnswer(accepted: true, closed: true),
      ],
    );
    final loop = makeLoop(port, heard: ['在写', '写完了']);

    await loop.start();

    expect(port.askCount, 2, reason: '到点才重新问，问的是同一句');
    expect(port.answered, ['在写', '写完了']);
    expect(loop.phase, BoutVoicePhase.done);
  });

  test('没有在跑的局：停在 done，不念也不开麦', () async {
    final port = FakePort();
    final loop = makeLoop(port);
    await loop.start();
    expect(loop.phase, BoutVoicePhase.done);
    expect(loop.ask, isEmpty);
    expect(port.spokenAt, isEmpty);
    expect(port.answered, isEmpty);
  });

  test('按停：正在听的时候按停，回来的话不再交上去判', () async {
    final port = FakePort(
      asks: [const BoutAsk(say: '现在到哪一步了？')],
      replies: [const BoutAnswer(accepted: true, closed: true)],
    );
    final gate = Completer<String?>();
    final loop = BoutVoiceLoop(
      port: port,
      now: _clock,
      say: (_) async => null,
      listen: () => gate.future,
    );
    final running = loop.start();
    await Future<void>.delayed(Duration.zero);
    loop.stop();
    gate.complete('这一句不该被交上去');
    await running;

    expect(port.answered, isEmpty);
    expect(loop.phase, BoutVoicePhase.idle);
    expect(loop.running, isFalse);
  });

  // ---- 等一句「念完」 --------------------------------------------------------------------------

  group('念完的判定', () {
    late StreamController<CompanionVoiceEvent> events;

    setUp(() => events = StreamController<CompanionVoiceEvent>.broadcast());
    tearDown(() => events.close());

    test('报完成就算念完', () async {
      final waiting = awaitCompanionSpoken(events.stream, 'a', '这一句', estimate: const Duration(seconds: 5));
      events.add(const CompanionVoiceEvent(CompanionVoiceEventType.done, 'a'));
      expect(await waiting, isNull);
    });

    test('报错就是没念出来', () async {
      final waiting = awaitCompanionSpoken(events.stream, 'a', '这一句', estimate: const Duration(seconds: 5));
      events.add(const CompanionVoiceEvent(CompanionVoiceEventType.error, 'a'));
      expect(await waiting, 'voice-out');
    });

    test('别的句子的事件不算（三把嗓子各报各的）', () async {
      final waiting = awaitCompanionSpoken(events.stream, 'a', '这一句', estimate: const Duration(milliseconds: 40));
      events.add(const CompanionVoiceEvent(CompanionVoiceEventType.done, 'b'));
      // 只等到按字数估的那一下，而不是被 b 的完成骗过去。
      expect(await waiting, isNull);
    });

    test('外接没取到、手机自己在念：不算念完，接着等完成', () async {
      final waiting = awaitCompanionSpoken(events.stream, 'a', '这一句', estimate: const Duration(seconds: 5));
      events.add(const CompanionVoiceEvent(CompanionVoiceEventType.fallback, 'a'));
      events.add(const CompanionVoiceEvent(CompanionVoiceEventType.done, 'a'));
      expect(await waiting, isNull);
    });

    test('没人报完成也不会永远挂着：按字数估的时间到了就起算', () async {
      final started = DateTime.now();
      final reason = await awaitCompanionSpoken(
        events.stream,
        'a',
        '这一句',
        estimate: const Duration(milliseconds: 60),
      );
      expect(reason, isNull);
      expect(DateTime.now().difference(started), lessThan(const Duration(seconds: 3)));
    });
  });
}
