package com.moadeal;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.List;
import static com.moadeal.Models.*;

public class ChatActivity extends BaseActivity {
    private Room room;
    private String sharedRoomId, draft = "";
    private FirebaseCommunity community;
    private final List<ListenerRegistration> listeners = new ArrayList<>();
    private boolean sharedRoomLoaded, membershipLoaded, joined, joining, loadFailed;
    private TextView description, roomInfo;
    private EditText input;
    private Button joinButton, sendButton;
    private LinearLayout messages;
    private ScrollView scroll;
    private PendingMessage pendingMessage;

    private record PendingMessage(String uid, String text, Task<Void> task) {}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sharedRoomId = getIntent().getStringExtra("sharedRoom");
        if (sharedRoomId != null) {
            community = ((MoadealApplication) getApplication()).community();
            if (community == null) { finish(); return; }
            if (state != null) draft = state.getString("chatDraft", "");
            title("그룹 채팅");
            createChatView("", "");
            if (getLastNonConfigurationInstance() instanceof PendingMessage pending) observeSend(pending);
            return;
        }
        room = store.room(getIntent().getStringExtra("room"));
        if (room == null) { note("이 모임은 앱 실행 중에만 유지됩니다."); finish(); return; }
        renderDemo();
    }

    @Override protected void onStart() {
        super.onStart();
        if (community == null || isFinishing()) return;
        String uid = accounts.currentUid();
        if (uid == null || !store.hasFirebaseProfile(uid)) return;
        loadFailed = false;
        listeners.add(community.watchRoom(sharedRoomId, (room, error) -> {
            if (!isCurrentUser(uid)) return;
            if (error != null) { noteLoadFailure(); return; }
            if (room == null) { note("모임을 찾을 수 없어요."); finish(); return; }
            title(room.name);
            description.setText(room.description);
            roomInfo.setText(room.area + " · " + room.memberCount + "명 · 그룹 채팅");
            if (!sharedRoomLoaded) {
                sharedRoomLoaded = true;
                if (membershipLoaded) renderComposer(joined);
            }
        }));
        listeners.add(community.watchMembership(sharedRoomId, uid, (member, error) -> {
            if (!isCurrentUser(uid)) return;
            if (error != null) { noteLoadFailure(); return; }
            if (member == null || membershipLoaded && joined == member) return;
            membershipLoaded = true;
            joined = member;
            if (sharedRoomLoaded) renderComposer(joined);
        }));
        listeners.add(community.watchMessages(sharedRoomId, (value, error) -> {
            if (!isCurrentUser(uid)) return;
            if (error != null) { noteLoadFailure(); return; }
            if (value == null) return;
            boolean atBottom = !scroll.canScrollVertically(1);
            renderMessages(value, uid);
            if (atBottom) scrollToBottom();
        }));
    }

    @Override protected void onStop() {
        for (ListenerRegistration listener : listeners) listener.remove();
        listeners.clear();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (community != null) state.putString("chatDraft", input == null ? draft : input.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override public Object onRetainNonConfigurationInstance() { return pendingMessage; }

    private void noteLoadFailure() {
        if (loadFailed) return;
        loadFailed = true;
        note("채팅을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
    }

    private void renderDemo() {
        title(room.name);
        createChatView(room.description, room.area + " · " + (room.sampleMembers + room.members.size()) + "명 · 예시 채팅");
        renderMessages(room.messages, store.account);
        renderComposer(room.members.contains(store.account));
        scrollToBottom();
    }

    private void createChatView(String text, String info) {
        scroll();
        description = Ui.text(this, content, text, 16, true);
        roomInfo = Ui.text(this, content, info, 14, false);
        content.setBackgroundColor(Ui.CREAM);
        messages = Ui.column(this, content);
        scroll = findViewById(R.id.page_scroll);
    }

    private void renderComposer(boolean member) {
        footer.removeAllViews();
        input = null;
        joinButton = null;
        sendButton = null;
        if (member) {
            LinearLayout row = Ui.row(this, footer);
            input = new EditText(this); input.setHint("메시지 입력"); input.setSingleLine(true); input.setId(R.id.chat_input);
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
            if (community != null) input.setText(draft);
            row.addView(input); Ui.weighted(input, 1);
            sendButton = Ui.button(this, row, "전송", this::send);
            sendButton.setEnabled(pendingMessage == null);
            sendButton.setContentDescription("메시지 전송"); sendButton.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 70), -2));
            input.setOnEditorActionListener((view, action, event) -> {
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(); return true; }
                return false;
            });
        } else {
            joinButton = primary("그룹 채팅 참여", this::join);
            joinButton.setEnabled(!joining);
        }
    }

    private void renderMessages(List<Message> values, String currentSender) {
        messages.removeAllViews();
        for (Message message : values) {
            boolean mine = currentSender.equals(message.sender);
            LinearLayout line = Ui.column(this, messages);
            line.setGravity(mine ? Gravity.END : Gravity.START);
            String senderName = community != null || message.sender == null ? message.name : store.accountId(message.sender);
            Ui.text(this, line, mine ? "나" : senderName, 12, false);
            TextView bubble = Ui.text(this, line, message.text, 16, false);
            bubble.setMaxWidth(Ui.dp(this, 285));
            bubble.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(mine ? 0xFFFFDAB9 : 0xFFFFFFFF); background.setCornerRadius(Ui.dp(this, 14));
            bubble.setBackground(background);
            line.setPadding(0, 0, 0, Ui.dp(this, 12));
        }
    }

    private void join() {
        if (community == null) {
            room.members.add(store.account);
            renderDemo();
            return;
        }
        String uid = accounts.currentUid();
        if (!isCurrentUser(uid) || joining || joined) return;
        joining = true;
        joinButton.setEnabled(false);
        community.join(sharedRoomId, uid).addOnCompleteListener(task -> {
            if (!isCurrentUser(uid)) return;
            joining = false;
            if (task.isSuccessful()) {
                if (!joined) {
                    joined = true;
                    renderComposer(true);
                }
            } else {
                if (joinButton != null) joinButton.setEnabled(true);
                note("모임에 참여하지 못했어요. 잠시 후 다시 시도해 주세요.");
            }
        });
    }

    private void send() {
        if (input == null || pendingMessage != null) return;
        String text = Ui.value(input);
        if (text.isEmpty()) return;
        if (community == null) {
            room.messages.add(new Message(text, store.account, ""));
            input.setText("");
            renderMessages(room.messages, store.account);
            scrollToBottom();
            return;
        }
        String uid = accounts.currentUid();
        if (!isCurrentUser(uid) || !joined) return;
        if (text.length() > FirebaseCommunity.MESSAGE_LIMIT) {
            note("메시지는 4,000자까지 입력할 수 있어요.");
            return;
        }
        observeSend(new PendingMessage(uid, text, community.send(sharedRoomId, uid, text)));
    }

    private void observeSend(PendingMessage request) {
        pendingMessage = request;
        if (sendButton != null) sendButton.setEnabled(false);
        request.task().addOnCompleteListener(task -> {
            if (!isCurrentUser(request.uid()) || request != pendingMessage) return;
            pendingMessage = null;
            if (sendButton != null) sendButton.setEnabled(true);
            if (!task.isSuccessful()) {
                note("메시지를 보내지 못했어요. 잠시 후 다시 시도해 주세요.");
                return;
            }
            if (input != null && Ui.value(input).equals(request.text())) input.setText("");
            if (draft.trim().equals(request.text())) draft = "";
            scrollToBottom();
        });
    }

    private boolean isCurrentUser(String uid) {
        return !isFinishing() && !isDestroyed() && uid != null
            && uid.equals(accounts.currentUid()) && store.hasFirebaseProfile(uid);
    }

    private void scrollToBottom() { scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN)); }
}
