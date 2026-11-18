package com.moadeal;

import android.os.Bundle;
import android.text.InputType;
import android.text.InputFilter;
import android.widget.EditText;

public class ProfileActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        title("프로필 수정"); form();
        Ui.text(this, content, Store.accountId(store.account), 20, true);
        Ui.text(this, content, store.seller() ? "판매자" : "구매자", 15, false);
        EditText name = Ui.field(this, content, store.seller() ? "가게 이름" : "닉네임",
            store.profileName(), InputType.TYPE_CLASS_TEXT, R.id.profile_name);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        primary("저장", () -> {
            if (!Ui.required(name)) return;
            store.saveProfile(Ui.value(name)); finish();
        });
    }
}
