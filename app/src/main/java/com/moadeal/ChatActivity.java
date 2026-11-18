package com.moadeal;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import static com.moadeal.Models.*;

public class ChatActivity extends BaseActivity {
    private Room room;
    private EditText input;
    private LinearLayout messages;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        room = store.room(getIntent().getStringExtra("room"));
        if (room == null) { note("이 모임은 앱 실행 중에만 유지됩니다."); finish(); return; }
        render();
    }
    private void render() {
        title(room.name); scroll();
        Ui.text(this, content, room.description, 16, true);
        Ui.text(this, content, room.area + " · " + (room.sampleMembers + room.members.size()) + "명 · 예시 채팅", 14, false);
        content.setBackgroundColor(Ui.CREAM);
        messages = Ui.column(this, content); renderMessages();
        scroll = findViewById(R.id.page_scroll);
        if (room.members.contains(store.account)) {
            LinearLayout row = Ui.row(this, footer);
            input = new EditText(this); input.setHint("메시지 입력"); input.setSingleLine(true); input.setId(R.id.chat_input);
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
            row.addView(input); Ui.weighted(input, 1);
            Button send = Ui.button(this, row, "전송", this::send);
            send.setContentDescription("메시지 전송"); send.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 70), -2));
            input.setOnEditorActionListener((view, action, event) -> {
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(); return true; }
                return false;
            });
        } else primary("그룹 채팅 참여", () -> { room.members.add(store.account); render(); });
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
    private void renderMessages() {
        messages.removeAllViews();
        for (Message message : room.messages) {
            boolean mine = store.account.equals(message.sender);
            LinearLayout line = Ui.column(this, messages);
            line.setGravity(mine ? Gravity.END : Gravity.START);
            Ui.text(this, line, mine ? "나" : message.sender == null ? message.name : Store.accountId(message.sender), 12, false);
            TextView bubble = Ui.text(this, line, message.text, 16, false);
            bubble.setMaxWidth(Ui.dp(this, 285));
            bubble.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(mine ? 0xFFFFDAB9 : 0xFFFFFFFF); background.setCornerRadius(Ui.dp(this, 14));
            bubble.setBackground(background);
            line.setPadding(0, 0, 0, Ui.dp(this, 12));
        }
    }
    private void send() {
        if (input == null || Ui.value(input).isEmpty()) return;
        room.messages.add(new Message(Ui.value(input), store.account, ""));
        input.setText(""); renderMessages();
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
