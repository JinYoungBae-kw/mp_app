package com.moadeal;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.*;
import static com.moadeal.Models.*;

public class RoomFormActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        title("모임 만들기"); form();
        EditText name = Ui.field(this, content, "모임 이름", "", InputType.TYPE_CLASS_TEXT, R.id.room_name);
        EditText area = Ui.field(this, content, "지역", "", InputType.TYPE_CLASS_TEXT, R.id.room_area);
        EditText description = Ui.field(this, content, "모임 소개", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, R.id.room_description);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        area.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        description.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        Spinner category = Ui.spinner(this, content, "카테고리", CommunityPanel.CATEGORIES, 0); category.setId(R.id.room_category);
        primary("그룹 채팅 열기", () -> {
            if (!Ui.required(name) || !Ui.required(area) || !Ui.required(description)) return;
            Room room = store.addRoom(Ui.value(name), Ui.value(description), Ui.value(area), category.getSelectedItem().toString(), "", 0);
            room.members.add(store.account);
            store.rooms.remove(room); store.rooms.add(0, room);
            Ui.open(this, ChatActivity.class, "room", room.id); finish();
        });
    }
}
