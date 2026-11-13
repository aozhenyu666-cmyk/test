// 第八轮 J9：局官的一次语音闭环（念 → 听 → 识别 → 判 → 下一拍）。
//
// 这一层只做「顺序」，不做判定：判过关、记拍、算升级都在插件的 boutrun.js 里。
// 手机这边负责三件插件做不到的事——把句子念出来（U2 的嘴）、开麦把话收成文字（底座的
// VoiceBridge）、把两句之间的时间点告诉局官。
//
// 两条退回路径，都记在账上（插件 `POST /bouts` 的 fallback）：
//   念不出来 → 这一句改用文字显示，照样算「送到他面前」，应答时限照常起算；
//   听不出来（没识别器 / 没授权 / 没听见）→ 停在「等一句话」，由他自己打，或者按停。
//
// 时间戳一律是毫秒时间。局官的 askedAt 就是「念完那一刻」——念的这段不算他的应答时间。

import 'dart:async';

import 'package:flutter/foundation.dart';

/// 局官这一拍要念的话。
class BoutAsk {
  const BoutAsk({required this.say, this.stepId = '', this.limitSec = 0});

  final String say;
  final String stepId;
  final int limitSec;
}

/// 他答完一句之后，局官怎么接。
class BoutAnswer {
  const BoutAnswer({
    required this.accepted,
    this.say = '',
    this.nextAskAt = 0,
    this.closed = false,
  });

  /// false 表示这一条没算应答（超字数 / 混乱），这一拍没往前走。
  final bool accepted;

  /// 局官接下来说的话。空表示这一句先不说（周期步在等「每 N 分钟」）。
  final String say;

  /// 周期步下一次问的时刻（毫秒）。0 表示不需要等。
  final int nextAskAt;

  /// 这一局结束了（收局或升级）。
  final bool closed;
}

/// 局官那一侧的四件事（插件 `POST /bouts`）。抽出来是为了这条闭环能脱开插件单独测。
abstract class BoutVoicePort {
  /// 这一拍该念哪一句。null 表示没有在跑的局或已经收了。拿到它就说明局官开始冻计时。
  Future<BoutAsk?> ask();

  /// 念完了：应答时限从 [at]（毫秒）开始算。
  Future<void> spoken(int at);

  /// 把识别出来的话交给局官判。
  Future<BoutAnswer?> answer(String text);

  /// 声音 / 识别用不了，退回原因记账。
  Future<void> fallback(String reason);
}

enum BoutVoicePhase {
  idle,

  /// 问局官这一拍是什么。
  asking,

  /// 局官在念。
  speaking,

  /// 麦克风开着。
  listening,

  /// 在判这一句。
  judging,

  /// 没听见：等他自己打一句。
  waitingText,

  /// 周期步在等「每 N 分钟」。
  waitingAsk,

  done,
}

/// 一条语音局的闭环。一个循环一次，收局或按停即结束。
class BoutVoiceLoop extends ChangeNotifier {
  BoutVoiceLoop({
    required this.port,
    required this.say,
    required this.listen,
    this.now = _defaultNow,
    this.wait = _defaultWait,
  });

  final BoutVoicePort port;

  /// 让局官念一句，等它念完。null 表示念了（或被别的嗓子念了），否则是念不成的原因。
  final Future<String?> Function(String text) say;

  /// 开麦收一句话。null 或空表示没听见 / 没有识别器 / 没授权。
  final Future<String?> Function() listen;

  final DateTime Function() now;

  /// 周期步的等待。抽成字段是为了测试不用真等十分钟。
  final Future<void> Function(Duration) wait;

  static DateTime _defaultNow() => DateTime.now();
  static Future<void> _defaultWait(Duration d) => Future<void>.delayed(d);

  BoutVoicePhase _phase = BoutVoicePhase.idle;
  BoutVoicePhase get phase => _phase;

  /// 局官这一拍说的话（念的和屏幕上显示的是同一句）。
  String _ask = '';
  String get ask => _ask;

  /// 这一句识别出来的话。
  String _heard = '';
  String get heard => _heard;

  /// 最近一次退回的原因（'voice-out' / 'voice-in'）。界面照实说，不猜。
  String _fallback = '';
  String get fallback => _fallback;

  bool _running = false;
  bool get running => _running;

  /// 页面拆掉之后还在飞的请求会走回来；这时不再 notify（否则 debug 下直接断言）。
  bool _gone = false;

  @override
  void dispose() {
    _running = false;
    _gone = true;
    super.dispose();
  }

  /// 开始这一局。没有在跑的局就停在 done。
  Future<void> start() async {
    if (_running) return;
    _running = true;
    _fallback = '';
    _set(BoutVoicePhase.asking);
    final ask = await port.ask();
    if (!_running) return;
    if (ask == null) {
      _done();
      return;
    }
    await _deliver(ask.say);
  }

  /// 没听见时他自己打的一句。只在等文字时有意义。
  Future<void> submit(String text) async {
    final said = text.trim();
    if (!_running || _phase != BoutVoicePhase.waitingText || said.isEmpty) return;
    _heard = said;
    await _afterAnswer(await port.answer(said));
  }

  /// 按停。已经发出去的请求照旧落地，但不再往下走。
  void stop() {
    if (!_running) return;
    _running = false;
    _set(BoutVoicePhase.idle);
  }

  /// 念这一句，然后听。
  Future<void> _deliver(String text) async {
    _ask = text;
    _heard = '';
    if (!_running) return;
    _set(BoutVoicePhase.speaking);
    String? why;
    try {
      why = await say(text);
    } on Object {
      why = 'voice-out';
    }
    if (!_running) return;
    if (why != null) {
      _fallback = why;
      // 念不出来：这一句已经显示在屏幕上了，仍然算送到他面前。记账，但不停这一局。
      await port.fallback(why);
      if (!_running) return;
    }
    await port.spoken(now().millisecondsSinceEpoch);
    if (!_running) return;
    _set(BoutVoicePhase.listening);
    String? heard;
    try {
      heard = await listen();
    } on Object {
      heard = null;
    }
    if (!_running) return;
    _heard = (heard ?? '').trim();
    if (_heard.isEmpty) {
      // 识别不可用和「他没说话」在这一层分不开，都停在等一句话：
      // 一直等到超时会把这一拍判成没接，而他其实可能是没开口——让他自己决定打不打。
      _fallback = _fallback.isEmpty ? 'voice-in' : _fallback;
      await port.fallback('voice-in');
      if (!_running) return;
      _set(BoutVoicePhase.waitingText);
      return;
    }
    _set(BoutVoicePhase.judging);
    await _afterAnswer(await port.answer(_heard));
  }

  /// 答完一句之后怎么接：接着念、等「每 N 分钟」、还是收。
  Future<void> _afterAnswer(BoutAnswer? answer) async {
    if (!_running) return;
    if (answer == null || answer.closed) {
      _done();
      return;
    }
    if (answer.say.isNotEmpty) {
      await _deliver(answer.say);
      return;
    }
    if (answer.nextAskAt > 0) {
      _set(BoutVoicePhase.waitingAsk);
      final waitMs = answer.nextAskAt - now().millisecondsSinceEpoch;
      if (waitMs > 0) await wait(Duration(milliseconds: waitMs));
      if (!_running) return;
      _set(BoutVoicePhase.asking);
      final next = await port.ask();
      if (!_running) return;
      if (next == null) {
        _done();
        return;
      }
      await _deliver(next.say);
      return;
    }
    _done();
  }

  void _done() {
    _running = false;
    _set(BoutVoicePhase.done);
  }

  void _set(BoutVoicePhase phase) {
    if (_phase == phase || _gone) return;
    _phase = phase;
    notifyListeners();
  }
}
