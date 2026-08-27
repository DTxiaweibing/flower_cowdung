// GlobalApplication.java (app-new 精简版)
package com.example.cowdunggame;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class GlobalApplication extends Application {

    private int activityCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        BgmManager.get().init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                if (activity instanceof AuthActivity) return; // 登录页不播放 BGM
                activityCount++;
                if (activityCount == 1) {
                    BgmManager.get().setForeground(true);
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                if (activity instanceof AuthActivity) return;
                activityCount--;
                if (activityCount <= 0) {
                    activityCount = 0;
                    BgmManager.get().setForeground(false);
                }
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityResumed(Activity activity) {}

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {}
        });
    }
}
