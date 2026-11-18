package com.moadeal;

import android.os.Bundle;
import android.text.InputType;
import android.text.InputFilter;
import android.widget.Button;
import android.widget.EditText;
import com.google.android.gms.tasks.Task;

public class ProfileActivity extends BaseActivity {
    private EditText name;
    private Button save;
    private SaveRequest pending;

    private static final class SaveRequest {
        final Task<FirebaseAccounts.Profile> task;

        SaveRequest(Task<FirebaseAccounts.Profile> task) { this.task = task; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        title("프로필 수정"); form();
        Ui.text(this, content, store.accountId(store.account), 20, true);
        Ui.text(this, content, store.seller() ? "판매자" : "구매자", 15, false);
        name = Ui.field(this, content, store.seller() ? "가게 이름" : "닉네임",
            store.profileName(), InputType.TYPE_CLASS_TEXT, R.id.profile_name);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        save = primary("저장", this::saveName);
        if (accounts != null && getLastNonConfigurationInstance() instanceof SaveRequest request) observe(request);
    }

    @Override public Object onRetainNonConfigurationInstance() { return pending; }

    private void saveName() {
        if (pending != null || !Ui.required(name)) return;
        String value = Ui.value(name);
        if (accounts == null) {
            store.saveProfile(value);
            finish();
            return;
        }
        observe(new SaveRequest(accounts.updateName(value)));
    }

    private void observe(SaveRequest request) {
        pending = request;
        name.setEnabled(false);
        save.setEnabled(false);
        request.task.addOnCompleteListener(result -> {
            if (isChangingConfigurations()) return;
            FirebaseAccounts.Profile profile = result.isSuccessful() ? result.getResult() : null;
            boolean saved = profile != null && profile.uid.equals(accounts.currentUid()) && store.hasFirebaseProfile(profile.uid);
            if (saved) store.applyFirebaseProfile(profile);
            if (isFinishing() || isDestroyed() || pending != request) return;
            pending = null;
            if (saved) {
                finish();
            } else {
                name.setEnabled(true);
                save.setEnabled(true);
                note("프로필을 저장하지 못했습니다. 다시 시도해 주세요.");
            }
        });
    }
}
