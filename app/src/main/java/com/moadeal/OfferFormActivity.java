package com.moadeal;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.util.Calendar;
import static com.moadeal.Models.*;

public class OfferFormActivity extends BaseActivity {
    private Product product;
    private EditText price, quantity;
    private int place = -1;
    private long expiry = System.currentTimeMillis() + 3L * 86400000;
    private Button placeButton, dateButton;
    static final String[] PLACES = {"월계동 과일가게", "광운대 앞 베이커리"};
    static final String[] ADDRESSES = {"서울 노원구 월계동", "서울 노원구 광운로"};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        product = store.product(getIntent().getStringExtra("product"));
        if (product == null || !store.seller()) { finish(); return; }
        if (state != null) { place = state.getInt("place", -1); expiry = state.getLong("expiry"); }
        title("판매 제안 작성"); form();
        Ui.text(this, content, product.title, 20, true);
        price = Ui.field(this, content, "제안 단가", "", InputType.TYPE_CLASS_NUMBER, R.id.offer_price);
        quantity = Ui.field(this, content, "판매 가능 수량", "", InputType.TYPE_CLASS_NUMBER, R.id.offer_quantity);
        placeButton = Ui.button(this, content, place < 0 ? "픽업 장소 검색" : PLACES[place], this::searchPlace);
        dateButton = Ui.button(this, content, "제안 유효기간 · " + Store.date(expiry), () -> {
            Calendar calendar = Calendar.getInstance(); calendar.setTimeInMillis(expiry);
            DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, day) -> {
                Calendar chosen = Calendar.getInstance(); chosen.set(year, month, day, 23, 59, 59);
                expiry = chosen.getTimeInMillis(); dateButton.setText("제안 유효기간 · " + Store.date(expiry));
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
            picker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
            picker.getDatePicker().setMaxDate(System.currentTimeMillis() + 90L * 86400000);
            picker.show();
        });
        primary("판매 제안 등록", () -> {
            int unit = Ui.number(price, 1), count = Ui.number(quantity, product.target);
            if (unit < 0 || count < 0) return;
            if (place < 0) { note("픽업 장소를 선택하세요."); return; }
            if (product.stage() != 1 || !product.offers.isEmpty()) { note("이미 제안이 등록되었거나 모집 상태가 변경됐어요."); finish(); return; }
            product.offers.add(new Offer(unit, count, PLACES[place], ADDRESSES[place], expiry, store.account));
            finish();
        });
    }

    private void searchPlace() {
        LinearLayout container = new LinearLayout(this); container.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this); search.setHint("장소명 또는 주소"); container.addView(search);
        LinearLayout list = Ui.column(this, container);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("픽업 장소 검색").setView(container).setNegativeButton("닫기", null).create();
        Runnable render = () -> {
            list.removeAllViews(); Ui.text(this, list, "예시 장소", 14, false);
            for (int i = 0; i < PLACES.length; i++) {
                final int chosen = i;
                if (!(PLACES[i] + ADDRESSES[i]).contains(Ui.value(search))) continue;
                Ui.button(this, list, PLACES[i] + "\n" + ADDRESSES[i], () -> {
                    place = chosen; placeButton.setText(PLACES[place]); dialog.dismiss();
                });
            }
        };
        Ui.watch(search, ignored -> render.run()); render.run(); dialog.show();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("place", place); state.putLong("expiry", expiry); super.onSaveInstanceState(state);
    }
}
