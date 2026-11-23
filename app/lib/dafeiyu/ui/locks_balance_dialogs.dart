part of 'locks_balance_page.dart';

class _SpendBalanceDialog extends StatefulWidget {
  const _SpendBalanceDialog();

  @override
  State<_SpendBalanceDialog> createState() => _SpendBalanceDialogState();
}

class _SpendBalanceDialogState extends State<_SpendBalanceDialog> {
  final _minCtrl = TextEditingController(text: '15');
  final _whyCtrl = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _minCtrl.dispose();
    _whyCtrl.dispose();
    super.dispose();
  }

  void _submit() {
    final mins = int.tryParse(_minCtrl.text.trim()) ?? 0;
    final why = _whyCtrl.text.trim();
    if (mins < 5 || mins > 60) {
      setState(() => _error = '每次花余额须在 5–60 分钟之间');
      return;
    }
    if (why.length < 4) {
      setState(() => _error = '请写一句理由（至少 4 个字）');
      return;
    }
    Navigator.of(context).pop((mins, why));
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    return AlertDialog(
      key: const ValueKey('locks-spend-dialog'),
      title: const Text('花娱乐余额放松'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            key: const ValueKey('locks-spend-minutes'),
            controller: _minCtrl,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly],
            decoration: const InputDecoration(labelText: '时长（5–60 分钟）'),
          ),
          const SizedBox(height: 10),
          TextField(
            key: const ValueKey('locks-spend-reason'),
            controller: _whyCtrl,
            autofocus: true,
            maxLength: 120,
            decoration: const InputDecoration(
              labelText: '理由（花在什么上、为什么现在）',
              hintText: '例如：刚做完一套行测，刷会 B 站歇 15 分钟',
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 6),
            Text(_error!, style: TextStyle(color: colors.danger, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('算了')),
        FilledButton(
          key: const ValueKey('locks-spend-submit'),
          onPressed: _submit,
          child: const Text('确认花费'),
        ),
      ],
    );
  }
}

class _SubmitDeliveryDialog extends StatefulWidget {
  const _SubmitDeliveryDialog({
    required this.title,
    required this.deliverable,
    this.initialText = '',
  });

  final String title;
  final String deliverable;
  final String initialText;

  @override
  State<_SubmitDeliveryDialog> createState() => _SubmitDeliveryDialogState();
}

class _SubmitDeliveryDialogState extends State<_SubmitDeliveryDialog> {
  late final TextEditingController _ctrl = TextEditingController(text: widget.initialText);
  String? _error;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  void _submit() {
    final text = _ctrl.text.trim();
    if (text.length < 12) {
      setState(() => _error = '交付说明至少 12 个字（当前 ${text.length} 字），请写清物理产出或结论');
      return;
    }
    Navigator.of(context).pop(text);
  }

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    return AlertDialog(
      key: const ValueKey('locks-delivery-dialog'),
      title: Text('提交交付 · ${widget.title}'),
      content: SizedBox(
        width: 380,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('约定交付：${widget.deliverable}', style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 10),
              TextField(
                key: const ValueKey('locks-delivery-input'),
                controller: _ctrl,
                autofocus: true,
                minLines: 3,
                maxLines: 6,
                decoration: const InputDecoration(
                  labelText: '真实交付内容 / 题量 / 文件增量（至少 12 字）',
                  border: OutlineInputBorder(),
                ),
              ),
              if (_error != null) ...[
                const SizedBox(height: 6),
                Text(_error!, style: TextStyle(color: colors.danger, fontSize: 12)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('取消')),
        FilledButton(
          key: const ValueKey('locks-delivery-submit'),
          onPressed: _submit,
          child: const Text('提交交付'),
        ),
      ],
    );
  }
}
