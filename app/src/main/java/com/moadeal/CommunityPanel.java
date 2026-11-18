package com.moadeal;

import android.widget.*;
import android.view.View;
import java.util.ArrayList;
import static com.moadeal.Models.*;

final class CommunityPanel {
    private final BaseActivity activity;
    private final LinearLayout parent;
    private LinearLayout results;
    static class State {
        String query = "", category = "전체";
        boolean joinedOnly;
    }
    private final State state;
    static final String[] CATEGORIES = {"식품", "미용", "생필품", "동네가게"};

    CommunityPanel(BaseActivity activity, LinearLayout parent) { this(activity, parent, new State()); }
    CommunityPanel(BaseActivity activity, LinearLayout parent, State state) {
        this.activity = activity; this.parent = parent; this.state = state;
    }

    void render() {
        parent.removeAllViews();
        EditText search = new EditText(activity);
        search.setHint("모임 이름이나 지역 검색"); search.setSingleLine(true); search.setText(state.query);
        parent.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 52)));
        Ui.watch(search, value -> { state.query = value; renderRooms(); });
        RadioGroup tabs = new RadioGroup(activity); tabs.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            boolean joined = i == 1;
            RadioButton button = new RadioButton(activity);
            button.setId(View.generateViewId()); button.setText(joined ? "참여 중인 채팅" : "모임 둘러보기");
            button.setChecked(state.joinedOnly == joined);
            tabs.addView(button, new RadioGroup.LayoutParams(0, -2, 1));
            button.setOnClickListener(view -> { state.joinedOnly = joined; renderRooms(); });
        }
        parent.addView(tabs);
        String[] categories = {"전체", "식품", "미용", "생필품", "동네가게"};
        Spinner types = Ui.spinner(activity, parent, "모임 카테고리", categories, java.util.Arrays.asList(categories).indexOf(state.category));
        types.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> adapter, View view, int position, long id) {
                state.category = adapter.getItemAtPosition(position).toString(); renderRooms();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        results = Ui.column(activity, parent);
        Ui.primary(Ui.button(activity, parent, "＋ 모임 만들기", () -> Ui.open(activity, RoomFormActivity.class, null, null)));
        renderRooms();
    }

    private void renderRooms() {
        if (results == null) return;
        results.removeAllViews();
        for (Room room : activity.store.rooms) {
            if (state.joinedOnly && !room.members.contains(activity.store.account)) continue;
            if (!state.category.equals("전체") && !state.category.equals(room.category)) continue;
            if (!(room.name + room.area).contains(state.query)) continue;
            View row = activity.getLayoutInflater().inflate(R.layout.item_row, results, false);
            ((TextView) row.findViewById(R.id.row_title)).setText(room.name);
            ((TextView) row.findViewById(R.id.row_description)).setText(room.description + "\n" + room.area + " · " + (room.sampleMembers + room.members.size()) + "명");
            Ui.image(activity, row.findViewById(R.id.row_photo), room.photo);
            row.setOnClickListener(view -> Ui.open(activity, ChatActivity.class, "room", room.id));
            results.addView(row);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = Ui.dp(activity, 12); row.setLayoutParams(params);
        }
        if (results.getChildCount() == 0) Ui.text(activity, results, state.joinedOnly
            ? "조건에 맞는 참여 중인 채팅이 없어요." : "조건에 맞는 모임이 없어요.", 16, false);
    }
}
