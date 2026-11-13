import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// 把 `dsh/proactive/agent` 这条事件流挂成「有人监听、但永远没有事件」。
///
/// 引擎侧的内核通过它下发 agent 命令（`ProactiveAgentTransport.start`）。测试里没有原生端，
/// `receiveBroadcastStream().listen()` 会抛 MissingPluginException——transport 自己的 onError
/// 接得住，但 Flutter 会把它记成未捕获的异步错误，凡渲染到 `watchProactiveTransport` 的
/// 用例就整个判失败（performance_ui_test 两条就是这么倒的）。
///
/// 和 [QuietPerformanceChannel] 同一个思路：没有原生端就该安静，而不是报错。
/// 只在被渲染的测试里生效，需要真事件的用例自己覆盖这条 channel。
void installQuietAgentChannel() {
  TestWidgetsFlutterBinding.ensureInitialized();
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockStreamHandler(
    const EventChannel('dsh/proactive/agent'),
    MockStreamHandler.inline(onListen: (_, __) {}, onCancel: (_) {}),
  );
}
