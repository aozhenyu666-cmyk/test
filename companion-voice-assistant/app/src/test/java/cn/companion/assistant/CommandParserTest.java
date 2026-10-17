package cn.companion.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class CommandParserTest {
    @Test public void explicitNavigationAndLaunchCommands() {
        assertEquals(CommandParser.Type.HOME, CommandParser.parse("请帮我返回桌面。").type());
        assertEquals(CommandParser.Type.BACK, CommandParser.parse("返回").type());
        assertEquals(CommandParser.Type.RECENTS, CommandParser.parse("最近任务").type());
        var command = CommandParser.parse("切换到微信！");
        assertEquals(CommandParser.Type.OPEN_APP, command.type()); assertEquals("微信", command.argument());
        assertEquals(CommandParser.Type.SETTINGS, CommandParser.parse("打开设置").type());
    }
    @Test public void questionsAndNegationsDoNotTriggerActions() {
        for (String input : new String[]{"不要打开微信", "你能打开微信吗", "我刚才打开了微信", "返回桌面是什么意思", "请不要返回", "", "打开", "帮我聊聊天"}) {
            assertEquals(input, CommandParser.Type.CHAT, CommandParser.parse(input).type());
        }
        assertEquals(CommandParser.Type.CHAT, CommandParser.parse(null).type());
    }
    @Test public void screenNeedsExplicitRequestAndKeepsQuestion() {
        assertEquals(CommandParser.Type.SCREEN, CommandParser.parse("看一下屏幕").type());
        var command = CommandParser.parse("看一下屏幕，帮我总结这一页。");
        assertEquals(CommandParser.Type.SCREEN, command.type()); assertEquals("帮我总结这一页", command.argument());
        assertEquals(CommandParser.Type.CHAT, CommandParser.parse("不要看屏幕").type());
    }
    @Test public void stopListeningAndVolumeCommands() {
        assertEquals(CommandParser.Type.STOP, CommandParser.parse("停止聆听。").type());
        assertEquals(CommandParser.Type.VOLUME_DOWN, CommandParser.parse("声音小一点").type());
        assertEquals(CommandParser.Type.VOLUME_UP, CommandParser.parse("声音大一点").type());
    }
}
