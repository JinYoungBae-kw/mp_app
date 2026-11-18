package com.moadeal;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONArray;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import android.graphics.Bitmap;
import static com.moadeal.Models.*;

/**
 * On-device migration regression checks using only the Android SDK.
 * Run on a dedicated emulator with "am instrument -w com.moadeal.test/com.moadeal.NativeSmokeTest".
 */
public class NativeSmokeTest extends Instrumentation {
    private int checks;
    private StringBuilder report = new StringBuilder();
    private Store store;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++; report.append("PASS ").append(label).append("\n");
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            // This runner is intended for the dedicated migration emulator.
            getTargetContext().getSharedPreferences("moadeal_native", 0).edit().clear().commit();
            getTargetContext().getSharedPreferences("FlutterSharedPreferences", 0).edit().clear().commit();
            Activity login = launch(LoginActivity.class, null, null);
            store = ((MoadealApplication) login.getApplication()).store();
            catalog();
            localStorageMigration();
            buyerSellerFlow();
            registrationFlows();
            screenFlows();
            result.putString("stream", "\n" + report + "\nOK (" + checks + " checks)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            capture("failure");
            result.putString("stream", "\n" + report + "\nFAIL " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void registrationFlows() {
        store.account = BUYER;
        Activity form = launch(RequestFormActivity.class, null, null);
        tap(form, "구매 요청 올리기");
        check(form.findViewById(R.id.request_name) instanceof EditText
            && ((EditText) form.findViewById(R.id.request_name)).getError() != null,
            "empty request submission shows required-field validation");
        fill(form, R.id.request_name, "테스트 구매 요청");
        fill(form, R.id.request_price, "6000");
        fill(form, R.id.request_target, "6");
        Activity detail = tapAndOpen(form, "구매 요청 올리기", DetailActivity.class);
        check(store.products.get(0).title.equals("테스트 구매 요청"), "buyer registration creates a request through its form");
        check(findText(detail, "참여 1 / 6명") != null, "registered buyer is the initial participant");
        Product request = store.products.get(0);
        request.sampleParticipants = 5;
        store.account = SELLER;
        Activity reviewing = launch(DetailActivity.class, "product", request.id);
        Activity offerForm = tapAndOpen(reviewing, "판매 제안 작성", OfferFormActivity.class);
        fill(offerForm, R.id.offer_price, "5700");
        fill(offerForm, R.id.offer_quantity, "6");
        tap(offerForm, "픽업 장소 검색");
        tap(offerForm, "월계동 과일가게\n서울 노원구 월계동");
        tap(offerForm, "판매 제안 등록");
        check(request.offers.size() == 1 && request.offers.get(0).price == 5700, "seller submits price, quantity and pickup place through the offer form");
        store.account = BUYER;
        Activity offers = launch(OfferListActivity.class, "product", request.id);
        tap(offers, "이 제안 선택");
        check(request.selected != null && request.selected.price == 5700, "request owner selects the seller offer through the comparison screen");

        store.account = SELLER;
        Activity sellerForm = launch(RequestFormActivity.class, null, null);
        fill(sellerForm, R.id.request_name, "테스트 판매 상품");
        fill(sellerForm, R.id.request_description, "판매 상세 설명");
        fill(sellerForm, R.id.request_price, "9000");
        fill(sellerForm, R.id.request_target, "6");
        Activity sellerDetail = tapAndOpen(sellerForm, "판매 상품 올리기", DetailActivity.class);
        check(store.products.get(0).sellerListing(), "seller registration creates a seller-owned listing");
        check(findText(sellerDetail, "참여 0 / 6명") != null, "seller is excluded from buyer participation count");
        check(findText(sellerDetail, "판매 상세 설명") != null, "seller detail renders its registered description");

        store.account = BUYER;
        Activity roomForm = launch(RoomFormActivity.class, null, null);
        fill(roomForm, R.id.room_name, "테스트 모임");
        fill(roomForm, R.id.room_area, "서울 노원구");
        fill(roomForm, R.id.room_description, "같이 구매할 이웃을 모아요");
        Activity chat = tapAndOpen(roomForm, "그룹 채팅 열기", ChatActivity.class);
        fill(chat, R.id.chat_input, "직접 보낸 메시지");
        tap(chat, "전송");
        check(findText(chat, "직접 보낸 메시지") != null, "new group chat accepts a user message");

        Activity profile = launch(ProfileActivity.class, null, null);
        fill(profile, R.id.profile_name, "수정한 구매자");
        tap(profile, "저장");
        check(store.profileName().equals("수정한 구매자"), "profile edit form saves account-scoped name");
        store.account = SELLER;
        check(!store.profileName().equals("수정한 구매자"), "seller profile is isolated from buyer profile");
    }

    private void catalog() {
        for (int c = 0; c < 3; c++) {
            int count = 0;
            for (Product p : store.products) if (!p.sellerListing() && p.category == c && !p.id.startsWith("regional|")) count++;
            check(count == 30, "catalog category " + c + " retains 30 products");
        }
        long specials = store.products.stream().filter(p -> Store.special(p) != null).count();
        check(specials == 5, "Korean date supplies exactly five daily specials");
        Region region = new Region("강원특별자치도", "춘천시", "효자동");
        store.fillRegion(region); store.fillRegion(region);
        check(store.visible(region, -1, -1, "", false).size() == 6, "all-category regional display has six products");
        for (int c = 0; c < 3; c++) check(store.visible(region, c, -1, "", false).size() == 3, "region/category " + c + " has three distinct products");
        check(store.neighborhoods(region).contains("효자동"), "nationwide directory includes actual neighborhoods");
    }

    private void localStorageMigration() {
        SharedPreferences legacy = getTargetContext().getSharedPreferences("FlutterSharedPreferences", 0);
        String prefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!";
        legacy.edit().putString("flutter.profile.name.host", "이전 닉네임")
            .putString("flutter.favorite.host.sample-strawberries", Store.today())
            .putString("flutter.attendance.host", prefix + new JSONArray().put("2026-01-01")).commit();
        store.account = BUYER;
        check("이전 닉네임".equals(store.profileName()), "Flutter profile survives an in-place update");
        check(store.favorite(store.product("sample-strawberries")), "Flutter favorites survive an in-place update");
        check(store.attendance().contains("2026-01-01"), "Flutter JSON attendance decodes correctly");
        check(store.checkIn(), "attendance allows first check-in");
        check(!store.checkIn(), "attendance prevents duplicate Korean-day check-in");
        store.toggleFavorite(store.product("sample-strawberries"));
        check(!store.favorite(store.product("sample-strawberries")), "removing a migrated favorite overrides its legacy value");
        store.saveProfile("변경한 이름");
        Store restored = new Store(getTargetContext());
        check("변경한 이름".equals(restored.profileName()), "native profile restores after store recreation");
        check(restored.attendance().size() == 2, "native attendance restores after store recreation");
    }

    private void buyerSellerFlow() {
        Product apple = store.product("seller-apples");
        store.account = BUYER;
        check(apple.joined() == 5 && apple.recruiting(), "seller listing starts at 5/6");
        apple.members.add(BUYER);
        check(apple.completed() && apple.joined() == 6, "buyer joining completes seller listing at 6/6");
        Question question = new Question("토요일 수령 가능한가요?", null);
        apple.questions.add(question);
        store.account = SELLER;
        question.answer = "가능합니다.";
        check("가능합니다.".equals(apple.questions.get(apple.questions.size() - 1).answer), "Q&A survives role switching");
        Product dessert = store.product("sample-dessert");
        check(dessert.stage() == 1, "buyer request becomes eligible for offers");
        dessert.selected = dessert.offers.get(0);
        check(dessert.stage() == 2 && dessert.selected.price == 10500, "selecting an offer sets final price and completed stage");
        Room room = store.rooms.get(0); room.members.add(BUYER);
        room.messages.add(new Message("같이 구매해요", BUYER, ""));
        check(room.messages.get(room.messages.size() - 1).sender.equals(BUYER), "chat keeps the sending account");
    }

    private void screenFlows() {
        store.account = BUYER;
        HomeActivity buyer = (HomeActivity) launch(HomeActivity.class, null, null);
        capture("buyer-home");
        check(findText(buyer, "커뮤니티") != null, "buyer home exposes community navigation");
        tap(buyer, "내 계정");
        capture("buyer-account");
        check(findText(buyer, "관심 내역   ›") != null, "buyer account exposes interest history");
        store.account = SELLER;
        HomeActivity seller = (HomeActivity) launch(HomeActivity.class, null, null);
        capture("seller-home");
        check(findText(seller, "내 판매 상품") != null, "seller receives a separate home");
        Activity history = launch(HistoryActivity.class, "section", "판매관리");
        check(findText(history, "판매진행중") != null && findText(history, "판매완료") != null, "sales management has both stages");
        Activity detail = launch(DetailActivity.class, "product", "seller-apples");
        capture("seller-detail");
        check(findText(detail, "최근 7일 조회수") != null, "seller detail shows chart and view count");
        runOnMainSync(() -> ((android.widget.ScrollView) detail.findViewById(R.id.page_scroll))
            .fullScroll(android.widget.ScrollView.FOCUS_DOWN));
        waitForIdleSync();
        capture("seller-stats");
        store.account = BUYER;
        Activity attendance = launch(AttendanceActivity.class, null, null);
        capture("attendance");
        check(findText(attendance, "출석 진행률     20%") != null, "attendance screen uses migrated days");
        Activity region = launch(RegionActivity.class, null, null);
        check(findText(region, "전체 시·도") != null, "optional region selection renders");
        for (Class<? extends Activity> type : List.of(RequestFormActivity.class, ProfileActivity.class, RoomFormActivity.class)) {
            Activity activity = launch(type, null, null);
            check(!activity.isFinishing(), type.getSimpleName() + " opens without crash");
        }
        Activity chat = launch(ChatActivity.class, "room", store.rooms.get(0).id);
        capture("chat");
        check(findText(chat, "직접 보낸 메시지") != null, "group chat shows messages after role switches");
        launch(CommunityActivity.class, null, null);
        capture("community");
        for (String collection : List.of("최저가도전", "랭킹", "마감임박", "오늘의 특가")) {
            Activity activity = launch(CollectionActivity.class, "collection", collection);
            check(!activity.isFinishing(), collection + " renders");
        }
        Activity verification = launch(VerificationActivity.class, null, null);
        tap(verification, "예시 사업자등록증 첨부");
        tap(verification, "심사 요청");
        check(findText(verification, "심사 대기") != null, "seller verification moves to review");
        tap(verification, "예시 심사 결과 보기");
        check(findText(verification, "승인 완료") != null, "seller verification moves to approval");
        store.account = BUYER;
        launch(HomeActivity.class, null, null);
    }

    private void fill(Activity activity, int id, String value) {
        runOnMainSync(() -> ((EditText) activity.findViewById(id)).setText(value));
        waitForIdleSync();
    }

    private Activity tapAndOpen(Activity activity, String label, Class<? extends Activity> target) {
        ActivityMonitor monitor = addMonitor(target.getName(), null, false);
        tap(activity, label);
        Activity opened = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        if (opened == null) throw new AssertionError("Did not navigate to " + target.getSimpleName());
        waitForIdleSync();
        return opened;
    }

    private void capture(String name) {
        waitForIdleSync();
        File directory = new File(getTargetContext().getExternalFilesDir(null), "migration-review");
        directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            Bitmap bitmap = getUiAutomation().takeScreenshot();
            if (bitmap == null) throw new IllegalStateException("No screenshot");
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private Activity launch(Class<? extends Activity> type, String key, String value) {
        Intent intent = new Intent(getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (key != null) intent.putExtra(key, value);
        Activity activity = startActivitySync(intent);
        waitForIdleSync();
        return activity;
    }

    private View findText(Activity activity, String text) {
        View[] found = {null};
        runOnMainSync(() -> found[0] = find(activity.getWindow().getDecorView(), text));
        return found[0];
    }

    private View find(View view, String text) {
        if (view instanceof TextView textView && text.contentEquals(textView.getText())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View match = find(group.getChildAt(i), text);
            if (match != null) return match;
        }
        return null;
    }

    private void tap(Activity activity, String text) {
        View view = findText(activity, text);
        if (view == null) {
            for (int attempt = 0; attempt < 10; attempt++) {
                android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                if (root != null) {
                    for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text.split("\n")[0])) {
                        String nodeText = node.getText() == null ? "" : node.getText().toString();
                        if (text.replaceAll("\\s+", " ").equals(nodeText.replaceAll("\\s+", " "))
                                && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                            waitForIdleSync();
                            return;
                        }
                    }
                }
                android.os.SystemClock.sleep(100);
            }
            throw new AssertionError("Missing button " + text);
        }
        runOnMainSync(() -> { if (view.isEnabled()) view.performClick(); else throw new AssertionError("Disabled " + text); });
        waitForIdleSync();
    }
}
