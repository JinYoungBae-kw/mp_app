package com.moadeal;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;

public abstract class BaseActivity extends Activity {
    Store store;
    android.view.ViewGroup body;
    LinearLayout content, footer;
    final Handler handler = new Handler(Looper.getMainLooper());
    private String lastDay;
    private final Runnable dayChange = new Runnable() {
        @Override public void run() {
            String today = Store.today();
            if (!today.equals(lastDay)) {
                lastDay = today; onDayChanged();
            }
            scheduleMidnight();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = ((MoadealApplication) getApplication()).store();
        setContentView(R.layout.activity_page);
        body = findViewById(R.id.body);
        footer = findViewById(R.id.footer);
        findViewById(R.id.back).setOnClickListener(view -> finish());
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        if (state != null && state.containsKey("account")) store.account = state.getString("account");
    }

    @Override protected void onResume() {
        super.onResume();
        String today = Store.today();
        if (lastDay != null && !lastDay.equals(today)) onDayChanged();
        lastDay = today;
        scheduleMidnight();
    }
    private void scheduleMidnight() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
        java.time.ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        handler.removeCallbacks(dayChange);
        handler.postDelayed(dayChange, Math.max(1, java.time.Duration.between(now, next).toMillis()));
    }
    @Override protected void onPause() { handler.removeCallbacks(dayChange); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("account", store.account);
        super.onSaveInstanceState(state);
    }

    void title(String title) { ((TextView) findViewById(R.id.page_title)).setText(title); }
    void action(String label, Runnable runnable) {
        Button action = findViewById(R.id.toolbar_action);
        action.setVisibility(View.VISIBLE); action.setText(label);
        action.setOnClickListener(view -> runnable.run());
    }
    LinearLayout scroll() {
        body.removeAllViews(); footer.removeAllViews();
        View page = getLayoutInflater().inflate(R.layout.page_scroll, body, false);
        body.addView(page);
        content = page.findViewById(R.id.page_content);
        return content;
    }
    LinearLayout form() {
        body.removeAllViews(); footer.removeAllViews();
        View form = getLayoutInflater().inflate(R.layout.activity_form, body, false);
        body.addView(form);
        content = form.findViewById(R.id.fields);
        return content;
    }
    void primary(String label, Runnable runnable) { Ui.primary(Ui.button(this, footer, label, runnable)); }
    void note(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    void onFavoriteChanged() {}
    void onDayChanged() {}
    void switchAccount() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent); finish();
    }
}
