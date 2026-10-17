package cn.companion.assistant;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PhoneActions {
    static String perform(Context context, CommandParser.Command command) {
        try {
            return switch (command.type()) {
                case OPEN_APP -> openApp(context, command.argument());
                case HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME, "已返回桌面。");
                case BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK, "已返回。");
                case RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "已打开最近任务。");
                case SETTINGS -> { context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); yield "已打开系统设置。"; }
                case VOLUME_UP, VOLUME_DOWN -> {
                    AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, command.type() == CommandParser.Type.VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                    yield command.type() == CommandParser.Type.VOLUME_UP ? "媒体音量已调高一级。" : "媒体音量已调低一级。";
                }
                default -> "这个操作还没有接入。";
            };
        } catch (RuntimeException e) { return "系统没有允许这次操作。请回到伴随后重试，或检查相关权限。"; }
    }
    private static String global(int action, String success) {
        PhoneAccessService service = PhoneAccessService.live;
        if (service == null) return "请先在伴随首页开启「屏幕与导航」无障碍服务。";
        return service.performGlobalAction(action) ? success : "系统未能执行这个导航操作。";
    }
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s·._-]", "").replaceFirst("(应用|app)$", "");
    }
    private static String openApp(Context context, String name) {
        if (name.isBlank()) return "请说出要打开的应用名称。";
        String target = normalize(name);
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, ResolveInfo> exact = new LinkedHashMap<>(), partial = new LinkedHashMap<>();
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(query, 0)) {
            String label = normalize(info.loadLabel(context.getPackageManager()).toString());
            if (label.equals(target)) exact.put(info.activityInfo.packageName, info);
            else if (label.contains(target)) partial.put(info.activityInfo.packageName, info);
        }
        List<ResolveInfo> matches = new ArrayList<>((exact.isEmpty() ? partial : exact).values());
        if (matches.isEmpty()) return "没有找到「" + name + "」。请使用手机上显示的完整应用名称。";
        if (matches.size() > 1) {
            List<String> labels = new ArrayList<>();
            for (ResolveInfo info : matches.subList(0, Math.min(4, matches.size()))) labels.add(info.loadLabel(context.getPackageManager()).toString());
            return "找到了多个应用：" + String.join("、", labels) + "。请说完整名称。";
        }
        ResolveInfo info = matches.get(0);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(info.activityInfo.packageName);
        if (launch == null) return "这个应用没有可打开的入口。";
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "已请求打开「" + info.loadLabel(context.getPackageManager()) + "」。";
    }
    private PhoneActions() {}
}
