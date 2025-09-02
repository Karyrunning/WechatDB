package com.wechat.dumpdb;

import android.app.Application;

public class BaseApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        System.loadLibrary("sqlcipher");
    }
}
