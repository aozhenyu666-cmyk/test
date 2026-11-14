import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';

import '../services/dsh_bridge.dart';
import '../theme/mist_theme.dart';

class ChatPane extends StatefulWidget {
  const ChatPane({super.key, required this.controller});

  final DesktopController controller;

  @override
  State<ChatPane> createState() => _ChatPaneState();
}

class _ChatPaneState extends State<ChatPane> {
  final _input = TextEditingController();

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  void _send() {
    final txt = _input.text.trim();
    if (txt.isEmpty) return;
    _input.clear();
    widget.controller.sendSay(txt);
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final remoteItems = c.transcript.items;

    return Row(
      children: [
        if (c.hasDshClient)
          SizedBox(
            width: 240,
            child: Padding(
              padding: const EdgeInsets.only(right: 12),
              child: MistGlassSurface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Remote 会话列表', style: Theme.of(context).textTheme.titleSmall),
                    const SizedBox(height: 8),
                    Expanded(
                      child: ListView.builder(
                        itemCount: c.sessions.length,
                        itemBuilder: (_, idx) {
                          final s = c.sessions[idx];
                          final selected = s.sessionId == c.activeSessionId;
                          return ListTile(
                            dense: true,
                            selected: selected,
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                            title: Text(
                              s.projections.title ?? s.sessionId,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            subtitle: Text(s.running ? '运行中' : '空闲', style: const TextStyle(fontSize: 11)),
                            onTap: () => c.selectSession(s.sessionId),
                          );
                        },
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        Expanded(
          child: MistGlassSurface(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    ChoiceChip(
                      selected: c.activeRole == 'companion',
                      label: const Text('鱼丸 · 日常陪伴'),
                      selectedColor: MistColors.fishCoral.withValues(alpha: 0.22),
                      onSelected: (_) => setState(() => c.activeRole = 'companion'),
                    ),
                    const SizedBox(width: 8),
                    ChoiceChip(
                      selected: c.activeRole == 'strategist',
                      label: const Text('鲸澜 · 结构谋士'),
                      selectedColor: MistColors.whaleTeal.withValues(alpha: 0.22),
                      onSelected: (_) => setState(() => c.activeRole = 'strategist'),
                    ),
                    const Spacer(),
                    if (c.sendingChat)
                      const Row(
                        children: [
                          SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
                          SizedBox(width: 6),
                          Text('等待回复...', style: TextStyle(fontSize: 12)),
                        ],
                      ),
                  ],
                ),
                const Divider(height: 20),
                Expanded(
                  child: ListView(
                    children: [
                      if (remoteItems.isNotEmpty) ...[
                        const Padding(
                          padding: EdgeInsets.only(bottom: 8),
                          child: Text('Direct / Noise_IK 会话流', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
                        ),
                        for (final item in remoteItems) _buildRemoteItem(context, item),
                        const Divider(height: 24),
                      ],
                      if (c.chatLines.isEmpty && remoteItems.isEmpty)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 48),
                          child: Center(
                            child: Text(
                              '选择「鱼丸」或「鲸澜」开始 PC 端双线协作；\n连接 Tailscale/LAN Host 或 Remote 中继后即可直通手机大脑。',
                              textAlign: TextAlign.center,
                              style: TextStyle(color: Theme.of(context).textTheme.bodySmall?.color),
                            ),
                          ),
                        ),
                      for (final line in c.chatLines) _buildChatBubble(context, line),
                    ],
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _input,
                        minLines: 1,
                        maxLines: 4,
                        onSubmitted: (_) => _send(),
                        decoration: InputDecoration(
                          hintText: c.activeRole == 'strategist'
                              ? '对鲸澜说（拆分任务 / 请求复核 / 发思考切片）...'
                              : '对鱼丸说（状态报备 / 今日打卡 / 聊聊当前进展）...',
                          filled: true,
                          border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    FilledButton.icon(
                      onPressed: c.sendingChat ? null : _send,
                      icon: const Icon(Icons.send_rounded, size: 18),
                      label: const Text('发送'),
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

  Widget _buildRemoteItem(BuildContext context, ChatItem item) {
    final text = switch (item) {
      UserChatItem(:final text) => '我：$text',
      AssistantChatItem(:final text) => '助手：$text',
      _ => item.key,
    };
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: MistColors.cobalt.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(text, style: const TextStyle(fontSize: 13)),
    );
  }

  Widget _buildChatBubble(BuildContext context, CompanionChatLine line) {
    final tint = switch (line.role) {
      'user' => MistColors.cobalt.withValues(alpha: 0.14),
      'strategist' => MistColors.whaleTeal.withValues(alpha: 0.14),
      'companion' => MistColors.fishCoral.withValues(alpha: 0.14),
      _ => Colors.grey.withValues(alpha: 0.14),
    };
    return Align(
      alignment: line.isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        constraints: const BoxConstraints(maxWidth: 680),
        margin: const EdgeInsets.only(bottom: 10),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: tint,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              line.speaker,
              style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 4),
            SelectableText(line.text, style: const TextStyle(fontSize: 13.5, height: 1.4)),
          ],
        ),
      ),
    );
  }
}
