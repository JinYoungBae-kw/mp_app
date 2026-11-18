package com.moadeal;

import android.os.Bundle;
import android.widget.*;
import static com.moadeal.Models.*;

public class HistoryActivity extends BaseActivity {
    private String section;
    private boolean complete;
    private LinearLayout rows;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        section = getIntent().getStringExtra("section");
        if (state != null) complete = state.getBoolean("complete");
        title(section);
    }
    @Override protected void onResume() {
        super.onResume();
        render();
        watchTradeCatalog(this::renderRows);
    }
    private void render() {
        scroll();
        if ("판매관리".equals(section)) {
            RadioGroup tabs = new RadioGroup(this); tabs.setOrientation(RadioGroup.HORIZONTAL);
            RadioButton active = new RadioButton(this); active.setText("판매진행중"); active.setId(android.view.View.generateViewId()); active.setChecked(!complete);
            RadioButton done = new RadioButton(this); done.setText("판매완료"); done.setId(android.view.View.generateViewId()); done.setChecked(complete);
            tabs.addView(active, new RadioGroup.LayoutParams(0, -2, 1)); tabs.addView(done, new RadioGroup.LayoutParams(0, -2, 1)); content.addView(tabs);
            active.setOnClickListener(view -> { complete = false; renderRows(); });
            done.setOnClickListener(view -> { complete = true; renderRows(); });
        }
        rows = Ui.column(this, content); renderRows();
    }
    private void renderRows() {
        rows.removeAllViews();
        for (Product p : store.products) {
            switch (section) {
                case "판매관리":
                    if (!store.seller()) break;
                    if (p.sellerListing() && p.owner.equals(store.account) && (complete ? p.completed() : p.recruiting())) {
                        Ui.productRow(this, rows, p, p.joined() + "/" + p.target + "명 · 판매 희망 수량 " + p.target + "개\n개당 " + Store.won(p.price));
                    }
                    for (Offer offer : p.offers) {
                        if (store.account.equals(offer.seller) && (complete ? p.selected == offer : p.selected == null && offer.expiry > System.currentTimeMillis())) {
                            Ui.productRow(this, rows, p, "판매 희망 수량 " + offer.quantity + "개\n개당 " + Store.won(offer.price));
                        }
                    }
                    break;
                case "요청내역": {
                    if (!p.members.contains(store.account)) break;
                    boolean recruiting = p.recruiting();
                    if (recruiting || p.awaitingOfferSelection()) {
                        String status = recruiting
                            ? (p.owner.equals(store.account) ? "내가 모집 중" : "참여 중")
                            : p.stageLabel();
                        Ui.productRow(this, rows, p, status + " · " + p.joined() + "/" + p.target + "명\n희망 단가 " + Store.won(p.price));
                    }
                    break;
                }
                case "구매내역":
                    if (p.members.contains(store.account) && (p.completed() || p.selected != null)) Ui.productRow(this, rows, p,
                        p.joined() + "명 공동구매 성사\n개당 " + Store.won(p.completed() ? p.price : p.selected.price));
                    break;
                case "관심 내역":
                    if (store.favorite(p)) Ui.productRow(this, rows, p, p.stageLabel() + "\n개당 " + Store.won(p.price));
                    break;
            }
        }
        if (!complete && store.seller() && "판매관리".equals(section)) {
            for (Product p : store.products) {
                if (p.canReceiveOffer()) Ui.productRow(this, rows, p,
                    p.stageLabel() + " · " + p.joined() + "/" + p.target + "명\n희망 단가 " + Store.won(p.price));
            }
        }
        if (rows.getChildCount() == 0) Ui.text(this, rows, switch (section) {
            case "요청내역" -> "아직 모집 중인 공동구매가 없어요.";
            case "구매내역" -> "성사된 공동구매가 없어요.";
            case "관심 내역" -> "저장한 관심상품이 없어요.";
            default -> complete ? "판매가 완료된 상품이 없어요." : "판매 진행 중인 상품이 없어요.";
        }, 16, false);
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("complete", complete); super.onSaveInstanceState(state); }
}
