package com.moadeal;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import com.google.android.gms.tasks.Task;
import static com.moadeal.Models.*;

public class OfferFormActivity extends BaseActivity {
    private static final int PICKUP_ADDRESS_REQUEST = 12;
    private static final String PICKUP_LABEL = "픽업 장소";

    private Product product;
    private EditText price, quantity;
    private String pickupAddress = "";
    private long expiry = System.currentTimeMillis() + 3L * 86400000;
    private Button placeButton, dateButton, submitButton;
    private Submission pending;

    private record Submission(String uid, Task<Void> task) {}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        product = store.product(getIntent().getStringExtra("product"));
        if (product == null || !store.seller()) { finish(); return; }
        if (state != null) {
            expiry = state.getLong("expiry", expiry);
            pickupAddress = state.getString("manualPickupAddress", "");
        }
        title("판매 제안 작성"); form();
        Ui.text(this, content, product.title, 20, true);
        price = Ui.field(this, content, "제안 단가", "", InputType.TYPE_CLASS_NUMBER, R.id.offer_price);
        quantity = Ui.field(this, content, "판매 가능 수량", "", InputType.TYPE_CLASS_NUMBER, R.id.offer_quantity);
        placeButton = Ui.button(this, content,
            pickupAddress.isEmpty() ? "픽업 장소 검색" : pickupAddress, this::openPickupMap);
        dateButton = Ui.button(this, content, "제안 유효기간 · " + Store.date(expiry), () ->
            Ui.pickEndDate(this, expiry, selected -> {
                expiry = selected;
                dateButton.setText("제안 유효기간 · " + Store.date(expiry));
            }));
        submitButton = primary("판매 제안 등록", this::submit);
        if (getLastNonConfigurationInstance() instanceof Submission submission) {
            pending = submission;
            submitButton.setEnabled(false);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (pending != null && !isFinishing()) observe(pending);
    }

    @Override protected void onResume() {
        super.onResume();
        if (product != null && product.remote) watchTradeCatalog(() -> {
            Product latest = store.product(product.id);
            if (latest != null) product = latest;
        });
    }

    @Override public Object onRetainNonConfigurationInstance() { return pending; }

    private void submit() {
        if (pending != null) return;
        int unit = Ui.number(price, 1), count = Ui.number(quantity, product.target);
        if (unit < 0 || count < 0) return;
        if (pickupAddress.isEmpty()) { note("픽업 주소를 직접 입력하세요."); return; }
        if (!product.canReceiveOffer()) { note("이미 제안이 등록되었거나 모집 상태가 변경됐어요."); finish(); return; }
        Offer offer = new Offer(unit, count, PICKUP_LABEL, pickupAddress, expiry, store.account);
        if (!product.remote) {
            product.offers.add(offer);
            finish();
            return;
        }
        String uid = accounts.currentUid();
        if (!canHandleTrade(uid)) return;
        pending = new Submission(uid, trades.submitOffer(product.id, offer));
        submitButton.setEnabled(false);
        observe(pending);
    }

    private void observe(Submission submission) {
        submission.task().addOnCompleteListener(this, result -> {
            if (pending != submission || !canHandleTrade(submission.uid())) return;
            pending = null;
            submitButton.setEnabled(true);
            if (result.isSuccessful()) finish();
            else note("판매 제안을 등록하지 못했습니다. 연결과 모집 상태를 확인해 주세요.");
        });
    }

    private void openPickupMap() {
        Intent intent = new Intent(this, PickupMapActivity.class);
        intent.putExtra(PickupMapActivity.EXTRA_ADDRESS, pickupAddress);
        startActivityForResult(intent, PICKUP_ADDRESS_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != PICKUP_ADDRESS_REQUEST || resultCode != RESULT_OK
            || result == null || placeButton == null) return;
        String address = result.getStringExtra(PickupMapActivity.EXTRA_ADDRESS);
        if (address != null) {
            pickupAddress = address;
            placeButton.setText(pickupAddress);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("manualPickupAddress", pickupAddress);
        state.putLong("expiry", expiry);
        super.onSaveInstanceState(state);
    }
}
