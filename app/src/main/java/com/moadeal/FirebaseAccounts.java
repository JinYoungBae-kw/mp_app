package com.moadeal;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

final class FirebaseAccounts {
    static final String BUYER_ROLE = "buyer";
    static final String SELLER_ROLE = "seller";

    static final class Profile {
        final String uid;
        final String name;
        final String role;

        Profile(String uid, String name, String role) {
            this.uid = uid;
            this.name = name;
            this.role = role;
        }
    }

    private final FirebaseAuth auth;
    private final FirebaseFirestore firestore;
    private final SharedPreferences pendingSignups;
    private final String pendingSignupPrefix;
    private Task<Profile> authenticationTask;
    private int sessionVersion;
    private int authenticationVersion;

    private FirebaseAccounts(FirebaseApp app, FirebaseAuth auth, FirebaseFirestore firestore) {
        this.auth = auth;
        this.firestore = firestore;
        pendingSignups = app.getApplicationContext().getSharedPreferences("moadeal_pending_signup", Context.MODE_PRIVATE);
        pendingSignupPrefix = app.getName() + ":" + app.getOptions().getApplicationId() + ":";
    }

    static FirebaseAccounts create(Context context) {
        FirebaseApp app = FirebaseApp.initializeApp(context);
        if (app == null) return null;
        return new FirebaseAccounts(app, FirebaseAuth.getInstance(app), FirebaseFirestore.getInstance(app));
    }

    String currentUid() {
        FirebaseUser user = auth.getCurrentUser();
        return user == null ? null : user.getUid();
    }

    boolean hasSession() { return currentUid() != null; }

    Task<String> idToken() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return noSession();
        int version = sessionVersion;
        return user.getIdToken(false).onSuccessTask(result -> {
            String token = result.getToken();
            if (token == null || version != sessionVersion || !user.getUid().equals(currentUid())) return noSession();
            return Tasks.forResult(token);
        });
    }

    FirebaseFirestore firestore() { return firestore; }

    Task<Profile> pendingSignIn() {
        return authenticationInProgress() && authenticationVersion == sessionVersion ? authenticationTask : null;
    }

    Task<Profile> signIn(String email, String password) {
        return authenticate(() -> auth.signInWithEmailAndPassword(email, password), null, null);
    }

    Task<Profile> signUp(String name, String email, String password, String role) {
        if (!validName(name)) return invalidName();
        if (!BUYER_ROLE.equals(role) && !SELLER_ROLE.equals(role)) {
            return Tasks.forException(new IllegalArgumentException("구매자 또는 판매자를 선택해 주세요."));
        }
        return authenticate(() -> auth.createUserWithEmailAndPassword(email, password), name.trim(), role);
    }

    Task<Profile> loadProfile() {
        if (authenticationInProgress()) {
            Task<Profile> pending = pendingSignIn();
            return pending != null ? pending : noSession();
        }
        FirebaseUser user = auth.getCurrentUser();
        return user == null ? noSession() : syncProfile(user, sessionVersion, null);
    }

    Task<Profile> updateName(String name) {
        if (!validName(name)) return invalidName();
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || authenticationInProgress()) return noSession();
        return syncProfile(user, sessionVersion, name.trim());
    }

    void signOut() {
        sessionVersion++;
        auth.signOut();
    }

    private boolean authenticationInProgress() {
        return authenticationTask != null && !authenticationTask.isComplete();
    }

    private Task<Profile> authenticate(Supplier<Task<AuthResult>> request, String signupName, String signupRole) {
        if (authenticationInProgress()) {
            return Tasks.forException(new IllegalStateException("진행 중인 로그인이 끝난 뒤 다시 시도하세요."));
        }
        int version = ++sessionVersion;
        authenticationVersion = version;
        authenticationTask = request.get().onSuccessTask(result -> {
            FirebaseUser user = result.getUser();
            if (user == null) return noSession();

            String uid = user.getUid();
            if (!uid.equals(currentUid())) return noSession();
            if (version != sessionVersion) {
                auth.signOut();
                return noSession();
            }
            if (signupName != null) {
                String pendingNameKey = pendingSignupPrefix + uid;
                pendingSignups.edit()
                    .putString(pendingNameKey, signupName)
                    .putString("role:" + pendingNameKey, signupRole)
                    .apply();
            }
            return syncProfile(user, version, null);
        });
        return authenticationTask;
    }

    private Task<Profile> syncProfile(FirebaseUser user, int version, String newName) {
        String uid = user.getUid();
        String pendingNameKey = pendingSignupPrefix + uid;
        String pendingRoleKey = "role:" + pendingNameKey;
        String accountName = user.getDisplayName();
        String initialName = newName == null
            ? pendingSignups.getString(pendingNameKey, validName(accountName) ? accountName.trim() : "구매자")
            : newName;
        String initialRole = profileRole(pendingSignups.getString(pendingRoleKey, BUYER_ROLE));
        DocumentReference profileRef = firestore.collection("users").document(uid);

        return firestore.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(profileRef);
            if (!snapshot.exists()) {
                Map<String, String> fields = new HashMap<>();
                fields.put("name", initialName);
                fields.put("role", initialRole);
                transaction.set(profileRef, fields);
                return new Profile(uid, initialName, initialRole);
            }

            String savedName = snapshot.getString("name");
            String role = profileRole(snapshot.getString("role"));
            if (newName != null && !newName.equals(savedName)) transaction.update(profileRef, "name", newName);
            String displayName = newName == null ? savedName : newName;
            return new Profile(uid, validName(displayName) ? displayName : "회원", role);
        }).onSuccessTask(profile -> {
            if (version != sessionVersion || !uid.equals(currentUid())) return noSession();
            pendingSignups.edit().remove(pendingNameKey).remove(pendingRoleKey).apply();
            return Tasks.forResult(profile);
        });
    }

    private static String profileRole(String role) {
        return SELLER_ROLE.equals(role) ? SELLER_ROLE : BUYER_ROLE;
    }

    private static boolean validName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        return !trimmed.isEmpty() && trimmed.length() <= 24;
    }

    private static Task<Profile> invalidName() {
        return Tasks.forException(new IllegalArgumentException("이름은 1자 이상 24자 이하로 입력하세요."));
    }

    private static <T> Task<T> noSession() {
        return Tasks.forException(new IllegalStateException("로그인이 필요합니다."));
    }
}
