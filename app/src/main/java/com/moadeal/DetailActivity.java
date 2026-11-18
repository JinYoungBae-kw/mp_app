package com.moadeal;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.*;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.Collections;
import java.util.List;
import static com.moadeal.Models.*;

public class DetailActivity extends BaseActivity {
    private String productId;
    private Product product;
    private ListenerRegistration questionsListener;
    private List<Question> sharedQuestions = Collections.emptyList();
    private int questionSubscription;
    private boolean active, viewRecorded;
    private Task<Void> participation;
    private AlertDialog questionDialog;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        productId = getIntent().getStringExtra("product");
        if (state != null) viewRecorded = state.getBoolean("viewRecorded");
        if (productId == null || trades == null && store.product(productId) == null) {
            note("앱을 다시 실행하여 예시 상품을 확인하세요.");
            finish();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        active = true;
        refreshProduct();
        if (product == null || product.remote) watchTradeCatalog(() -> renderKeepingScroll(this::refreshProduct));
    }

    @Override protected void onPause() {
        active = false;
        if (questionsListener != null) {
            questionSubscription++;
            questionsListener.remove();
            questionsListener = null;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (questionDialog != null) questionDialog.dismiss();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("viewRecorded", viewRecorded);
        super.onSaveInstanceState(state);
    }

    private void refreshProduct() {
        product = store.product(productId);
        if (product == null) {
            title("상품 상세"); scroll();
            Ui.text(this, content, "상품을 불러오는 중이에요.", 16, false);
            return;
        }
        if (product.remote && canHandleTrade(accounts.currentUid())) {
            if (questionsListener == null) watchQuestions();
            if (!viewRecorded && product.sellerListing() && !store.seller()) {
                viewRecorded = true;
                trades.recordView(product.id, product.owner.substring("firebase:".length()));
            }
        }
        render();
    }

    private void watchQuestions() {
        String uid = accounts.currentUid();
        int subscription = ++questionSubscription;
        questionsListener = trades.listenQuestions(productId, questions -> {
            if (!active || subscription != questionSubscription || !canHandleTrade(uid)) return;
            sharedQuestions = questions;
            if (product != null) renderKeepingScroll(this::render);
        }, error -> {
            if (active && subscription == questionSubscription && canHandleTrade(uid)) {
                note("상품 질의응답을 불러오지 못했습니다.");
            }
        });
    }

    private void render() {
        title(product.sellerListing() ? "판매 상품 상세" : "구매 요청 상세"); scroll();
        if (!store.seller()) action(store.favorite(product) ? "♥ 관심" : "♡ 관심",
            () -> Ui.toggleFavorite(this, product, () -> renderKeepingScroll(this::render)));
        Ui.productPhoto(this, content, product, 210).setContentDescription(product.title + " 사진");
        Ui.text(this, content, product.stageLabel(), 14, true).setTextColor(Ui.BROWN);
        Ui.text(this, content, product.title, 27, true);
        if (!product.description.isEmpty()) Ui.text(this, content, product.description, 16, false);
        Ui.text(this, content, product.region.label() + " · 모집 마감 " + Store.date(product.deadline), 14, false);
        Integer special = Store.special(product);
        if (special != null) Ui.text(this, content, "오늘의 특가 " + Store.won(special), 17, true);
        Ui.text(this, content, (product.sellerListing() ? "판매 단가  " : "희망 단가  ") + Store.won(product.price), 23, true).setTextColor(Ui.BROWN);
        LinearLayout progressCard = Ui.card(this, content);
        progressCard.setBackgroundColor(Ui.CREAM);
        Ui.text(this, progressCard, "참여 " + product.joined() + " / " + product.target + "명", 16, true);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(product.target); progress.setProgress(product.joined()); progressCard.addView(progress);
        if (product.selected != null) offerCard(content, product.selected, true);
        if (!product.sellerListing()) {
            if (product.recruiting()) Ui.text(this, content, product.owner.equals(store.account)
                ? "개설자는 첫 참여자로 포함됩니다." : "목표 인원이 모이면 판매 제안을 받을 수 있습니다.", 15, false);
            else if (product.awaitingOfferSelection()) Ui.text(this, content, product.offers.isEmpty() ? "판매자의 제안을 기다리고 있습니다." : "받은 제안 " + product.offers.size() + "건", 16, true);
        }
        if (store.seller() && product.sellerListing() && product.owner.equals(store.account)) {
            Ui.text(this, content, "최근 7일 조회수", 20, true);
            Ui.text(this, content, product.views() + (product.remote ? "회" : "회 · 예시 통계"), 16, false);
            ViewsChart chart = new ViewsChart(this, null);
            chart.setProduct(product);
            content.addView(chart, new LinearLayout.LayoutParams(-1, Ui.dp(this, 150)));
        }
        questions();
        boolean owner = product.owner.equals(store.account), joined = product.members.contains(store.account);
        if (!store.seller() && !owner && product.recruiting()) {
            Button join = primary(joined ? "참여 취소" : "참여하기", () -> changeParticipation(!joined));
            join.setEnabled(participation == null);
        }
        if (store.seller() && !product.sellerListing() && product.canReceiveOffer()) {
            primary("판매 제안 작성", () -> Ui.open(this, OfferFormActivity.class, "product", product.id));
        }
        if (!product.sellerListing() && product.stage() != 0) {
            Ui.button(this, footer, "제안 보기", () -> Ui.open(this, OfferListActivity.class, "product", product.id));
        }
    }

    private void changeParticipation(boolean join) {
        if (participation != null) return;
        if (!product.remote) {
            if (join) product.members.add(store.account);
            else product.members.remove(store.account);
            render();
            return;
        }
        String uid = accounts.currentUid();
        if (!canHandleTrade(uid)) return;
        participation = trades.setParticipation(product.id, join);
        renderKeepingScroll(this::render);
        participation.addOnCompleteListener(result -> {
            participation = null;
            if (!active || !canHandleTrade(uid)) return;
            if (!result.isSuccessful()) note("참여 상태를 변경하지 못했습니다. 연결과 모집 상태를 확인해 주세요.");
            renderKeepingScroll(this::refreshProduct);
        });
    }

    void offerCard(LinearLayout parent, Offer offer, boolean selected) {
        LinearLayout card = Ui.card(this, parent);
        if (selected) Ui.text(this, card, "선택한 제안", 14, true);
        Ui.text(this, card, Store.won(offer.price), 24, true).setTextColor(Ui.BROWN);
        Ui.text(this, card, "수량 " + offer.quantity + "개 · " + Store.date(offer.expiry) + "까지", 15, false);
        Ui.text(this, card, offer.place + "\n" + offer.address, 16, false);
    }

    private void questions() {
        Ui.text(this, content, "상품 질의응답", 22, true);
        List<Question> questions = product.remote ? sharedQuestions : product.questions;
        if (questions.isEmpty()) Ui.text(this, content, "아직 등록된 질문이 없어요.", 15, false);
        boolean canAnswer = store.seller() && (product.sellerListing() && product.owner.equals(store.account)
            || product.offers.stream().anyMatch(offer -> store.account.equals(offer.seller)));
        for (Question question : questions) {
            LinearLayout card = Ui.card(this, content); card.setBackgroundColor(Ui.CREAM);
            Ui.text(this, card, "Q. " + question.text, 16, true);
            if (question.answer != null) Ui.text(this, card, "A. " + question.answer, 15, false);
            else if (canAnswer) Ui.button(this, card, "답변 달기", () -> write(question));
            else Ui.text(this, card, "답변 대기 중", 14, false);
        }
        if (!store.seller()) Ui.button(this, content, "질문 남기기", () -> write(null));
    }

    private void write(Question question) {
        Product item = product;
        EditText input = new EditText(this); input.setHint("내용을 입력하세요"); input.setMinLines(2);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(240)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(question == null ? "질문 남기기" : "답변 달기")
            .setView(input).setNegativeButton("취소", null).setPositiveButton("등록", null).create();
        questionDialog = dialog;
        dialog.setOnDismissListener(ignored -> questionDialog = null);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!Ui.required(input)) return;
            String response = Ui.value(input);
            if (!item.remote) {
                if (question == null) item.questions.add(new Question(response, null));
                else question.answer = response;
                dialog.dismiss(); render();
                return;
            }
            String uid = accounts.currentUid();
            if (!canHandleTrade(uid)) return;
            Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            submit.setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.setCancelable(false);
            Task<Void> save = question == null ? trades.askQuestion(item.id, response)
                : trades.answerQuestion(item.id, question.id, response);
            save.addOnCompleteListener(result -> {
                if (!canHandleTrade(uid) || !dialog.isShowing()) return;
                if (result.isSuccessful()) dialog.dismiss();
                else {
                    submit.setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    dialog.setCancelable(true);
                    note("내용을 등록하지 못했습니다. 다시 시도해 주세요.");
                }
            });
        }));
        dialog.show();
    }
    @Override void onDayChanged() { if (product != null) render(); }
}
