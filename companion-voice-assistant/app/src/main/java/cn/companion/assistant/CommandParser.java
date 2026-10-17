package cn.companion.assistant;

import java.util.regex.Pattern;

/** Only explicit commands match. Conversation never implicitly authorizes an action. */
public final class CommandParser {
    public enum Type { OPEN_APP, HOME, BACK, RECENTS, SCREEN, VOLUME_UP, VOLUME_DOWN, SETTINGS, STOP, CHAT }
    public record Command(Type type, String argument) {}
    private static final Pattern END = Pattern.compile("[。.!！?？,，\\s]+$");
    public static Command parse(String input) {
        String s = END.matcher(input == null ? "" : input.trim()).replaceAll("");
        s = s.replaceFirst("^(请帮我|帮我|请)", "").trim();
        if (s.matches("(返回桌面|回到桌面|回桌面|主页)")) return new Command(Type.HOME, "");
        if (s.matches("(返回|返回上一页|返回上一级|后退)")) return new Command(Type.BACK, "");
        if (s.matches("(最近任务|打开最近任务|多任务|打开多任务)")) return new Command(Type.RECENTS, "");
        if (s.matches("(看看屏幕|看一下屏幕|看屏幕|理解屏幕|读一下屏幕|屏幕上有什么)")) return new Command(Type.SCREEN, "请解释当前屏幕的内容，并给我有用的下一步建议。");
        if (s.matches("(看看屏幕|看一下屏幕|看屏幕)[，,:： ].+")) return new Command(Type.SCREEN, s.replaceFirst("^(看看屏幕|看一下屏幕|看屏幕)[，,:： ]+", ""));
        if (s.matches("(声音大一点|音量加大|增大音量)")) return new Command(Type.VOLUME_UP, "");
        if (s.matches("(声音小一点|音量减小|减小音量)")) return new Command(Type.VOLUME_DOWN, "");
        if (s.matches("(打开设置|系统设置)")) return new Command(Type.SETTINGS, "");
        if (s.matches("(暂停聆听|停止聆听|结束对话|停止对话)")) return new Command(Type.STOP, "");
        if (s.matches("(打开|启动|切换到|切到).{1,30}")) return new Command(Type.OPEN_APP, s.replaceFirst("^(打开|启动|切换到|切到)", "").trim());
        return new Command(Type.CHAT, s);
    }
    private CommandParser() {}
}
