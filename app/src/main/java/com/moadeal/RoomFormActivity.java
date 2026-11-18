package com.moadeal;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.*;
import com.google.android.gms.tasks.Task;
import static com.moadeal.Models.*;

public class RoomFormActivity extends BaseActivity {
    private Button openChat;
    private PendingRoom pendingRoom;
    private String roomId;

    private record PendingRoom(String uid, Task<String> task) {}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) roomId = state.getString("roomId");
        title("모임 만들기"); form();
        EditText name = Ui.field(this, content, "모임 이름", "", InputType.TYPE_CLASS_TEXT, R.id.room_name);
        EditText area = Ui.field(this, content, "지역", "", InputType.TYPE_CLASS_TEXT, R.id.room_area);
        EditText description = Ui.field(this, content, "모임 소개", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, R.id.room_description);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        area.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        description.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        Spinner category = Ui.spinner(this, content, "카테고리", CommunityPanel.CATEGORIES, 0); category.setId(R.id.room_category);
        openChat = primary("그룹 채팅 열기", () -> {
            if (!Ui.required(name) || !Ui.required(area) || !Ui.required(description)) return;
            createRoom(Ui.value(name), Ui.value(description), Ui.value(area), category.getSelectedItem().toString());
        });
        if (getLastNonConfigurationInstance() instanceof PendingRoom pending) observeCreation(pending);
    }

    @Override public Object onRetainNonConfigurationInstance() { return pendingRoom; }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (roomId != null) state.putString("roomId", roomId);
        super.onSaveInstanceState(state);
    }

    private void createRoom(String name, String description, String area, String category) {
        if (accounts == null) {
            Room room = store.addRoom(name, description, area, category, "", 0);
            room.members.add(store.account);
            store.rooms.remove(room);
            store.rooms.add(0, room);
            Ui.open(this, ChatActivity.class, "room", room.id);
            finish();
            return;
        }
        String uid = accounts.currentUid();
        if (uid == null || !store.hasFirebaseProfile(uid) || pendingRoom != null) return;
        FirebaseCommunity community = ((MoadealApplication) getApplication()).community();
        if (roomId == null) roomId = community.newRoomId();
        observeCreation(new PendingRoom(uid, community.createRoom(roomId, uid, name, description, area, category)));
    }

    private void observeCreation(PendingRoom request) {
        pendingRoom = request;
        openChat.setEnabled(false);
        request.task().addOnCompleteListener(task -> {
            if (isFinishing() || isDestroyed() || request != pendingRoom) return;
            pendingRoom = null;
            openChat.setEnabled(true);
            if (!request.uid().equals(accounts.currentUid()) || !store.hasFirebaseProfile(request.uid())) return;
            if (!task.isSuccessful()) {
                note("모임을 만들지 못했어요. 잠시 후 다시 시도해 주세요.");
                return;
            }
            Ui.open(this, ChatActivity.class, "sharedRoom", task.getResult());
            finish();
        });
    }
}
