package io.github.machtvapps.machlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

public class HomeButtonService extends AccessibilityService {
    private static final long REOPEN_CHECK_MS = 500;
    private static final int REOPEN_CHECKS = 6;
    private static HomeButtonService running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String systemHome;
    private int reopenChecksLeft;

    static void turnOff() {
        HomeButtonService service = running;
        running = null;
        if (service != null) {
            service.disableSelf();
        }
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        systemHome = home == null ? null : home.activityInfo.packageName;
        if (isFirstConnectionSinceBoot()) {
            openMach();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (running != this || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (isSystemHome(packageName)) {
            openMach();
            reopenChecksLeft = REOPEN_CHECKS;
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(this::reopenUnlessShown, REOPEN_CHECK_MS);
        } else if (packageName != null && !getPackageName().contentEquals(packageName)) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        if (running == this) {
            running = null;
        }
        return super.onUnbind(intent);
    }

    private boolean isFirstConnectionSinceBoot() {
        int bootCount = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        SharedPreferences preferences = getSharedPreferences("home_button_service", MODE_PRIVATE);
        if (preferences.getInt("opened_at_boot_count", -1) == bootCount) {
            return false;
        }
        preferences.edit().putInt("opened_at_boot_count", bootCount).apply();
        return true;
    }

    private boolean isSystemHome(CharSequence packageName) {
        return packageName != null && !getPackageName().contentEquals(packageName) && packageName.toString().equals(systemHome);
    }

    private void reopenUnlessShown() {
        if (!MainActivity.inFront) {
            openMach();
        }
        if (--reopenChecksLeft > 0) {
            handler.postDelayed(this::reopenUnlessShown, REOPEN_CHECK_MS);
        }
    }

    private void openMach() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
