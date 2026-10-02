package androidx.test.runner.lifecycle;

public final class ApplicationLifecycleMonitorRegistry {
    private static ApplicationLifecycleMonitor instance;

    public static ApplicationLifecycleMonitor getInstance() { return instance; }

    public static void registerInstance(ApplicationLifecycleMonitor m) { instance = m; }
}
