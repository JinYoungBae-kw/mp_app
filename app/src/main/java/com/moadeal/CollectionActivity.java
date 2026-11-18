package com.moadeal;

import android.app.AlertDialog;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static com.moadeal.Models.*;

public class CollectionActivity extends BaseActivity {
    private String collection;
    private int category = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        collection = getIntent().getStringExtra("collection");
        if (state != null) category = state.getInt("category", -1);
        title(collection);
        if ("최저가도전".equals(collection)) action("필터", () -> {
            String[] options = {"전체 카테고리", "식품", "미용", "생필품"};
            new AlertDialog.Builder(this).setTitle("필터").setSingleChoiceItems(options, category + 1, (dialog, which) -> {
                category = which - 1; dialog.dismiss(); render();
            }).show();
        });
    }
    @Override protected void onResume() {
        super.onResume();
        render();
        watchTradeCatalog(() -> renderKeepingScroll(this::render));
    }

    private void render() {
        scroll();
        List<Product> matches = new ArrayList<>();
        for (Product p : store.products) {
            if (p.id.startsWith("regional|")) continue;
            boolean matchesCollection = switch (collection) {
                case "최저가도전" -> p.recruiting() && p.comparisonPrice > 0 && (category < 0 || category == p.category);
                case "랭킹" -> store.likesToday(p) > 0;
                case "마감임박" -> p.recruiting() && p.remaining() > 0 && p.remaining() <= 2;
                case "오늘의 특가" -> Store.special(p) != null;
                default -> false;
            };
            if (matchesCollection) matches.add(p);
        }
        String heading;
        switch (collection) {
            case "최저가도전":
                matches.sort(Comparator.comparingDouble(Product::savings).reversed().thenComparingInt(Product::remaining));
                heading = (category < 0 ? "전체" : CATEGORIES[category]) + " · 희망가 절감률 기준"; break;
            case "랭킹":
                matches.sort(Comparator.comparingInt((Product p) -> store.likesToday(p)).reversed().thenComparing(p -> p.title));
                heading = "오늘 받은 관심순"; break;
            case "마감임박":
                matches.sort(Comparator.comparingInt(Product::remaining).thenComparingLong(p -> p.deadline));
                heading = "참여 자리 1~2개 남은 요청"; break;
            default:
                heading = Store.today() + " · 한국 시간 · 특가 " + matches.size() + "개"; break;
        }
        Ui.text(this, content, heading, 17, true);
        if (matches.isEmpty()) Ui.text(this, content, "조건에 맞는 상품이 없습니다.", 16, false);
        Ui.grid(this, content, matches, p -> switch (collection) {
            case "최저가도전" -> "비교가 대비 " + Math.round(p.savings() * 100) + "% 낮음";
            case "랭킹" -> "오늘 관심 " + store.likesToday(p) + "개";
            case "마감임박" -> p.remaining() + "자리 남음";
            default -> "오늘만 " + Store.won(Store.special(p));
        });
    }
    @Override void onDayChanged() { render(); }
    @Override void onFavoriteChanged() { if ("랭킹".equals(collection)) render(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("category", category); super.onSaveInstanceState(state); }
}
