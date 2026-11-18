package com.moadeal;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.util.List;
import static com.moadeal.Models.*;

public class HomeActivity extends BaseActivity {
    private String tab = "홈", query = "";
    private int category = -1, stage = -1;
    private Region region = new Region();
    private LinearLayout results;
    private LinearLayout toolbarBrand;
    private final CommunityPanel.State communityState = new CommunityPanel.State();
    private int scrollY;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            tab = state.getString("tab", "홈"); query = state.getString("query", "");
            category = state.getInt("category", -1); stage = state.getInt("stage", -1);
            region = new Region(state.getString("city", ""), state.getString("district", ""), state.getString("neighborhood", ""));
            store.fillRegion(region);
            scrollY = state.getInt("scrollY");
            communityState.query = state.getString("communityQuery", "");
            communityState.category = state.getString("communityCategory", "전체");
            communityState.joinedOnly = state.getBoolean("communityJoined");
        }
        findViewById(R.id.back).setVisibility(View.GONE);
        action("계정 전환", this::switchAccount);
    }

    @Override protected void onResume() {
        super.onResume(); render();
        ScrollView scroll = findViewById(R.id.page_scroll);
        if (scroll != null) scroll.post(() -> scroll.scrollTo(0, scrollY));
    }
    @Override protected void onPause() {
        ScrollView scroll = findViewById(R.id.page_scroll);
        if (scroll != null) scrollY = scroll.getScrollY();
        super.onPause();
    }

    private void render() {
        title(tab.equals("홈") ? "모아딜" : tab);
        scroll();
        brand();
        navigation();
        if (tab.equals("커뮤니티")) { new CommunityPanel(this, content, communityState).render(); return; }
        if (tab.equals("내 계정")) { account(); return; }
        if (store.seller()) { sellerHome(); return; }
        if (tab.equals("관심상품")) { results = Ui.column(this, content); renderProducts(); return; }
        EditText search = new EditText(this);
        search.setHint("상품명이나 지역으로 요청 검색"); search.setSingleLine(true); search.setText(query);
        search.setContentDescription("상품 검색");
        content.addView(search, new android.view.ViewGroup.LayoutParams(-1, Ui.dp(this, 52)));
        Ui.watch(search, value -> { query = value; renderProducts(); });
        RadioGroup stages = new RadioGroup(this);
        stages.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"전체", "모집 중", "제안 접수", "선택 완료"};
        for (int i = 0; i < labels.length; i++) {
            final int chosen = i - 1;
            RadioButton button = new RadioButton(this); button.setText(labels[i]); button.setTextSize(12);
            button.setId(View.generateViewId()); button.setChecked(stage == chosen);
            stages.addView(button, new RadioGroup.LayoutParams(0, -2, 1));
            button.setOnClickListener(view -> { stage = chosen; renderProducts(); });
        }
        content.addView(stages);
        LinearLayout shortcuts = Ui.row(this, content);
        String[] full = {"출석체크", "최저가도전", "랭킹", "마감임박", "오늘의 특가"};
        for (int i = 0; i < full.length; i++) {
            final int index = i;
            Button button = Ui.button(this, shortcuts, new String[]{"출석\n체크", "최저가\n도전", "랭킹", "마감\n임박", "오늘의\n특가"}[i], () -> {
                if (index == 0) Ui.open(this, AttendanceActivity.class, null, null);
                else Ui.open(this, CollectionActivity.class, "collection", full[index]);
            });
            button.setContentDescription(full[i]); button.setTextSize(12); button.setPadding(0, 0, 0, 0);
            button.setMinWidth(0); Ui.weighted(button, 1);
        }
        Ui.text(this, content, "동네 구매 요청", 22, true);
        Ui.button(this, content, "⌖ " + region.display(), () -> {
            Intent intent = new Intent(this, RegionActivity.class);
            intent.putExtra("city", region.city).putExtra("district", region.district).putExtra("neighborhood", region.neighborhood);
            startActivityForResult(intent, 10);
        });
        RadioGroup categories = new RadioGroup(this);
        categories.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = -1; i < 3; i++) {
            final int chosen = i;
            RadioButton button = new RadioButton(this);
            button.setText(i < 0 ? "전체 종목" : CATEGORIES[i]); button.setTextSize(13);
            button.setId(View.generateViewId()); button.setChecked(category == chosen);
            categories.addView(button, new RadioGroup.LayoutParams(0, -2, 1));
            button.setOnClickListener(view -> { category = chosen; renderProducts(); });
        }
        content.addView(categories);
        results = Ui.column(this, content);
        renderProducts();
    }

    private void navigation() {
        LinearLayout nav = findViewById(R.id.navigation);
        nav.setVisibility(View.VISIBLE); nav.removeAllViews();
        String[] labels = store.seller() ? new String[]{"홈", "커뮤니티", "내 계정"}
            : new String[]{"홈", "요청 등록", "관심상품", "커뮤니티", "내 계정"};
        for (String label : labels) {
            Button button = Ui.button(this, nav, label, () -> {
                if (label.equals("요청 등록")) { register(); return; }
                tab = label;
                scrollY = 0;
                if (label.equals("홈")) { query = ""; stage = -1; }
                render();
            });
            button.setTextSize(11); button.setMinWidth(0); button.setPadding(0, 0, 0, 0);
            int icon = switch (label) {
                case "홈" -> R.drawable.ic_home;
                case "요청 등록" -> R.drawable.ic_add;
                case "관심상품" -> R.drawable.ic_heart;
                case "커뮤니티" -> R.drawable.ic_groups;
                default -> R.drawable.ic_person;
            };
            android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
            drawable.setTint(label.equals(tab) ? Ui.ORANGE : Ui.BROWN);
            drawable.setBounds(0, 0, Ui.dp(this, 23), Ui.dp(this, 23));
            button.setCompoundDrawables(null, drawable, null, null);
            button.setCompoundDrawablePadding(Ui.dp(this, 3));
            button.setMinHeight(Ui.dp(this, 60));
            button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            button.setStateListAnimator(null);
            Ui.weighted(button, 1);
            if (label.equals(tab)) button.setTextColor(Ui.ORANGE);
        }
    }

    private void brand() {
        LinearLayout toolbar = findViewById(R.id.toolbar);
        TextView heading = findViewById(R.id.page_title);
        if (toolbarBrand != null) toolbar.removeView(toolbarBrand);
        heading.setVisibility(tab.equals("홈") ? View.GONE : View.VISIBLE);
        if (!tab.equals("홈")) return;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toolbarBrand = row;
        toolbar.addView(row, 1, new LinearLayout.LayoutParams(0, -1, 1));
        BrandLogoView logo = new BrandLogoView(this, null);
        row.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 38)));
        ImageView mascot = new ImageView(this);
        row.addView(mascot, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 44)));
        Ui.image(this, mascot, "brand/mascot.png");
        mascot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mascot.setContentDescription("모아딜 다람쥐");
    }

    private void renderProducts() {
        if (results == null) return;
        results.removeAllViews();
        boolean favorites = tab.equals("관심상품");
        List<Product> visible = store.visible(region, category, stage, query, favorites);
        if (!favorites && query.isEmpty() && stage < 0) {
            for (Product product : visible) {
                if (!product.recruiting()) continue;
                Ui.productRow(this, results, product, "함께 살 이웃을 모집 중\n요청 보기  →");
                break;
            }
        }
        Ui.text(this, results, favorites ? "저장한 상품 " + visible.size() + "개"
            : query.isEmpty() ? "상품 " + visible.size() + "개" : "검색 결과 " + visible.size() + "건", 16, true);
        if (visible.isEmpty()) Ui.text(this, results, favorites ? "상품의 하트를 눌러 담아보세요." : "조건에 맞는 구매 요청이 없습니다.", 16, false);
        Ui.grid(this, results, visible, null);
    }

    private void sellerHome() {
        Ui.text(this, content, "내 판매 상품", 24, true);
        Ui.primary(Ui.button(this, content, "상품 등록", this::register));
        for (Product p : store.products) if (p.sellerListing()) {
            Ui.productRow(this, content, p, Store.won(p.price) + " · " + p.stageLabel()
                + "\n" + p.joined() + "/" + p.target + "명 · 최근 7일 조회 " + p.views() + "회");
        }
    }

    private void register() {
        Intent intent = new Intent(this, RequestFormActivity.class);
        Region initial = region.city.isEmpty() ? store.demoRegions.get(0) : region;
        intent.putExtra("city", initial.city).putExtra("district", initial.district).putExtra("neighborhood", initial.neighborhood);
        startActivity(intent);
    }

    private void account() {
        content.setBackgroundColor(Ui.CREAM);
        LinearLayout profile = Ui.card(this, content);
        LinearLayout profileRow = Ui.row(this, profile);
        ImageView person = new ImageView(this); person.setImageResource(R.drawable.ic_person);
        profileRow.addView(person, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout name = Ui.column(this, profileRow); Ui.weighted(name, 1);
        Ui.text(this, name, Store.accountId(store.account) + "   ›", 19, true);
        Ui.text(this, name, store.profileName().isEmpty() ? store.seller() ? "판매자" : "구매자" : store.profileName(), 14, false);
        profile.setOnClickListener(view -> Ui.open(this, ProfileActivity.class, null, null));
        LinearLayout transactions = Ui.card(this, content);
        Ui.text(this, transactions, "나의 거래", 20, true);
        String[] labels = store.seller() ? new String[]{"판매관리"} : new String[]{"요청내역", "판매 제안 작성", "구매내역", "관심 내역"};
        for (String label : labels) Ui.button(this, transactions, label + "   ›", () -> {
            if (label.equals("판매 제안 작성")) register();
            else Ui.open(this, HistoryActivity.class, "section", label);
        });
        LinearLayout attendance = Ui.card(this, content);
        Ui.button(this, attendance, "숲속 출석체크   ›", () -> Ui.open(this, AttendanceActivity.class, null, null));
        Ui.photo(this, attendance, "brand/forest-home.png", 145);
        int count = store.attendance().size();
        Ui.text(this, attendance, count == 0 ? "첫 출석을 시작해요" : count + "일차 출석", 20, true);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgress(Math.min(100, count * 10)); attendance.addView(progress);
        boolean checked = store.attendance().contains(Store.today());
        Button check = Ui.button(this, attendance, checked ? "오늘 출첵 완료" : count >= 10 ? "출석 목표 달성" : "출첵!", () -> {
            store.checkIn(); render();
        });
        check.setEnabled(!checked && count < 10);
    }

    @Override void onFavoriteChanged() { if (tab.equals("관심상품")) renderProducts(); }
    @Override void onDayChanged() { render(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10 && result == RESULT_OK && data != null) {
            region = new Region(data.getStringExtra("city"), data.getStringExtra("district"), data.getStringExtra("neighborhood"));
            scrollY = 0; store.fillRegion(region); render();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("tab", tab); state.putString("query", query);
        state.putInt("category", category); state.putInt("stage", stage);
        state.putString("city", region.city); state.putString("district", region.district); state.putString("neighborhood", region.neighborhood);
        state.putInt("scrollY", scrollY);
        state.putString("communityQuery", communityState.query);
        state.putString("communityCategory", communityState.category);
        state.putBoolean("communityJoined", communityState.joinedOnly);
        super.onSaveInstanceState(state);
    }
}
