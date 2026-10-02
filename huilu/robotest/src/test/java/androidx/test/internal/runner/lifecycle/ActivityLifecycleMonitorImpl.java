package androidx.test.internal.runner.lifecycle;

import android.app.Activity;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.Stage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    private final Map<Activity, Stage> stages = new WeakHashMap<>();
    private final List<ActivityLifecycleCallback> callbacks = new ArrayList<>();

    public ActivityLifecycleMonitorImpl() {}

    public void signalLifecycleChange(Stage stage, Activity activity) {
        stages.put(activity, stage);
        for (ActivityLifecycleCallback c : new ArrayList<>(callbacks)) c.onActivityLifecycleChanged(activity, stage);
    }

    @Override public void addLifecycleCallback(ActivityLifecycleCallback c) { callbacks.add(c); }

    @Override public void removeLifecycleCallback(ActivityLifecycleCallback c) { callbacks.remove(c); }

    @Override public Stage getLifecycleStageOf(Activity a) {
        Stage s = stages.get(a);
        if (s == null) throw new IllegalArgumentException("Unknown activity: " + a);
        return s;
    }

    @Override public Collection<Activity> getActivitiesInStage(Stage stage) {
        List<Activity> out = new ArrayList<>();
        for (Map.Entry<Activity, Stage> e : stages.entrySet()) if (e.getValue() == stage) out.add(e.getKey());
        return out;
    }
}
