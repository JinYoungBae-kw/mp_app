package com.moadeal;

import android.app.Application;

public class MoadealApplication extends Application {
    private Store store;

    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
    }

    Store store() { return store; }
}
