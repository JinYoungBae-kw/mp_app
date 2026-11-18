package com.moadeal;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

public class LoginActivity extends BaseActivity {
    private boolean signup;
    private Spinner accounts;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        title("모아딜");
        findViewById(R.id.back).setVisibility(View.GONE);
        getLayoutInflater().inflate(R.layout.activity_login, body, true);
        accounts = findViewById(R.id.login_account);
        accounts.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"구매자", "판매자"}));
        Button enter = findViewById(R.id.login_enter);
        Ui.primary(enter);
        ((RadioGroup) findViewById(R.id.login_mode)).setOnCheckedChangeListener((group, id) -> {
            signup = id == R.id.mode_signup;
            findViewById(R.id.signup_name).setVisibility(signup ? View.VISIBLE : View.GONE);
            updateLabel();
        });
        accounts.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateLabel(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        enter.setOnClickListener(view -> {
            store.account = accounts.getSelectedItemPosition() == 1 ? Models.SELLER : Models.BUYER;
            startActivity(new Intent(this, signup && store.seller() ? VerificationActivity.class : HomeActivity.class));
        });
    }

    private void updateLabel() {
        ((Button) findViewById(R.id.login_enter)).setText(!signup ? "예시 계정으로 입장"
            : accounts.getSelectedItemPosition() == 1 ? "사업자 인증 시작" : "가입 화면 체험");
    }
}
