package com.moadeal;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import static com.moadeal.Models.*;

public class RegionActivity extends BaseActivity {
    private Region region;
    private boolean required;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        region = new Region(getIntent().getStringExtra("city"), getIntent().getStringExtra("district"), getIntent().getStringExtra("neighborhood"));
        if (region.city == null) region = new Region();
        if (region.district == null) region.district = "";
        if (region.neighborhood == null) region.neighborhood = "";
        required = getIntent().getBooleanExtra("required", false);
        if (state != null) region = new Region(state.getString("city", ""), state.getString("district", ""), state.getString("neighborhood", ""));
        render();
    }
    private void render() {
        title("지역 선택"); scroll();
        Ui.text(this, content, "원하는 지역까지만 선택하세요.", 18, true);
        Ui.button(this, content, region.city.isEmpty() ? "전체 시·도" : region.city, () -> choose(0, store.cities()));
        Ui.button(this, content, region.district.isEmpty() ? "전체 시·군·구" : region.district, () -> choose(1, store.districts(region.city)));
        Ui.button(this, content, region.neighborhood.isEmpty() ? "전체 읍·면·동·리" : region.neighborhood, () -> choose(2, store.neighborhoods(region)));
        Ui.text(this, content, region.display(), 17, false);
        primary("상품 보기", () -> {
            if (required && region.city.isEmpty()) { note("시·도를 선택하세요."); return; }
            setResult(RESULT_OK, new Intent().putExtra("city", region.city)
                .putExtra("district", region.district).putExtra("neighborhood", region.neighborhood));
            finish();
        });
    }
    private void choose(int level, List<String> entries) {
        if (level > 0 && region.city.isEmpty()) { note("시·도를 먼저 선택하세요."); return; }
        List<String> all = new ArrayList<>();
        all.add(new String[]{"전체 시·도", "전체 시·군·구", "전체 읍·면·동·리"}[level]);
        all.addAll(entries);
        LinearLayout container = new LinearLayout(this); container.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this); search.setHint("지역 검색"); search.setSingleLine(true); container.addView(search);
        ListView list = new ListView(this); container.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(this, 360)));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(all));
        list.setAdapter(adapter);
        Ui.watch(search, value -> {
            List<String> filtered = new ArrayList<>();
            for (String entry : all) if (entry.contains(value)) filtered.add(entry);
            adapter.clear(); adapter.addAll(filtered);
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("지역 선택").setView(container).setNegativeButton("닫기", null).create();
        list.setOnItemClickListener((parent, view, index, id) -> {
            String selected = adapter.getItem(index);
            if (selected.equals(all.get(0))) selected = "";
            if (level == 0) { region.city = selected; region.district = ""; region.neighborhood = ""; }
            if (level == 1) { region.district = selected; region.neighborhood = ""; }
            if (level == 2) region.neighborhood = selected;
            dialog.dismiss(); render();
        });
        dialog.show();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("city", region.city); state.putString("district", region.district); state.putString("neighborhood", region.neighborhood);
        super.onSaveInstanceState(state);
    }
}
