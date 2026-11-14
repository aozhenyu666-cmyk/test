import 'package:flutter/material.dart';

import '../services/dsh_bridge.dart';
import '../services/pc_sensor.dart';
import '../theme/mist_theme.dart';

class PcSensorPane extends StatefulWidget {
  const PcSensorPane({super.key, required this.controller});

  final DesktopController controller;

  @override
  State<PcSensorPane> createState() => _PcSensorPaneState();
}

class _PcSensorPaneState extends State<PcSensorPane> {
  late final TextEditingController _awCtrl;
  late final TextEditingController _obsCtrl;
  String _probeResult = '';

  @override
  void initState() {
    super.initState();
    _awCtrl = TextEditingController(text: widget.controller.sensor.activityWatchUrl);
    _obsCtrl = TextEditingController(text: widget.controller.sensor.obsidianUrl);
  }

  @override
  void dispose() {
    _awCtrl.dispose();
    _obsCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final sample = c.latestSample;
    final catColor = switch (sample?.category) {
      PcActivityCategory.coding ||
      PcActivityCategory.docs ||
      PcActivityCategory.research ||
      PcActivityCategory.studyVideo =>
        MistColors.emerald,
      PcActivityCategory.entertainment => MistColors.crimson,
      PcActivityCategory.afk => MistColors.amber,
      _ => MistColors.cobalt,
    };

    return ListView(
      children: [
        MistGlassSurface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(Icons.monitor_heart_rounded, color: catColor),
                  const SizedBox(width: 8),
                  Text('Windows 前台与 AFK 哨兵', style: Theme.of(context).textTheme.titleMedium),
                  const Spacer(),
                  Switch(
                    value: c.sensorRunning,
                    onChanged: (v) => v ? c.startSensorLoop() : c.stopSensorLoop(),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              if (sample == null)
                const Text('正在采集首个窗口快照...')
              else
                Wrap(
                  spacing: 12,
                  runSpacing: 8,
                  children: [
                    Chip(
                      backgroundColor: catColor.withValues(alpha: 0.16),
                      label: Text('分类：${sample.category.label}'),
                    ),
                    Chip(label: Text('进程：${sample.app.isEmpty ? "unknown" : sample.app}')),
                    Chip(label: Text('空闲：${sample.idleSec}s${sample.isAfk ? " (AFK)" : ""}')),
                    Chip(label: Text('专注累计：${sample.focusMinutes.toStringAsFixed(1)} min')),
                    Chip(label: Text('娱乐累计：${sample.entertainmentMinutes.toStringAsFixed(1)} min')),
                  ],
                ),
              if (sample != null && sample.title.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text('当前窗口标题：${sample.title}', maxLines: 2, overflow: TextOverflow.ellipsis),
              ],
            ],
          ),
        ),
        const SizedBox(height: 12),
        MistGlassSurface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('视频切片与计划严格锁定守护', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('手机端锁墙时严格提醒（娱乐窗口自动置顶提示）'),
                value: c.enableStrictPlanGuard,
                onChanged: (v) {
                  c.enableStrictPlanGuard = v;
                  c.savePrefsPatch({'enableStrictPlanGuard': v});
                  c.tickSensor();
                },
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: Text('启用 3 分钟学习/视频切片暂停守护（剩余 ${c.videoSliceSecondsLeft}s）'),
                subtitle: const Text('看视频满 3 分钟自动模拟 VK_MEDIA_PLAY_PAUSE 暂停键并拉起一拍反思卡片'),
                value: c.enableVideoSliceTimer,
                onChanged: c.toggleVideoSliceTimer,
              ),
              Row(
                children: [
                  OutlinedButton.icon(
                    onPressed: c.triggerManualMediaPause,
                    icon: const Icon(Icons.pause_circle_outline_rounded, size: 18),
                    label: const Text('立即测试媒体暂停 + 反思卡片'),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        MistGlassSurface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('可选本地联动源（ActivityWatch / Obsidian）', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 10),
              Row(
                children: [
                  Checkbox(
                    value: c.enableAw,
                    onChanged: (v) {
                      c.enableAw = v == true;
                      c.savePrefsPatch({'enableAw': c.enableAw});
                      c.tickSensor();
                    },
                  ),
                  Expanded(
                    child: TextField(
                      controller: _awCtrl,
                      decoration: const InputDecoration(
                        isDense: true,
                        labelText: 'ActivityWatch URL',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(
                    onPressed: () async {
                      c.sensor.activityWatchUrl = _awCtrl.text;
                      await c.savePrefsPatch({'awUrl': _awCtrl.text});
                      final res = await c.sensor.probeActivityWatch();
                      if (!mounted) return;
                      setState(() {
                        _probeResult = res == null ? 'ActivityWatch 未响应（可忽略，Win32 采集已接管）' : 'AW 正常：${res.$1} / ${res.$2}';
                      });
                    },
                    child: const Text('探测 AW'),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Checkbox(
                    value: c.enableObsidian,
                    onChanged: (v) {
                      c.enableObsidian = v == true;
                      c.savePrefsPatch({'enableObsidian': c.enableObsidian});
                      c.tickSensor();
                    },
                  ),
                  Expanded(
                    child: TextField(
                      controller: _obsCtrl,
                      decoration: const InputDecoration(
                        isDense: true,
                        labelText: 'Obsidian Local REST URL',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton(
                    onPressed: () async {
                      c.sensor.obsidianUrl = _obsCtrl.text;
                      await c.savePrefsPatch({'obsidianUrl': _obsCtrl.text});
                      final note = await c.sensor.probeObsidianActiveNote();
                      if (!mounted) return;
                      setState(() {
                        _probeResult = note == null ? 'Obsidian Local REST 未启用（可选）' : '当前活跃笔记：$note';
                      });
                    },
                    child: const Text('探测 Obsidian'),
                  ),
                ],
              ),
              if (_probeResult.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(_probeResult, style: const TextStyle(fontSize: 12.5)),
              ],
            ],
          ),
        ),
      ],
    );
  }
}
