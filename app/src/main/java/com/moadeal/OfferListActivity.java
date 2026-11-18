package com.moadeal;

import android.os.Bundle;
import android.widget.LinearLayout;
import com.google.android.gms.tasks.Task;
import java.util.ArrayList;
import java.util.Comparator;
import static com.moadeal.Models.*;

public class OfferListActivity extends BaseActivity {
    private String productId;
    private Selection pending;

    private record Selection(String uid, Task<Void> task) {}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        productId = getIntent().getStringExtra("product");
        if (getLastNonConfigurationInstance() instanceof Selection selection) pending = selection;
        if (productId == null || trades == null && store.product(productId) == null) finish();
    }

    @Override protected void onStart() {
        super.onStart();
        if (pending != null && !isFinishing()) observe(pending);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        render();
        Product product = store.product(productId);
        if (product == null || product.remote) watchTradeCatalog(() -> renderKeepingScroll(this::render));
    }

    @Override public Object onRetainNonConfigurationInstance() { return pending; }

    private void render() {
        Product product = store.product(productId);
        title("판매 제안"); scroll();
        if (product == null) {
            Ui.text(this, content, "판매 제안을 불러오는 중이에요.", 16, false);
            return;
        }
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
                var select = Ui.button(this, card, "이 제안 선택", () -> select(product, offer));
                select.setEnabled(pending == null && offer.expiry > System.currentTimeMillis());
            }
        }
    }

    private void select(Product product, Offer offer) {
        if (pending != null) return;
        if (product.selected != null || offer.expiry <= System.currentTimeMillis()) {
            note("선택할 수 없는 제안입니다.");
            return;
        }
        if (!product.remote) {
            product.selected = offer;
            finish();
            return;
        }
        String uid = accounts.currentUid();
        if (!canHandleTrade(uid)) return;
        pending = new Selection(uid, trades.selectOffer(product.id));
        renderKeepingScroll(this::render);
        observe(pending);
    }

    private void observe(Selection selection) {
        selection.task().addOnCompleteListener(this, result -> {
            if (pending != selection || !canHandleTrade(selection.uid())) return;
            pending = null;
            if (result.isSuccessful()) finish();
            else {
                note("제안을 선택하지 못했습니다. 연결과 제안 상태를 확인해 주세요.");
                renderKeepingScroll(this::render);
            }
        });
    }
}
