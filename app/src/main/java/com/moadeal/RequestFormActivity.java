package com.moadeal;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.util.Calendar;
import java.util.UUID;
import static com.moadeal.Models.*;

public class RequestFormActivity extends BaseActivity {
    private EditText name, description, price, target;
    private Spinner category;
    private Region region;
    private String photo = "";
    private long deadline;
    private Button regionButton, photoButton, dateButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        region = new Region(getIntent().getStringExtra("city"), getIntent().getStringExtra("district"), getIntent().getStringExtra("neighborhood"));
        if (region.city == null) region = store.demoRegions.get(0).copy();
        deadline = System.currentTimeMillis() + 7L * 86400000;
        if (state != null) {
            photo = state.getString("photo", ""); deadline = state.getLong("deadline");
            region = new Region(state.getString("city", ""), state.getString("district", ""), state.getString("neighborhood", ""));
        }
        title(store.seller() ? "판매 상품 등록" : "구매 요청 등록"); form();
        name = Ui.field(this, content, "상품명", "", InputType.TYPE_CLASS_TEXT, R.id.request_name);
        if (store.seller()) description = Ui.field(this, content, "상품 상세 설명", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, R.id.request_description);
        regionButton = Ui.button(this, content, region.display(), () -> {
            Intent intent = new Intent(this, RegionActivity.class).putExtra("required", true);
            intent.putExtra("city", region.city).putExtra("district", region.district).putExtra("neighborhood", region.neighborhood);
            startActivityForResult(intent, 10);
        });
        regionButton.setContentDescription("모집 지역");
        photoButton = Ui.button(this, content, photo.isEmpty() ? "상품 사진 추가 (선택)" : "상품 사진 변경", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, 11);
        });
        Ui.button(this, content, "상품 사진 제거", () -> { photo = ""; photoButton.setText("상품 사진 추가 (선택)"); });
        category = Ui.spinner(this, content, "카테고리", CATEGORIES, state == null ? 0 : state.getInt("category"));
        category.setId(R.id.request_category);
        price = Ui.field(this, content, store.seller() ? "판매 단가" : "희망 단가", "", InputType.TYPE_CLASS_NUMBER, R.id.request_price);
        target = Ui.field(this, content, "목표 인원", "", InputType.TYPE_CLASS_NUMBER, R.id.request_target);
        dateButton = Ui.button(this, content, "모집 마감일 · " + Store.date(deadline), () -> pickDeadline());
        primary(store.seller() ? "판매 상품 올리기" : "구매 요청 올리기", this::save);
    }

    private void pickDeadline() {
        Calendar calendar = Calendar.getInstance(); calendar.setTimeInMillis(deadline);
        DatePickerDialog dialog = new DatePickerDialog(this, (picker, year, month, day) -> {
            Calendar date = Calendar.getInstance(); date.set(year, month, day, 23, 59, 59);
            deadline = date.getTimeInMillis(); dateButton.setText("모집 마감일 · " + Store.date(deadline));
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis() + 90L * 86400000);
        dialog.show();
    }

    private void save() {
        if (!Ui.required(name) || description != null && !Ui.required(description)) return;
        int unitPrice = Ui.number(price, 1), people = Ui.number(target, 2);
        if (unitPrice < 0 || people < 0) return;
        Product product = new Product(); product.id = "request-" + UUID.randomUUID();
        product.title = Ui.value(name); product.owner = store.account;
        if (!store.seller()) product.members.add(store.account);
        product.region = region.copy(); product.price = unitPrice; product.target = people;
        product.photo = photo; product.category = category.getSelectedItemPosition(); product.deadline = deadline;
        if (description != null) product.description = Ui.value(description);
        store.products.add(0, product);
        Ui.open(this, DetailActivity.class, "product", product.id); finish();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == 10) {
            region = new Region(data.getStringExtra("city"), data.getStringExtra("district"), data.getStringExtra("neighborhood"));
            regionButton.setText(region.display());
        }
        if (request == 11 && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) {}
            photo = uri.toString(); photoButton.setText("상품 사진 변경");
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("city", region.city); state.putString("district", region.district); state.putString("neighborhood", region.neighborhood);
        state.putString("photo", photo); state.putLong("deadline", deadline); state.putInt("category", category.getSelectedItemPosition());
        super.onSaveInstanceState(state);
    }
}
