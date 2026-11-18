package com.moadeal;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.util.Patterns;
import android.view.View;
import android.widget.*;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuthException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LoginActivity extends BaseActivity {
    private static final String SIGNUP_ROLE = "signup_role";

    private boolean signup;
    private Spinner accountPicker;
    private RadioGroup mode;
    private Button enter;
    private EditText signupName;
    private EditText email;
    private EditText password;
    private List<String> accountIds = Arrays.asList(Models.BUYER, Models.SELLER);
    private boolean foreground;
    private boolean restoreChecked;
    private LoginRequest pending;
    private FirebaseAccounts.Profile readyProfile;

    private static final class LoginRequest {
        final Task<FirebaseAccounts.Profile> task;
        final String failure;

        LoginRequest(Task<FirebaseAccounts.Profile> task, String failure) {
            this.task = task;
            this.failure = failure;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        title("모아딜");
        findViewById(R.id.back).setVisibility(View.GONE);
        getLayoutInflater().inflate(R.layout.activity_login, body, true);
        accountPicker = findViewById(R.id.login_account);
        mode = findViewById(R.id.login_mode);
        enter = findViewById(R.id.login_enter);
        signupName = findViewById(R.id.signup_name);
        email = findViewById(R.id.login_email);
        password = findViewById(R.id.login_password);
        signupName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        Ui.primary(enter);

        if (accounts == null) {
            refreshAccounts();
        } else {
            ((TextView) findViewById(R.id.login_account_hint)).setText("이메일과 비밀번호로 모아딜을 이용하세요.");
            ((TextView) findViewById(R.id.login_account_label)).setText("가입 유형");
            accountPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"구매자", "판매자"}));
            if (state != null && FirebaseAccounts.SELLER_ROLE.equals(state.getString(SIGNUP_ROLE))) {
                accountPicker.setSelection(1);
            }
        }
        mode.setOnCheckedChangeListener((group, id) -> {
            signup = id == R.id.mode_signup;
            if (accounts == null) refreshAccounts();
            updateForm();
        });
        accountPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateForm(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        enter.setOnClickListener(view -> { if (accounts == null) enterDemo(); else authenticate(); });

        if (accounts != null && getLastNonConfigurationInstance() instanceof LoginRequest request) {
            restoreChecked = true;
            observe(request);
        }
        updateForm();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        openHomeIfReady();
        if (accounts != null && !restoreChecked) {
            restoreChecked = true;
            if (accounts.pendingSignIn() != null || accounts.hasSession()) {
                observe(new LoginRequest(accounts.loadProfile(), "프로필을 불러오지 못했습니다. 다시 로그인해 주세요."));
            }
        }
    }

    @Override protected void onPause() {
        foreground = false;
        super.onPause();
    }

    @Override public Object onRetainNonConfigurationInstance() { return pending; }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (accounts != null) state.putString(SIGNUP_ROLE, selectedSignupRole());
        super.onSaveInstanceState(state);
    }

    private void enterDemo() {
        String account = selectedAccount();
        if (signup && Models.BUYER.equals(account)) {
            String name = Ui.value(signupName);
            if (!name.isEmpty()) account = store.registerDemoBuyer(name);
        }
        store.account = account;
        startActivity(new Intent(this, signup && store.seller() ? VerificationActivity.class : HomeActivity.class));
    }

    private void authenticate() {
        if (pending != null || readyProfile != null) return;
        if (signup && !Ui.required(signupName)) return;
        String address = Ui.value(email);
        if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
            email.setError("이메일 주소를 확인해 주세요.");
            email.requestFocus();
            return;
        }
        String secret = password.getText().toString();
        if (secret.isEmpty()) {
            password.setError("비밀번호를 입력해 주세요.");
            password.requestFocus();
            return;
        }
        if (signup && secret.length() < 6) {
            password.setError("비밀번호는 6자 이상 입력해 주세요.");
            password.requestFocus();
            return;
        }
        observe(signup
            ? new LoginRequest(accounts.signUp(Ui.value(signupName), address, secret, selectedSignupRole()),
                "가입을 완료하지 못했습니다. 다시 시도해 주세요. 이미 가입한 계정은 로그인해 주세요.")
            : new LoginRequest(accounts.signIn(address, secret), "계정 정보를 불러오지 못했습니다. 다시 시도해 주세요."));
    }

    private String selectedSignupRole() {
        return accountPicker.getSelectedItemPosition() == 1 ? FirebaseAccounts.SELLER_ROLE : FirebaseAccounts.BUYER_ROLE;
    }

    private void observe(LoginRequest request) {
        pending = request;
        updateForm();
        request.task.addOnCompleteListener(result -> {
            if (isFinishing() || isDestroyed() || pending != request) return;
            pending = null;
            if (result.isSuccessful()) {
                readyProfile = result.getResult();
                openHomeIfReady();
            } else {
                note(authenticationError(result.getException(), request.failure));
            }
            updateForm();
        });
    }

    private void openHomeIfReady() {
        if (!foreground || readyProfile == null) return;
        FirebaseAccounts.Profile profile = readyProfile;
        readyProfile = null;
        if (!profile.uid.equals(accounts.currentUid())) {
            updateForm();
            return;
        }
        store.applyFirebaseProfile(profile);
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private String authenticationError(Exception error, String fallback) {
        if (error instanceof FirebaseNetworkException) return "인터넷 연결을 확인하고 다시 시도해 주세요.";
        if (!(error instanceof FirebaseAuthException authError)) return fallback;
        return switch (authError.getErrorCode()) {
            case "ERROR_INVALID_EMAIL" -> "이메일 주소를 확인해 주세요.";
            case "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" ->
                "이메일 또는 비밀번호를 확인해 주세요.";
            case "ERROR_EMAIL_ALREADY_IN_USE" -> "이미 가입된 이메일입니다. 로그인해 주세요.";
            case "ERROR_WEAK_PASSWORD" -> "가입 조건을 충족하는 비밀번호를 입력해 주세요.";
            case "ERROR_USER_DISABLED" -> "이 계정은 현재 사용할 수 없습니다.";
            case "ERROR_TOO_MANY_REQUESTS" -> "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";
            case "ERROR_OPERATION_NOT_ALLOWED" -> "이메일 로그인이 아직 설정되지 않았습니다.";
            default -> fallback;
        };
    }

    private void refreshAccounts() {
        String selected = selectedAccount();
        List<String> ids = new ArrayList<>(Arrays.asList(Models.BUYER, Models.SELLER));
        List<String> labels = new ArrayList<>(Arrays.asList("구매자", "판매자"));
        if (!signup) {
            for (Map.Entry<String, String> buyer : store.demoBuyers().entrySet()) {
                ids.add(buyer.getKey());
                labels.add(buyer.getValue() + " (구매자)");
            }
        }
        accountIds = ids;
        accountPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        accountPicker.setSelection(Math.max(0, ids.indexOf(selected)));
    }

    private String selectedAccount() {
        int index = accountPicker.getSelectedItemPosition();
        return index >= 0 && index < accountIds.size() ? accountIds.get(index) : Models.BUYER;
    }

    private void updateForm() {
        signupName.setVisibility(signup ? View.VISIBLE : View.GONE);
        if (accounts == null) {
            boolean seller = Models.SELLER.equals(selectedAccount());
            signupName.setEnabled(signup && !seller);
            enter.setText(!signup ? "예시 계정으로 입장" : seller ? "사업자 인증 시작" : "가입 화면 체험");
            return;
        }
        int roleVisibility = signup ? View.VISIBLE : View.GONE;
        findViewById(R.id.login_account_label).setVisibility(roleVisibility);
        accountPicker.setVisibility(roleVisibility);

        boolean editing = pending == null && readyProfile == null;
        signupName.setEnabled(signup && editing);
        accountPicker.setEnabled(signup && editing);
        email.setEnabled(editing);
        password.setEnabled(editing);
        findViewById(R.id.mode_login).setEnabled(editing);
        findViewById(R.id.mode_signup).setEnabled(editing);
        enter.setEnabled(editing);
        enter.setText(signup ? "회원가입" : "로그인");
    }
}
