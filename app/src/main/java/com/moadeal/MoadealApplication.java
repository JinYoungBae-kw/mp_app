package com.moadeal;

import android.app.Application;

public class MoadealApplication extends Application {
    private Store store;
    private FirebaseAccounts accounts;
    private FirebaseTrades trades;
    private FirebaseCommunity community;

    @Override public void onCreate() {
        super.onCreate();
        store = new Store(this);
        accounts = FirebaseAccounts.create(this);
        if (accounts != null) {
            trades = new FirebaseTrades(accounts.firestore(), accounts);
            community = new FirebaseCommunity(accounts.firestore());
            store.useFirebaseTrades(trades);
        }
    }

    Store store() { return store; }
    FirebaseAccounts accounts() { return accounts; }
    FirebaseTrades trades() { return trades; }
    FirebaseCommunity community() { return community; }
}
