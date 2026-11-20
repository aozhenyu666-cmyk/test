import 'package:flutter/widgets.dart';

/// 对话框 / 底部弹层里用的输入框控制器：`await showDialog(...)` 一返回就 dispose 太早——
/// 关闭动画还在用它（debug 下报「used after being disposed」）。等关闭动画走完再收。
void disposeAfterRoute(ChangeNotifier c, {Duration delay = const Duration(milliseconds: 600)}) {
  Future<void>.delayed(delay, c.dispose);
}
