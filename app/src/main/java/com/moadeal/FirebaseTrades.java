package com.moadeal;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.Blob;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Transaction;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import static com.moadeal.Models.*;

final class FirebaseTrades {
    private static final int PHOTO_LIMIT = 120 * 1024;

    record DailyLikes(String productId, long day, int likes) {}
    record DailyViews(String productId, long day, int views) {}

    private final FirebaseFirestore firestore;
    private final FirebaseAccounts accounts;
    private final Map<String, Task<Void>> favoriteWrites = new HashMap<>();

    FirebaseTrades(FirebaseFirestore firestore, FirebaseAccounts accounts) {
        this.firestore = firestore;
        this.accounts = accounts;
    }

    ListenerRegistration listenProducts(Consumer<List<Product>> onData, Consumer<Exception> onError) {
        Query query = firestore.collection("products").orderBy("createdAt", Query.Direction.DESCENDING);
        return query.addSnapshotListener((snapshot, error) -> {
            if (error != null) { onError.accept(error); return; }
            if (snapshot == null) return;
            List<Product> products = new ArrayList<>();
            for (DocumentSnapshot document : snapshot) products.add(readProduct(document));
            onData.accept(products);
        });
    }

    ListenerRegistration listenMemberships(Consumer<Set<String>> onData, Consumer<Exception> onError) {
        String uid = accounts.currentUid();
        if (uid == null) throw new IllegalStateException("로그인이 필요합니다.");
        return memberships(uid).addSnapshotListener((snapshot, error) -> {
            if (!uid.equals(accounts.currentUid())) return;
            if (error != null) { onError.accept(error); return; }
            if (snapshot == null) return;
            Set<String> ids = new HashSet<>();
            for (DocumentSnapshot document : snapshot) ids.add(document.getId());
            onData.accept(ids);
        });
    }

    ListenerRegistration listenQuestions(String productId, Consumer<List<Question>> onData, Consumer<Exception> onError) {
        return product(productId).collection("questions").orderBy("createdAt").addSnapshotListener((snapshot, error) -> {
            if (error != null) { onError.accept(error); return; }
            if (snapshot == null) return;
            List<Question> questions = new ArrayList<>();
            for (DocumentSnapshot document : snapshot) {
                Question question = new Question(text(document, "text"), document.getString("answer"));
                question.id = document.getId();
                questions.add(question);
            }
            onData.accept(questions);
        });
    }

    ListenerRegistration listenLikes(Consumer<List<DailyLikes>> onData, Consumer<Exception> onError) {
        return firestore.collection("tradeDays").whereGreaterThanOrEqualTo("day", Store.epochDay() - 6)
            .addSnapshotListener((snapshot, error) -> {
                if (error != null) { onError.accept(error); return; }
                if (snapshot == null) return;
                List<DailyLikes> stats = new ArrayList<>();
                for (DocumentSnapshot document : snapshot) {
                    stats.add(new DailyLikes(text(document, "productId"), number(document, "day"), count(document, "likes")));
                }
                onData.accept(stats);
            });
    }

    ListenerRegistration listenViews(Consumer<List<DailyViews>> onData, Consumer<Exception> onError) {
        String uid = accounts.currentUid();
        if (uid == null) throw new IllegalStateException("로그인이 필요합니다.");
        return viewDays(uid).whereGreaterThanOrEqualTo("day", Store.epochDay() - 6)
            .addSnapshotListener((snapshot, error) -> {
                if (!uid.equals(accounts.currentUid())) return;
                if (error != null) { onError.accept(error); return; }
                if (snapshot == null) return;
                List<DailyViews> stats = new ArrayList<>();
                for (DocumentSnapshot document : snapshot) {
                    stats.add(new DailyViews(text(document, "productId"), number(document, "day"), count(document, "views")));
                }
                onData.accept(stats);
            });
    }

    Task<Void> createProduct(Product product, Blob jpeg) {
        String uid = accounts.currentUid();
        if (uid == null) return noSession();
        int photoBytes = jpeg == null ? 0 : jpeg.toBytes().length;
        if (jpeg != null && (photoBytes == 0 || photoBytes > PHOTO_LIMIT)) {
            return Tasks.forException(failure("상품 사진을 저장할 수 없습니다."));
        }
        DocumentReference reference = product(product.id);
        return write(uid, transaction -> {
            DocumentSnapshot existing = transaction.get(reference);
            if (existing.exists()) {
                require(uid.equals(existing.getString("ownerUid")), "이미 등록된 상품입니다.");
                return null;
            }

            Map<String, Object> fields = new HashMap<>();
            fields.put("ownerUid", uid);
            fields.put("sellerListing", product.sellerProduct);
            fields.put("title", product.title);
            fields.put("description", product.description);
            fields.put("city", product.region.city);
            fields.put("district", product.region.district);
            fields.put("neighborhood", product.region.neighborhood);
            fields.put("category", product.category);
            fields.put("price", product.price);
            fields.put("target", product.target);
            fields.put("deadline", timestamp(product.deadline));
            fields.put("createdAt", FieldValue.serverTimestamp());
            fields.put("participantCount", product.sellerProduct ? 0 : 1);
            fields.put("offer", null);
            fields.put("selectedAt", null);
            fields.put("comparisonPrice", product.comparisonPrice);
            fields.put("hasPhoto", jpeg != null);
            transaction.set(reference, fields);
            if (!product.sellerProduct) {
                transaction.set(memberships(uid).document(product.id), Map.of("joinedAt", FieldValue.serverTimestamp()));
            }
            if (jpeg != null) transaction.set(photo(reference), Map.of("jpeg", jpeg));
            return null;
        });
    }

    Task<Void> setParticipation(String productId, boolean join) {
        String uid = accounts.currentUid();
        if (uid == null) return noSession();
        DocumentReference reference = product(productId);
        DocumentReference membership = memberships(uid).document(productId);
        return write(uid, transaction -> {
            DocumentSnapshot current = requireProduct(transaction, reference);
            boolean joined = transaction.get(membership).exists();
            if (join == joined) return null;

            long participants = number(current, "participantCount");
            require(!uid.equals(current.getString("ownerUid")) && participants < number(current, "target")
                && time(current, "deadline") > System.currentTimeMillis() && current.get("offer") == null
                && current.get("selectedAt") == null, "모집 상태가 변경되었어요.");
            if (join) {
                transaction.set(membership, Map.of("joinedAt", FieldValue.serverTimestamp()));
            } else {
                require(participants > 0, "참여 상태를 확인할 수 없습니다.");
                transaction.delete(membership);
            }
            transaction.update(reference, "participantCount", participants + (join ? 1 : -1));
            return null;
        });
    }

    Task<Void> submitOffer(String productId, Offer offer) {
        String uid = accounts.currentUid();
        if (uid == null) return noSession();
        DocumentReference reference = product(productId);
        return write(uid, transaction -> {
            DocumentSnapshot current = requireProduct(transaction, reference);
            require(!Boolean.TRUE.equals(current.getBoolean("sellerListing"))
                && number(current, "participantCount") >= number(current, "target")
                && current.get("offer") == null && current.get("selectedAt") == null,
                "이미 제안이 등록되었거나 모집 상태가 변경됐어요.");
            require(offer.expiry > System.currentTimeMillis(), "제안 유효기간을 확인하세요.");
            Map<String, Object> fields = Map.of("sellerUid", uid, "price", offer.price, "quantity", offer.quantity,
                "place", offer.place, "address", offer.address, "expiry", timestamp(offer.expiry),
                "createdAt", FieldValue.serverTimestamp());
            transaction.update(reference, "offer", fields);
            return null;
        });
    }

    Task<Void> selectOffer(String productId) {
        String uid = accounts.currentUid();
        if (uid == null) return noSession();
        DocumentReference reference = product(productId);
        return write(uid, transaction -> {
            DocumentSnapshot current = requireProduct(transaction, reference);
            require(uid.equals(current.getString("ownerUid")) && current.get("offer") != null
                && current.get("selectedAt") == null && time(current, "offer.expiry") > System.currentTimeMillis(),
                "선택할 수 없는 제안입니다.");
            transaction.update(reference, "selectedAt", FieldValue.serverTimestamp());
            return null;
        });
    }

    Task<Void> askQuestion(String productId, String text) {
        String uid = accounts.currentUid();
        if (uid == null) return noSession();
        DocumentReference reference = product(productId);
        DocumentReference question = reference.collection("questions").document();
        return write(uid, transaction -> {
            requireProduct(transaction, reference);
            Map<String, Object> fields = new HashMap<>();
            fields.put("authorUid", uid);
            fields.put("text", text);
            fields.put("createdAt", FieldValue.serverTimestamp());
            fields.put("answer", null);
            fields.put("answeredAt", null);
            fields.put("answererUid", null);
            transaction.set(question, fields);
            return null;
        });
    }

    Task<Void> answerQuestion(String productId, String questionId, String text) {
        String uid = accounts.currentUid();
        if (uid == null) return noSession();
        DocumentReference reference = product(productId);
        DocumentReference question = reference.collection("questions").document(questionId);
        return write(uid, transaction -> {
            DocumentSnapshot current = requireProduct(transaction, reference);
            DocumentSnapshot saved = transaction.get(question);
            boolean sellerOwner = Boolean.TRUE.equals(current.getBoolean("sellerListing"))
                && uid.equals(current.getString("ownerUid"));
            boolean offerOwner = current.get("offer") != null && uid.equals(current.getString("offer.sellerUid"));
            require((sellerOwner || offerOwner) && saved.exists() && saved.get("answer") == null,
                "답변을 등록할 수 없는 질문입니다.");
            transaction.update(question, Map.of("answer", text, "answererUid", uid, "answeredAt", FieldValue.serverTimestamp()));
            return null;
        });
    }

    Task<Void> setFavorite(String productId, boolean wanted) {
        String uid = accounts.currentUid();
        if (uid == null) return noSession();
        String key = uid + ":" + productId;
        Task<Void> previous = favoriteWrites.get(key);
        Task<Void> pending = previous == null ? updateFavorite(uid, productId, wanted)
            : previous.continueWithTask(ignored -> updateFavorite(uid, productId, wanted));
        favoriteWrites.put(key, pending);
        pending.addOnCompleteListener(ignored -> favoriteWrites.remove(key, pending));
        return pending;
    }

    private Task<Void> updateFavorite(String uid, String productId, boolean wanted) {
        DocumentReference favorite = product(productId).collection("likes").document(uid);
        return write(uid, transaction -> {
            long day = Store.epochDay();
            DocumentSnapshot current = transaction.get(favorite);
            boolean activeToday = current.exists() && number(current, "day") == day;
            if (wanted && activeToday || !wanted && !current.exists()) return null;

            if (wanted || activeToday) updateLikes(transaction, productId, day, wanted ? 1 : -1);
            if (wanted) transaction.set(favorite, Map.of("day", day));
            else transaction.delete(favorite);
            return null;
        });
    }

    Task<Void> recordView(String productId, String ownerUid) {
        if (accounts.currentUid() == null) return noSession();
        long day = Store.epochDay();
        return viewDays(ownerUid).document(productId + "_" + day)
            .set(Map.of("productId", productId, "day", day, "views", FieldValue.increment(1)), SetOptions.merge());
    }

    Task<Blob> loadPhoto(String productId) {
        return photo(product(productId)).get().onSuccessTask(snapshot -> Tasks.forResult(snapshot.getBlob("jpeg")));
    }

    private Task<Void> write(String uid, Transaction.Function<Void> operation) {
        return firestore.runTransaction(transaction -> {
            require(uid.equals(accounts.currentUid()), "로그인이 필요합니다.");
            return operation.apply(transaction);
        });
    }

    private void updateLikes(Transaction transaction, String productId, long day, int delta)
            throws FirebaseFirestoreException {
        DocumentReference reference = firestore.collection("tradeDays").document(productId + "_" + day);
        DocumentSnapshot previous = transaction.get(reference);
        long likes = number(previous, "likes") + delta;
        require(likes >= 0, "관심상품 집계를 불러오지 못했습니다.");
        transaction.set(reference, Map.of("productId", productId, "day", day, "likes", likes));
    }

    private DocumentReference product(String id) { return firestore.collection("products").document(id); }

    private CollectionReference memberships(String uid) {
        return firestore.collection("users").document(uid).collection("tradeMemberships");
    }

    private CollectionReference viewDays(String uid) {
        return firestore.collection("users").document(uid).collection("tradeViewDays");
    }

    private static DocumentReference photo(DocumentReference reference) {
        return reference.collection("photos").document("main");
    }

    private static DocumentSnapshot requireProduct(Transaction transaction, DocumentReference reference)
            throws FirebaseFirestoreException {
        DocumentSnapshot document = transaction.get(reference);
        require(document.exists(), "상품 정보를 찾을 수 없습니다.");
        return document;
    }

    private static Product readProduct(DocumentSnapshot document) {
        Product product = new Product();
        product.id = document.getId();
        product.remote = true;
        product.owner = "firebase:" + text(document, "ownerUid");
        product.sellerProduct = Boolean.TRUE.equals(document.getBoolean("sellerListing"));
        product.title = text(document, "title");
        product.description = text(document, "description");
        product.region = new Region(text(document, "city"), text(document, "district"), text(document, "neighborhood"));
        product.category = count(document, "category");
        product.price = count(document, "price");
        product.target = count(document, "target");
        product.deadline = time(document, "deadline");
        product.remoteParticipantCount = count(document, "participantCount");
        product.comparisonPrice = count(document, "comparisonPrice");
        product.hasRemotePhoto = Boolean.TRUE.equals(document.getBoolean("hasPhoto"));
        if (document.get("offer") != null) {
            Offer offer = new Offer(count(document, "offer.price"), count(document, "offer.quantity"),
                text(document, "offer.place"), text(document, "offer.address"), time(document, "offer.expiry"),
                "firebase:" + text(document, "offer.sellerUid"));
            product.offers.add(offer);
            if (time(document, "selectedAt") != 0) product.selected = offer;
        }
        return product;
    }

    private static Task<Void> noSession() { return Tasks.forException(failure("로그인이 필요합니다.")); }

    private static FirebaseFirestoreException failure(String message) {
        return new FirebaseFirestoreException(message, FirebaseFirestoreException.Code.FAILED_PRECONDITION);
    }

    private static void require(boolean valid, String message) throws FirebaseFirestoreException {
        if (!valid) throw failure(message);
    }

    private static Timestamp timestamp(long millis) { return new Timestamp(new Date(millis)); }

    private static long time(DocumentSnapshot document, String field) {
        Timestamp timestamp = document.getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
        return timestamp == null ? 0 : timestamp.toDate().getTime();
    }

    private static String text(DocumentSnapshot document, String field) {
        String text = document.getString(field);
        return text == null ? "" : text;
    }

    private static long number(DocumentSnapshot document, String field) {
        Long number = document.getLong(field);
        return number == null ? 0 : number;
    }

    private static int count(DocumentSnapshot document, String field) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(document, field)));
    }
}
