package me.firesun.dingtalk.enhancement.modern;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class App extends Application implements XposedServiceHelper.OnServiceListener {
    public static volatile XposedService service;

    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override public void onServiceBind(XposedService value) {
        service = value;
    }

    @Override public void onServiceDied(XposedService value) {
        if (service == value) service = null;
    }
}

