import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../ui/common/list_page_row.dart';
import '../../ui/common/segmented_pill.dart';
import '../../ui/common/status_chip.dart';
import '../../ui/shell/page_frame.dart';
import '../../ui/theme/app_icons.dart';
import '../../ui/theme/theme.dart';
import '../voice/companion_voice_source.dart';
import '../voice/voice_roles.dart';
import 'bout_voice_page.dart';

/// 姐妹与局官统一语音控制台：配置音源、API Key、并分别为鱼丸 / 鲸澜 / 局官分配独立音色与在线试听。
class VoiceSettingsPage extends ConsumerStatefulWidget {
  const VoiceSettingsPage({super.key});

  static Future<void> open(BuildContext context) => Navigator.of(context).push(
    MaterialPageRoute<void>(builder: (_) => const VoiceSettingsPage()),
  );

  @override
  ConsumerState<VoiceSettingsPage> createState() => _VoiceSettingsPageState();
}

class _VoiceSettingsPageState extends ConsumerState<VoiceSettingsPage> {
  final _keyCtrl = TextEditingController();
  final _baseCtrl = TextEditingController();
  final _modelCtrl = TextEditingController();
  final _styleCtrl = TextEditingController();
  final _customUrlCtrl = TextEditingController();
  final _customHeadersCtrl = TextEditingController();
  final _customBodyCtrl = TextEditingController();
  final _customFieldCtrl = TextEditingController();

  bool _hydrated = false;
  bool _saving = false;
  String? _statusMsg;

  @override
  void dispose() {
    _keyCtrl.dispose();
    _baseCtrl.dispose();
    _modelCtrl.dispose();
    _styleCtrl.dispose();
    _customUrlCtrl.dispose();
    _customHeadersCtrl.dispose();
    _customBodyCtrl.dispose();
    _customFieldCtrl.dispose();
    super.dispose();
  }

  void _syncControllers(CompanionVoiceSettings s) {
    if (_hydrated) return;
    _hydrated = true;
    _keyCtrl.text = switch (s.source) {
      CompanionVoiceSource.preset => s.presetKeys[s.presetInfo.id] ?? '',
      CompanionVoiceSource.openai => s.openaiKey,
      _ => '',
    };
    _baseCtrl.text = s.openaiBase;
    _modelCtrl.text = s.openaiModel;
    _styleCtrl.text = s.presetStyle;
    _customUrlCtrl.text = s.customUrl;
    _customHeadersCtrl.text = s.customHeaders;
    _customBodyCtrl.text = s.customBody;
    _customFieldCtrl.text = s.customField;
  }

  Future<void> _saveSettings(CompanionVoiceSettings current, {CompanionVoiceSource? nextSource, bool? nextOn}) async {
    setState(() {
      _saving = true;
      _statusMsg = null;
    });
    final source = nextSource ?? current.source;
    final nextKeys = Map<String, String>.from(current.presetKeys);
    if (source == CompanionVoiceSource.preset) {
      nextKeys[current.presetInfo.id] = _keyCtrl.text.trim();
    }
    final next = current.copyWith(
      on: nextOn ?? current.on,
      source: source,
      presetKeys: nextKeys,
      presetStyle: _styleCtrl.text.trim(),
      openaiBase: _baseCtrl.text.trim().isEmpty ? current.openaiBase : _baseCtrl.text.trim(),
      openaiKey: source == CompanionVoiceSource.openai ? _keyCtrl.text.trim() : current.openaiKey,
      openaiModel: _modelCtrl.text.trim().isEmpty ? current.openaiModel : _modelCtrl.text.trim(),
      customUrl: _customUrlCtrl.text.trim(),
      customHeaders: _customHeadersCtrl.text.trim(),
      customBody: _customBodyCtrl.text.trim().isEmpty ? current.customBody : _customBodyCtrl.text.trim(),
      customField: _customFieldCtrl.text.trim(),
    );
    await ref.read(companionVoiceSettingsProvider.notifier).update(next);
    if (!mounted) return;
    setState(() {
      _saving = false;
      _statusMsg = '语音配置已保存';
    });
  }

  Future<void> _previewRole(VoiceRole role, CompanionVoiceSettings settings) async {
    final sample = switch (role) {
      VoiceRole.companion => '喂，你刚才是不是又准备切出去摸鱼了？老实回去干活。',
      VoiceRole.strategist => '前提错了。先把这一步写完，我再评估下一阶段路径。',
      VoiceRole.official => '当前专注局已进入严格计时，请汇报这一步产出。',
    };
    setState(() => _statusMsg = '正在试听 ${role.name}…');
    final err = await ref.read(companionRolesProvider).preview(role, sample);
    if (!mounted) return;
    setState(() => _statusMsg = err == null ? '试听完成' : '试听失败：$err');
  }

  Future<void> _pickVoiceForRole(VoiceRole role, CompanionVoiceSettings settings, String currentVoice) async {
    final candidates = settings.source == CompanionVoiceSource.preset
        ? [for (final v in settings.presetInfo.voices) v.id]
        : const ['alloy', 'echo', 'fable', 'onyx', 'nova', 'shimmer'];
    final ctrl = TextEditingController(text: currentVoice);
    final chosen = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('${_roleTitle(role)}音色'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final id in candidates)
                    ChoiceChip(
                      label: Text(id),
                      selected: ctrl.text == id,
                      onSelected: (_) => Navigator.of(ctx).pop(id),
                    ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                controller: ctrl,
                decoration: const InputDecoration(
                  labelText: '或自定义音色 ID / clone URI',
                  isDense: true,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.of(ctx).pop(ctrl.text.trim()), child: const Text('应用')),
        ],
      ),
    );
    ctrl.dispose();
    if (chosen == null || chosen.isEmpty) return;
    await ref.read(voiceRoleProvider.notifier).set(role, chosen);
    if (mounted) setState(() => _statusMsg = '${_roleTitle(role)}音色已切至 $chosen');
  }

  String _roleTitle(VoiceRole role) => switch (role) {
    VoiceRole.companion => '鱼丸（妹妹）',
    VoiceRole.strategist => '鲸澜（姐姐）',
    VoiceRole.official => '局官（督战）',
  };

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    final settings = ref.watch(companionVoiceSettingsProvider) ?? const CompanionVoiceSettings();
    final roleMap = ref.watch(voiceRoleProvider);
    _syncControllers(settings);

    return AppPage.list(
      title: '语音与角色声线',
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
          child: SegmentedPill<CompanionVoiceSource>(
            segments: const [
              PillSegment(CompanionVoiceSource.system, '系统 TTS'),
              PillSegment(CompanionVoiceSource.preset, 'MiMo 预设'),
              PillSegment(CompanionVoiceSource.openai, 'OpenAI'),
              PillSegment(CompanionVoiceSource.custom, '自定义'),
            ],
            selected: settings.source,
            onChanged: (next) {
              _hydrated = false;
              unawaited(_saveSettings(settings, nextSource: next));
            },
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: ListPageCard(
            title: '总控开关与音源',
            children: [
              ListPageRow(
                icon: AppIcons.voice,
                iconColor: settings.on ? colors.success : colors.textTertiary,
                title: '姐妹与局官主动语音播报',
                subtitle: settings.on ? '已开启（按各自音色队列播放）' : '已静音，点击切换开启',
                badge: StatusChip(
                  tone: settings.on ? StatusTone.ok : StatusTone.idle,
                  label: settings.on ? '开启' : '关闭',
                ),
                onTap: () => unawaited(_saveSettings(settings, nextOn: !settings.on)),
              ),
              if (settings.source == CompanionVoiceSource.preset)
                ListPageRow(
                  icon: AppIcons.configure,
                  title: '预设服务',
                  subtitle: '${settings.presetInfo.name} · ${settings.presetInfo.site}',
                  badge: StatusChip(tone: StatusTone.idle, label: settings.presetInfo.id),
                  onTap: () {
                    final idx = companionVoicePresets.indexWhere((p) => p.id == settings.presetInfo.id);
                    final nextPreset = companionVoicePresets[(idx + 1) % companionVoicePresets.length];
                    final nextKeys = Map<String, String>.from(settings.presetKeys);
                    nextKeys[settings.presetInfo.id] = _keyCtrl.text.trim();
                    _hydrated = false;
                    unawaited(
                      ref.read(companionVoiceSettingsProvider.notifier).update(
                        settings.copyWith(preset: nextPreset.id, presetKeys: nextKeys, presetStyle: _styleCtrl.text.trim()),
                      ),
                    );
                  },
                ),
            ],
          ),
        ),
        if (settings.source != CompanionVoiceSource.system) ...[
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (settings.source == CompanionVoiceSource.openai || settings.source == CompanionVoiceSource.preset) ...[
                      TextField(
                        controller: _keyCtrl,
                        obscureText: true,
                        decoration: const InputDecoration(labelText: 'API Key（安全存储）', isDense: true),
                      ),
                      const SizedBox(height: 10),
                    ],
                    if (settings.source == CompanionVoiceSource.preset)
                      TextField(
                        controller: _styleCtrl,
                        decoration: const InputDecoration(labelText: '说话风格提示（可选）', isDense: true),
                      ),
                    if (settings.source == CompanionVoiceSource.openai) ...[
                      TextField(
                        controller: _baseCtrl,
                        decoration: const InputDecoration(labelText: 'Base URL', isDense: true),
                      ),
                      const SizedBox(height: 10),
                      TextField(
                        controller: _modelCtrl,
                        decoration: const InputDecoration(labelText: 'TTS Model', isDense: true),
                      ),
                    ],
                    if (settings.source == CompanionVoiceSource.custom) ...[
                      TextField(
                        controller: _customUrlCtrl,
                        decoration: const InputDecoration(labelText: 'HTTP URL', isDense: true),
                      ),
                      const SizedBox(height: 10),
                      TextField(
                        controller: _customHeadersCtrl,
                        maxLines: 2,
                        decoration: const InputDecoration(labelText: 'Headers（每行 Name: value）', isDense: true),
                      ),
                      const SizedBox(height: 10),
                      TextField(
                        controller: _customBodyCtrl,
                        maxLines: 2,
                        decoration: const InputDecoration(labelText: 'POST JSON 模板（含 {text}）', isDense: true),
                      ),
                      const SizedBox(height: 10),
                      TextField(
                        controller: _customFieldCtrl,
                        decoration: const InputDecoration(labelText: '音频响应字段路径（留空表示二进制流）', isDense: true),
                      ),
                    ],
                    const SizedBox(height: 12),
                    FilledButton(
                      onPressed: _saving ? null : () => unawaited(_saveSettings(settings)),
                      child: Text(_saving ? '保存中…' : '保存音源参数'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
        const SizedBox(height: 12),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: ListPageCard(
            title: '三角色独立声线（小鱼 / 大鱼 / 局官）',
            children: [
              for (final role in VoiceRole.values)
                Builder(builder: (context) {
                  final eff = settings.source == CompanionVoiceSource.preset
                      ? settings.presetInfo.voices.first.id
                      : settings.openaiVoice;
                  final picked = roleMap[role] ?? '';
                  final validPreset = settings.source != CompanionVoiceSource.preset ||
                      settings.presetInfo.voices.any((v) => v.id == picked) ||
                      (settings.presetInfo.clones && picked.startsWith('data:'));
                  final displayVoice = (picked.isNotEmpty && validPreset) ? picked : eff;
                  final canPickVoice = settings.source == CompanionVoiceSource.preset ||
                      settings.source == CompanionVoiceSource.openai;
                  return ListPageRow(
                    icon: AppIcons.voice,
                    iconColor: role == VoiceRole.companion
                        ? colors.warning
                        : (role == VoiceRole.strategist ? colors.info : colors.success),
                    title: _roleTitle(role),
                    subtitle: switch (settings.source) {
                      CompanionVoiceSource.system => '系统本地语音合成 · 点击试听',
                      CompanionVoiceSource.custom => '自定义 HTTP 语音接口 · 点击试听',
                      _ => '音色：$displayVoice（点击更换）',
                    },
                    trailing: TextButton(
                      onPressed: () => unawaited(_previewRole(role, settings)),
                      child: const Text('试听'),
                    ),
                    onTap: !canPickVoice
                        ? () => unawaited(_previewRole(role, settings))
                        : () => unawaited(_pickVoiceForRole(role, settings, displayVoice)),
                  );
                }),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: ListPageCard(
            title: '联调入口',
            children: [
              ListPageRow(
                icon: AppIcons.running,
                title: '语音收局口述测试',
                subtitle: '进入局官一拍一拍语音追问环节',
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const BoutVoicePage()),
                ),
              ),
            ],
          ),
        ),
        if (_statusMsg != null) ...[
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text(
              _statusMsg!,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(color: colors.info),
            ),
          ),
        ],
        const SizedBox(height: 24),
      ],
    );
  }
}
