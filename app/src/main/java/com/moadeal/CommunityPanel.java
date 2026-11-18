package com.moadeal;

import android.widget.*;
import android.view.View;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import static com.moadeal.Models.*;

final class CommunityPanel {
    private final BaseActivity activity;
    private final LinearLayout parent;
    private LinearLayout results;
    private List<FirebaseCommunity.RoomSummary> sharedRooms = Collections.emptyList();
    private Set<String> joinedRooms = Collections.emptySet();
    static class State {
        String query = "", category = "전체";
        boolean joinedOnly;
    }
    private final State state;
    static final String[] CATEGORIES = {"식품", "미용", "생필품", "동네가게"};

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
        observeSharedRooms();
    }

    private void observeSharedRooms() {
        FirebaseCommunity community = ((MoadealApplication) activity.getApplication()).community();
        if (community == null || activity.accounts == null) return;
        String uid = activity.accounts.currentUid();
        if (uid == null || !activity.store.hasFirebaseProfile(uid)) return;
        View.OnAttachStateChangeListener watcher = new View.OnAttachStateChangeListener() {
            private ListenerRegistration rooms, memberships;

            @Override public void onViewAttachedToWindow(View view) {
                rooms = community.watchRooms(activity, (value, error) -> {
                    if (!view.isAttachedToWindow() || !uid.equals(activity.accounts.currentUid())) return;
                    if (error != null) {
                        activity.note("공유 모임을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
                    } else if (value != null) {
                        sharedRooms = value;
                        renderRooms();
                    }
                });
                memberships = community.watchJoinedRooms(activity, uid, (value, error) -> {
                    if (!view.isAttachedToWindow() || !uid.equals(activity.accounts.currentUid())) return;
                    if (error != null) {
                        activity.note("참여한 모임을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
                    } else if (value != null) {
                        joinedRooms = value;
                        renderRooms();
                    }
                });
            }

            @Override public void onViewDetachedFromWindow(View view) {
                if (rooms != null) rooms.remove();
                if (memberships != null) memberships.remove();
            }
        };
        results.addOnAttachStateChangeListener(watcher);
        if (results.isAttachedToWindow()) watcher.onViewAttachedToWindow(results);
    }

    private void renderRooms() {
        if (results == null) return;
        results.removeAllViews();
        for (FirebaseCommunity.RoomSummary room : sharedRooms) {
            renderRoom(room.name, room.description, room.area, room.category, "", room.memberCount,
                joinedRooms.contains(room.id), () -> Ui.open(activity, ChatActivity.class, "sharedRoom", room.id));
        }
        for (Room room : activity.store.rooms) {
            renderRoom(room.name, room.description, room.area, room.category, room.photo,
                room.sampleMembers + room.members.size(), room.members.contains(activity.store.account),
                () -> Ui.open(activity, ChatActivity.class, "room", room.id));
        }
        if (results.getChildCount() == 0) Ui.text(activity, results, state.joinedOnly
            ? "조건에 맞는 참여 중인 채팅이 없어요." : "조건에 맞는 모임이 없어요.", 16, false);
    }

    private void renderRoom(String name, String description, String area, String category, String photo, long memberCount,
            boolean joined, Runnable open) {
        if (state.joinedOnly && !joined) return;
        if (!state.category.equals("전체") && !state.category.equals(category)) return;
        if (!(name + area).contains(state.query)) return;
        View row = activity.getLayoutInflater().inflate(R.layout.item_row, results, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(name);
        ((TextView) row.findViewById(R.id.row_description)).setText(description + "\n" + area + " · " + memberCount + "명");
        Ui.image(activity, row.findViewById(R.id.row_photo), photo);
        row.setOnClickListener(view -> open.run());
        results.addView(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(activity, 12);
        row.setLayoutParams(params);
    }
}
