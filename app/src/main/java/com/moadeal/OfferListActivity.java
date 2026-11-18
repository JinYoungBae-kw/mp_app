package com.moadeal;

import android.os.Bundle;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Comparator;
import static com.moadeal.Models.*;

public class OfferListActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Product product = store.product(getIntent().getStringExtra("product"));
        if (product == null) { finish(); return; }
        title("판매 제안"); scroll();
        Ui.text(this, content, product.title, 24, true);
        ArrayList<Offer> offers = new ArrayList<>(product.offers);
        offers.sort(Comparator.comparingInt(offer -> offer.price));
        if (offers.isEmpty()) Ui.text(this, content, "아직 등록된 제안이 없습니다.", 16, false);
        for (Offer offer : offers) {
            LinearLayout card = Ui.card(this, content);
            if (product.selected == offer) Ui.text(this, card, "선택한 제안", 16, true);
            Ui.text(this, card, Store.won(offer.price), 25, true);
            Ui.text(this, card, "수량 " + offer.quantity + "개 · " + Store.date(offer.expiry) + "까지", 15, false);
            Ui.text(this, card, offer.place + "\n" + offer.address, 16, false);
            if (product.owner.equals(store.account) && product.selected == null) {
                var select = Ui.button(this, card, "이 제안 선택", () -> {
                    if (product.selected != null || offer.expiry <= System.currentTimeMillis()) { note("선택할 수 없는 제안입니다."); return; }
                    product.selected = offer; finish();
                });
                select.setEnabled(offer.expiry > System.currentTimeMillis());
            }
        }
    }
}
