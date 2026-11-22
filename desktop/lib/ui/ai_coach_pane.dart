import 'package:flutter/material.dart';

import '../models/guard_models.dart';
import '../services/pc_guard_controller.dart';
import '../theme/mist_theme.dart';

class AiCoachPane extends StatefulWidget {
  const AiCoachPane({super.key, required this.controller});

  final PcGuardController controller;

  @override
  State<AiCoachPane> createState() => _AiCoachPaneState();
}

class _AiCoachPaneState extends State<AiCoachPane> {
  late final TextEditingController _baseUrlCtrl;
  late final TextEditingController _modelCtrl;
  late final TextEditingController _apiKeyCtrl;
  late final TextEditingController _customPromptCtrl;
  final _chatInputCtrl = TextEditingController();

  String _preset = 'deepseek';
  bool _enabled = false;
  bool _autoJudgeDual = true;
  String _testStatus = '';
  bool _testing = false;

  @override
  void initState() {
    super.initState();
    final cfg = widget.controller.aiConfig;
    _preset = cfg.preset;
    _enabled = cfg.enabled;
    _autoJudgeDual = cfg.autoJudgeDual;
    _baseUrlCtrl = TextEditingController(text: cfg.baseUrl);
    _modelCtrl = TextEditingController(text: cfg.model);
    _apiKeyCtrl = TextEditingController(text: widget.controller.aiApiKey);
    _customPromptCtrl = TextEditingController(text: cfg.customPrompt);
  }

  @override
  void dispose() {
    _baseUrlCtrl.dispose();
    _modelCtrl.dispose();
    _apiKeyCtrl.dispose();
    _customPromptCtrl.dispose();
    _chatInputCtrl.dispose();
    super.dispose();
  }

  AiProviderConfig _currentDraft() => AiProviderConfig(
        preset: _preset,
        baseUrl: _baseUrlCtrl.text.trim(),
        model: _modelCtrl.text.trim(),
        enabled: _enabled,
        autoJudgeDual: _autoJudgeDual,
        customPrompt: _customPromptCtrl.text.trim(),
      );

  Future<void> _saveConfig() async {
    await widget.controller.saveAiConfig(
      _currentDraft(),
      apiKey: _apiKeyCtrl.text,
    );
    if (!mounted) return;
    setState(() => _testStatus = 'AI 配置与密钥已安全保存至本机');
  }

  Future<void> _testConfig() async {
    setState(() {
      _testing = true;
      _testStatus = '正在请求模型连通性测试...';
    });
    await widget.controller.saveAiConfig(
      _currentDraft(),
      apiKey: _apiKeyCtrl.text,
    );
    final res = await widget.controller.testAiConnection();
    if (!mounted) return;
    setState(() {
      _testing = false;
      _testStatus = res.detail;
    });
  }

  void _sendChat() {
    final text = _chatInputCtrl.text.trim();
    if (text.isEmpty) return;
    _chatInputCtrl.clear();
    widget.controller.sendCoachMessage(text);
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          flex: 5,
          child: ListView(
            children: [
              MistGlassSurface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('OpenAI 兼容 AI 裁判接口', style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 6,
                      children: [
                        for (final entry in AiProviderConfig.presets.entries)
                          ChoiceChip(
                            selected: _preset == entry.key,
                            label: Text(entry.value.label),
                            onSelected: (_) {
                              setState(() {
                                _preset = entry.key;
                                if (entry.value.baseUrl.isNotEmpty) {
                                  _baseUrlCtrl.text = entry.value.baseUrl;
                                  _modelCtrl.text = entry.value.model;
                                }
                              });
                            },
                          ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    TextField(
                      controller: _baseUrlCtrl,
                      decoration: const InputDecoration(
                        isDense: true,
                        labelText: 'Base URL (例如 https://api.deepseek.com/v1)',
                        border: OutlineInputBorder(),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _modelCtrl,
                            decoration: const InputDecoration(
                              isDense: true,
                              labelText: 'Model Name',
                              border: OutlineInputBorder(),
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: TextField(
                            controller: _apiKeyCtrl,
                            obscureText: true,
                            decoration: const InputDecoration(
                              isDense: true,
                              labelText: 'API Key（本地加密存储）',
                              border: OutlineInputBorder(),
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: _customPromptCtrl,
                      minLines: 1,
                      maxLines: 3,
                      decoration: const InputDecoration(
                        isDense: true,
                        labelText: '当前学习方向补充说明（如：正在备考省考行测与准备后端面试）',
                        border: OutlineInputBorder(),
                      ),
                    ),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      title: const Text('启用 AI 智能裁决与参谋'),
                      value: _enabled,
                      onChanged: (v) => setState(() => _enabled = v),
                    ),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      title: const Text('专注/超额时自动识别 B站/知乎/YouTube 是否为学习视频'),
                      value: _autoJudgeDual,
                      onChanged: (v) => setState(() => _autoJudgeDual = v),
                    ),
                    Row(
                      children: [
                        FilledButton(
                          onPressed: _saveConfig,
                          child: const Text('保存配置'),
                        ),
                        const SizedBox(width: 8),
                        OutlinedButton(
                          onPressed: _testing ? null : _testConfig,
                          child: Text(_testing ? '测试中...' : '测试连接'),
                        ),
                      ],
                    ),
                    if (_testStatus.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Text(_testStatus, style: const TextStyle(fontSize: 12.5)),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 12),
              MistGlassSurface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('近期 AI 窗口裁决记录', style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    if (c.ledger.aiJudgments.isEmpty)
                      const Text('暂无 AI 裁决记录；开启后访问灰区网站时将自动生成。', style: TextStyle(fontSize: 12.5))
                    else
                      for (final j in c.ledger.aiJudgments.take(10))
                        Container(
                          margin: const EdgeInsets.only(bottom: 8),
                          padding: const EdgeInsets.all(10),
                          decoration: BoxDecoration(
                            color: (j.verdict == 'allow_study'
                                    ? MistColors.emerald
                                    : MistColors.crimson)
                                .withValues(alpha: 0.08),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '${j.verdict == "allow_study" ? "放行学习(${j.leaseMinutes}m)" : "判定娱乐"} · ${j.app}',
                                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
                              ),
                              const SizedBox(height: 2),
                              Text(j.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12)),
                              Text('依据：${j.reason}', style: const TextStyle(fontSize: 12)),
                            ],
                          ),
                        ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          flex: 5,
          child: MistGlassSurface(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    const Icon(Icons.psychology_rounded, color: MistColors.cobalt),
                    const SizedBox(width: 8),
                    Text('AI 自律参谋', style: Theme.of(context).textTheme.titleMedium),
                    const Spacer(),
                    FilledButton.tonalIcon(
                      onPressed: c.coachBusy ? null : c.runDailyAiReview,
                      icon: const Icon(Icons.summarize_rounded, size: 17),
                      label: const Text('一键复盘今日电脑使用'),
                    ),
                  ],
                ),
                const Divider(height: 20),
                Expanded(
                  child: c.coachMessages.isEmpty
                      ? const Center(
                          child: Text(
                            '对话会自动附带今日真实软件时长、拦截次数与 Watchdog 记录，\n可直接点击右上角「一键复盘今日电脑使用」。',
                            textAlign: TextAlign.center,
                            style: TextStyle(fontSize: 13),
                          ),
                        )
                      : ListView.builder(
                          itemCount: c.coachMessages.length,
                          itemBuilder: (_, idx) {
                            final m = c.coachMessages[idx];
                            return Align(
                              alignment: m.isUser
                                  ? Alignment.centerRight
                                  : Alignment.centerLeft,
                              child: Container(
                                constraints: const BoxConstraints(maxWidth: 520),
                                margin: const EdgeInsets.only(bottom: 10),
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 14,
                                  vertical: 10,
                                ),
                                decoration: BoxDecoration(
                                  color: (m.isUser
                                          ? MistColors.cobalt
                                          : MistColors.whaleTeal)
                                      .withValues(alpha: 0.12),
                                  borderRadius: BorderRadius.circular(14),
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      m.speaker,
                                      style: const TextStyle(
                                        fontSize: 11.5,
                                        fontWeight: FontWeight.w700,
                                      ),
                                    ),
                                    const SizedBox(height: 4),
                                    SelectableText(m.text, style: const TextStyle(fontSize: 13)),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _chatInputCtrl,
                        onSubmitted: (_) => _sendChat(),
                        decoration: const InputDecoration(
                          isDense: true,
                          hintText: '结合今日账本向 AI 提问 / 拆解下一步任务...',
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    FilledButton.icon(
                      onPressed: c.coachBusy ? null : _sendChat,
                      icon: const Icon(Icons.send_rounded, size: 17),
                      label: Text(c.coachBusy ? '思考中...' : '发送'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
