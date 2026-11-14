import 'dart:io';

import 'package:flutter/material.dart';

import '../services/dsh_bridge.dart';
import '../theme/mist_theme.dart';

/// Wall audit panel + PC code/git-diff/markdown deliverable uploader + formal appeals.
class WallDropPane extends StatefulWidget {
  const WallDropPane({super.key, required this.controller});

  final DesktopController controller;

  @override
  State<WallDropPane> createState() => _WallDropPaneState();
}

class _WallDropPaneState extends State<WallDropPane> {
  final _titleCtrl = TextEditingController();
  final _filePathCtrl = TextEditingController();
  final _repoPathCtrl = TextEditingController(text: Directory.current.path);
  final _contentCtrl = TextEditingController();
  final _appealEvidenceCtrl = TextEditingController();
  String _submitStatus = '';

  @override
  void dispose() {
    _titleCtrl.dispose();
    _filePathCtrl.dispose();
    _repoPathCtrl.dispose();
    _contentCtrl.dispose();
    _appealEvidenceCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadLocalFile() async {
    final path = _filePathCtrl.text.trim().replaceAll('"', '');
    if (path.isEmpty) return;
    final f = File(path);
    if (!await f.exists()) {
      setState(() => _submitStatus = '找不到文件：$path');
      return;
    }
    final raw = await f.readAsString();
    setState(() {
      if (_titleCtrl.text.trim().isEmpty) {
        _titleCtrl.text = f.uri.pathSegments.last;
      }
      _contentCtrl.text = raw.length > 3500 ? '${raw.substring(0, 3500)}\n... [truncated]' : raw;
      _submitStatus = '已载入文件 ${f.path}（${raw.length} 字符）';
    });
  }

  Future<void> _loadGitDiff() async {
    setState(() => _submitStatus = '正在读取 git diff HEAD...');
    try {
      final diff = await widget.controller.captureGitDiff(_repoPathCtrl.text);
      if (!mounted) return;
      setState(() {
        if (_titleCtrl.text.trim().isEmpty) _titleCtrl.text = 'Git 工作区变更交付';
        _contentCtrl.text = diff;
        _submitStatus = '已提取最新 git diff（${diff.length} 字符）';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitStatus = '读取 git diff 失败：$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final locked = c.wallData['locked'] == true;
    final kind = (c.wallData['kind'] ?? 'normal').toString();
    final why = (c.wallData['why'] ?? '墙当前处于常规监控或未知状态').toString();
    final items = (c.wallThreadData['items'] as List?) ?? const [];

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          flex: 5,
          child: MistGlassSurface(
            child: ListView(
              children: [
                Row(
                  children: [
                    Icon(
                      locked ? Icons.lock_rounded : Icons.verified_user_rounded,
                      color: locked ? MistColors.crimson : MistColors.emerald,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      locked ? '规则墙锁定中 ($kind)' : '规则墙状态正常 ($kind)',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(why, style: const TextStyle(fontSize: 13)),
                const Divider(height: 22),
                Text('向大肥鱼交付作品 / 代码改动（交墙验真）', style: Theme.of(context).textTheme.titleSmall),
                const SizedBox(height: 10),
                TextField(
                  controller: _titleCtrl,
                  decoration: const InputDecoration(
                    isDense: true,
                    labelText: '交付物标题',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _filePathCtrl,
                        decoration: const InputDecoration(
                          isDense: true,
                          labelText: '本地文件路径（可直接把文件路径拖/粘进这里）',
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    OutlinedButton(onPressed: _loadLocalFile, child: const Text('载入文件')),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _repoPathCtrl,
                        decoration: const InputDecoration(
                          isDense: true,
                          labelText: 'Git 仓库路径',
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    OutlinedButton(onPressed: _loadGitDiff, child: const Text('一键抓 Git Diff')),
                  ],
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _contentCtrl,
                  minLines: 6,
                  maxLines: 12,
                  decoration: const InputDecoration(
                    labelText: '交付物正文 / Diff / 笔记摘录',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    FilledButton.icon(
                      onPressed: () async {
                        final res = await c.submitArtifactToWall(
                          title: _titleCtrl.text,
                          content: _contentCtrl.text,
                          sourcePath: _filePathCtrl.text,
                        );
                        if (!mounted) return;
                        setState(() {
                          _submitStatus = res?['error'] != null
                              ? '提交失败：${res?['error']}'
                              : '已提交至手机规则墙验真';
                        });
                      },
                      icon: const Icon(Icons.upload_file_rounded, size: 18),
                      label: const Text('提交交付物验真'),
                    ),
                    const SizedBox(width: 10),
                    if (_submitStatus.isNotEmpty)
                      Expanded(child: Text(_submitStatus, style: const TextStyle(fontSize: 12))),
                  ],
                ),
              ],
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          flex: 4,
          child: MistGlassSurface(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('墙审查记录与申诉 (/wall/thread)', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                Expanded(
                  child: items.isEmpty
                      ? const Center(child: Text('暂无已落账墙判定记录'))
                      : ListView.builder(
                          itemCount: items.length,
                          itemBuilder: (_, idx) {
                            final it = (items[idx] as Map).cast<String, Object?>();
                            final at = int.tryParse((it['at'] ?? 0).toString()) ?? 0;
                            return Container(
                              margin: const EdgeInsets.only(bottom: 8),
                              padding: const EdgeInsets.all(10),
                              decoration: BoxDecoration(
                                color: Colors.black.withValues(alpha: 0.04),
                                borderRadius: BorderRadius.circular(12),
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '${it['kind'] ?? 'verdict'}  ·  ${it['code'] ?? ''}',
                                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
                                  ),
                                  const SizedBox(height: 4),
                                  Text((it['reason'] ?? it['text'] ?? it.toString()).toString(), style: const TextStyle(fontSize: 12.5)),
                                  if (at > 0)
                                    Align(
                                      alignment: Alignment.centerRight,
                                      child: TextButton(
                                        onPressed: _appealEvidenceCtrl.text.trim().isEmpty
                                            ? null
                                            : () => c.submitWallAppeal(at: at, evidence: _appealEvidenceCtrl.text),
                                        child: const Text('用下方新证据申诉此条'),
                                      ),
                                    ),
                                ],
                              ),
                            );
                          },
                        ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _appealEvidenceCtrl,
                  decoration: const InputDecoration(
                    isDense: true,
                    labelText: '申诉客观证据（每日限 2 次）',
                    border: OutlineInputBorder(),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
