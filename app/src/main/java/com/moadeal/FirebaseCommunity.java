package com.moadeal;

import android.app.Activity;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.moadeal.Models.Message;

final class FirebaseCommunity {
    static final int MESSAGE_LIMIT = 4000;

    static final class RoomSummary {
        final String id, name, area, description, category;
        final long memberCount;

        RoomSummary(DocumentSnapshot document) {
            id = document.getId();
            name = document.getString("name");
            area = document.getString("area");
            description = document.getString("description");
            category = document.getString("category");
            Long count = document.getLong("memberCount");
            memberCount = count == null ? 0 : count;
        }
    }

    private final FirebaseFirestore firestore;

    FirebaseCommunity(FirebaseFirestore firestore) { this.firestore = firestore; }

    ListenerRegistration watchRooms(Activity activity, EventListener<List<RoomSummary>> listener) {
        return rooms().orderBy("createdAt", Query.Direction.DESCENDING).addSnapshotListener(activity, (snapshot, error) -> {
            if (error != null || snapshot == null) {
                listener.onEvent(null, error);
                return;
            }
            List<RoomSummary> result = new ArrayList<>();
            for (DocumentSnapshot document : snapshot) result.add(new RoomSummary(document));
            listener.onEvent(result, null);
        });
    }

    ListenerRegistration watchJoinedRooms(Activity activity, String uid, EventListener<Set<String>> listener) {
        return joinedRooms(uid).addSnapshotListener(activity, (snapshot, error) -> {
            if (error != null || snapshot == null) {
                listener.onEvent(null, error);
                return;
            }
            Set<String> roomIds = new HashSet<>();
            for (DocumentSnapshot document : snapshot) roomIds.add(document.getId());
            listener.onEvent(roomIds, null);
        });
    }

    ListenerRegistration watchRoom(String id, EventListener<RoomSummary> listener) {
        return rooms().document(id).addSnapshotListener(MetadataChanges.INCLUDE, (document, error) -> {
            if (document != null && !document.exists() && document.getMetadata().isFromCache()) return;
            listener.onEvent(document != null && document.exists() ? new RoomSummary(document) : null, error);
        });
    }

    ListenerRegistration watchMembership(String roomId, String uid, EventListener<Boolean> listener) {
        return joinedRooms(uid).document(roomId).addSnapshotListener(MetadataChanges.INCLUDE, (document, error) -> {
            if (document != null && !document.exists() && document.getMetadata().isFromCache()) return;
            listener.onEvent(document != null ? document.exists() : null, error);
        });
    }

    ListenerRegistration watchMessages(String roomId, EventListener<List<Message>> listener) {
        return rooms().document(roomId).collection("messages").orderBy("sentAt").addSnapshotListener((snapshot, error) -> {
            if (error != null || snapshot == null) {
                listener.onEvent(null, error);
                return;
            }
            List<Message> messages = new ArrayList<>();
            for (DocumentSnapshot document : snapshot) {
                messages.add(new Message(document.getString("text"), document.getString("senderUid"), document.getString("senderName")));
            }
            listener.onEvent(messages, null);
        });
    }

    String newRoomId() { return rooms().document().getId(); }

    Task<String> createRoom(String roomId, String uid, String name, String description, String area, String category) {
        DocumentReference room = rooms().document(roomId);
        Map<String, Object> fields = new HashMap<>();
        fields.put("ownerUid", uid);
        fields.put("name", name);
        fields.put("description", description);
        fields.put("area", area);
        fields.put("category", category);
        fields.put("memberCount", 1);
        fields.put("createdAt", FieldValue.serverTimestamp());
        return firestore.runTransaction(transaction -> {
            DocumentSnapshot saved = transaction.get(room);
            if (saved.exists()) {
                if (!uid.equals(saved.getString("ownerUid"))) throw new IllegalStateException("모임을 저장할 수 없습니다.");
                return roomId;
            }
            transaction.set(room, fields);
            transaction.set(joinedRooms(uid).document(roomId), newMembership());
            return roomId;
        });
    }

    Task<Void> join(String roomId, String uid) {
        DocumentReference room = rooms().document(roomId);
        DocumentReference membership = joinedRooms(uid).document(roomId);
        return firestore.runTransaction(transaction -> {
            if (transaction.get(membership).exists()) return null;
            Long count = transaction.get(room).getLong("memberCount");
            if (count == null) throw new IllegalStateException("모임을 찾을 수 없습니다.");
            transaction.update(room, "memberCount", count + 1);
            transaction.set(membership, newMembership());
            return null;
        });
    }

    Task<Void> send(String roomId, String uid, String text) {
        DocumentReference message = rooms().document(roomId).collection("messages").document();
        return firestore.runTransaction(transaction -> {
            String name = transaction.get(user(uid)).getString("name");
            if (name == null) throw new IllegalStateException("프로필을 찾을 수 없습니다.");
            Map<String, Object> fields = new HashMap<>();
            fields.put("senderUid", uid);
            fields.put("senderName", name);
            fields.put("text", text);
            fields.put("sentAt", FieldValue.serverTimestamp());
            transaction.set(message, fields);
            return null;
        });
    }

    private CollectionReference rooms() { return firestore.collection("rooms"); }

    private DocumentReference user(String uid) { return firestore.collection("users").document(uid); }

    private CollectionReference joinedRooms(String uid) { return user(uid).collection("joinedRooms"); }

    private Map<String, Object> newMembership() {
        return Collections.singletonMap("joinedAt", FieldValue.serverTimestamp());
    }
}
