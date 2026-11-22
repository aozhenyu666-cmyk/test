import 'dart:async';

import 'package:flutter/material.dart';

import '../../l10n/l10n.dart';
import '../../ui/theme/app_icons.dart';
import '../channels/loop_channel.dart';

/// 通用的已安装应用多选弹窗（内置搜索框、预设快捷芯片、虚拟化列表与防遮挡底部 `FilledButton` 确认）。
Future<List<LoopApp>?> showInstalledAppsPicker(
  BuildContext context, {
  required String title,
  required LoopChannel loop,
  List<LoopApp> initialApps = const [],
  List<LoopApp> presets = const [],
  String presetsLabel = '常用应用一键勾选',
  List<String> initialSelected = const [],
  Set<String> excludedPackages = const {},
  List<String> recentPackages = const [],
  bool allowEmpty = false,
  String? emptyError,
  String confirmLabel = '确认',
}) {
  return showDialog<List<LoopApp>>(
    context: context,
    builder: (_) => _InstalledAppsPickerDialog(
      title: title,
      loop: loop,
      initialApps: initialApps,
      presets: presets,
      presetsLabel: presetsLabel,
      initialSelected: initialSelected,
      excludedPackages: excludedPackages,
      recentPackages: recentPackages,
      allowEmpty: allowEmpty,
      emptyError: emptyError,
      confirmLabel: confirmLabel,
    ),
  );
}

class _InstalledAppsPickerDialog extends StatefulWidget {
  const _InstalledAppsPickerDialog({
    required this.title,
    required this.loop,
    required this.initialApps,
    required this.presets,
    required this.presetsLabel,
    required this.initialSelected,
    required this.excludedPackages,
    required this.recentPackages,
    required this.allowEmpty,
    required this.emptyError,
    required this.confirmLabel,
  });

  final String title;
  final LoopChannel loop;
  final List<LoopApp> initialApps;
  final List<LoopApp> presets;
  final String presetsLabel;
  final List<String> initialSelected;
  final Set<String> excludedPackages;
  final List<String> recentPackages;
  final bool allowEmpty;
  final String? emptyError;
  final String confirmLabel;

  @override
  State<_InstalledAppsPickerDialog> createState() => _InstalledAppsPickerDialogState();
}

class _InstalledAppsPickerDialogState extends State<_InstalledAppsPickerDialog> {
  final TextEditingController _controller = TextEditingController();
  final Map<String, String> _selected = <String, String>{};
  List<LoopApp> _installed = const [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    final labelIndex = <String, String>{
      for (final p in widget.presets) p.package: p.label,
      for (final a in widget.initialApps) a.package: a.label,
    };
    for (final pkg in widget.initialSelected) {
      final clean = pkg.trim();
      if (clean.isEmpty || widget.excludedPackages.contains(clean)) continue;
      _selected[clean] = labelIndex[clean] ?? clean;
    }
    if (widget.initialApps.isNotEmpty) {
      _installed = _normalizeApps(widget.initialApps);
      _loading = false;
    } else {
      unawaited(_loadInstalled());
    }
  }

  List<LoopApp> _normalizeApps(List<LoopApp> raw) {
    final rank = <String, int>{};
    for (var i = 0; i < widget.recentPackages.length; i += 1) {
      rank.putIfAbsent(widget.recentPackages[i], () => i);
    }
    final seen = <String>{};
    final out = <LoopApp>[];
    for (final a in raw) {
      if (a.package.isEmpty || widget.excludedPackages.contains(a.package)) continue;
      if (seen.add(a.package)) out.add(a);
    }
    out.sort((a, b) {
      final sa = _selected.containsKey(a.package) ? 0 : 1;
      final sb = _selected.containsKey(b.package) ? 0 : 1;
      if (sa != sb) return sa.compareTo(sb);
      final ra = rank[a.package] ?? 1 << 20;
      final rb = rank[b.package] ?? 1 << 20;
      return ra != rb ? ra.compareTo(rb) : a.label.compareTo(b.label);
    });
    return out;
  }

  Future<void> _loadInstalled() async {
    try {
      final apps = await widget.loop.apps();
      if (!mounted) return;
      for (final a in apps) {
        if (_selected.containsKey(a.package) && _selected[a.package] == a.package) {
          _selected[a.package] = a.label;
        }
      }
      setState(() {
        _installed = _normalizeApps(apps);
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _toggle(String pkg, String label) {
    setState(() {
      _error = null;
      if (_selected.containsKey(pkg)) {
        _selected.remove(pkg);
      } else {
        _selected[pkg] = label.isEmpty ? pkg : label;
      }
    });
  }

  void _submit() {
    final typed = _controller.text.trim();
    final picked = <LoopApp>[
      for (final e in _selected.entries) LoopApp(e.key, e.value),
    ];
    final looksLikePkg = typed.contains('.') && !typed.contains(' ');
    if (looksLikePkg && !_selected.containsKey(typed) && !widget.excludedPackages.contains(typed)) {
      final matched = _installed.where((a) => a.package == typed).firstOrNull;
      picked.add(LoopApp(typed, matched?.label ?? typed));
    } else if (picked.isEmpty && typed.isNotEmpty) {
      final q = typed.toLowerCase();
      final exact = _installed.where((a) => a.label.toLowerCase() == q || a.package.toLowerCase() == q).toList();
      if (exact.length == 1) {
        picked.add(exact.first);
      }
    }
    if (picked.isEmpty && !widget.allowEmpty) {
      setState(() => _error = widget.emptyError ?? '请至少勾选一个应用，或输入完整包名（如 md.obsidian）');
      return;
    }
    Navigator.of(context).pop(picked);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    final query = _controller.text.trim().toLowerCase();
    final availablePresets = widget.presets.where((p) => !widget.excludedPackages.contains(p.package)).toList();
    final filtered = _installed.where((a) {
      if (query.isEmpty) return true;
      return a.label.toLowerCase().contains(query) || a.package.toLowerCase().contains(query);
    }).toList();
    final looksLikePkg = query.contains('.') && !query.contains(' ');
    final effectiveCount = _selected.length +
        (looksLikePkg && !_selected.containsKey(_controller.text.trim()) && !widget.excludedPackages.contains(_controller.text.trim()) ? 1 : 0);

    return AlertDialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 20),
      title: Text(widget.title),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              key: const ValueKey('app-picker-search'),
              controller: _controller,
              decoration: InputDecoration(
                prefixIcon: const Icon(AppIcons.search, size: 18),
                hintText: '搜索应用名，或直接输入包名',
                errorText: _error,
              ),
              onChanged: (_) => setState(() => _error = null),
              onSubmitted: (_) => _submit(),
            ),
            if (availablePresets.isNotEmpty) ...[
              const SizedBox(height: 10),
              Text(widget.presetsLabel, style: theme.textTheme.labelMedium),
              const SizedBox(height: 6),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 116),
                child: SingleChildScrollView(
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      for (final preset in availablePresets)
                        FilterChip(
                          label: Text(preset.label),
                          selected: _selected.containsKey(preset.package),
                          onSelected: (_) => _toggle(preset.package, preset.label),
                        ),
                    ],
                  ),
                ),
              ),
            ],
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: Text(
                    _loading
                        ? '正在读取本机已安装应用…'
                        : (filtered.isEmpty ? '未匹配到，可在上方直接输入包名' : '本机已安装应用（${filtered.length}）'),
                    style: theme.textTheme.labelMedium,
                  ),
                ),
                if (effectiveCount > 0)
                  Text(
                    '已选 $effectiveCount',
                    style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.primary),
                  ),
              ],
            ),
            if (filtered.isNotEmpty) ...[
              const SizedBox(height: 6),
              Flexible(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 260),
                  child: ListView.builder(
                    shrinkWrap: true,
                    itemCount: filtered.length,
                    itemBuilder: (_, idx) {
                      final app = filtered[idx];
                      final checked = _selected.containsKey(app.package);
                      return InkWell(
                        key: ValueKey('picker-app-${app.package}'),
                        borderRadius: BorderRadius.circular(10),
                        onTap: () => _toggle(app.package, app.label),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 7, horizontal: 4),
                          child: Row(
                            children: [
                              Icon(
                                checked ? AppIcons.checkboxOn : AppIcons.checkboxOff,
                                size: 20,
                                color: checked ? theme.colorScheme.primary : null,
                              ),
                              const SizedBox(width: 10),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(app.label, maxLines: 1, overflow: TextOverflow.ellipsis),
                                    Text(
                                      app.package,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: theme.textTheme.bodySmall,
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.commonCancel),
        ),
        FilledButton(
          key: const ValueKey('app-picker-confirm'),
          onPressed: _submit,
          child: Text(effectiveCount > 0 ? '${widget.confirmLabel} ($effectiveCount)' : widget.confirmLabel),
        ),
      ],
    );
  }
}
