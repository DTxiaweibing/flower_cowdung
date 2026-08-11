// GlobalApplication.java
package com.example.cowdunggame;

import android.app.Application;

public class GlobalApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
    }
}