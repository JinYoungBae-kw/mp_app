package com.moadeal;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.firestore.ListenerRegistration;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import static com.moadeal.Models.*;

final class Store {
    private static final String DEMO_BUYER_PREFIX = "demo.buyer.";

    final Context context;
    final SharedPreferences preferences;
    final List<Product> products = new ArrayList<>();
    final List<Room> rooms = new ArrayList<>();
    final List<Region> demoRegions = new ArrayList<>();
    private final Map<String, String> demoBuyerIds = new TreeMap<>();
    private final Map<String, String> firebaseNames = new HashMap<>();
    private FirebaseAccounts.Profile firebaseProfile;
    private FirebaseTrades trades;
    private final Map<Runnable, Consumer<Exception>> tradeObservers = new LinkedHashMap<>();
    private final List<ListenerRegistration> tradeListeners = new ArrayList<>();
    private Set<String> tradeMemberships = Collections.emptySet();
    private final Map<String, Map<Long, Integer>> tradeLikes = new HashMap<>();
    private final Map<String, Map<Long, Integer>> tradeViews = new HashMap<>();
    private int tradeObservation;
    JSONObject regions;
    String account = BUYER;

    Store(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("moadeal_native", Context.MODE_PRIVATE);
        for (var entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(DEMO_BUYER_PREFIX) && entry.getValue() instanceof String name) {
                demoBuyerIds.put(name, entry.getKey().substring(DEMO_BUYER_PREFIX.length()));
            }
        }
        try {
            regions = readJson("data/korea_regions.json");
            seedCatalog();
            seedRooms();
            restoreRegionalFavorites();
        } catch (Exception e) {
            throw new IllegalStateException("모아딜 기본 데이터를 읽을 수 없습니다.", e);
        }
    }

    JSONObject readJson(String path) throws Exception {
        try (var stream = context.getAssets().open(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONObject(output.toString("UTF-8"));
        }
    }

    static String today() { return LocalDate.now(ZoneId.of("Asia/Seoul")).toString(); }
    static long epochDay() { return LocalDate.now(ZoneId.of("Asia/Seoul")).toEpochDay(); }
    static String date(long time) {
        return java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("M월 d일"));
    }
    static String won(int price) { return String.format(java.util.Locale.KOREA, "%,d원", price); }
    String accountId(String account) {
        if (SELLER.equals(account)) return "moadeal_seller";
        String firebaseName = firebaseNames.get(account);
        if (firebaseName != null) return firebaseName;
        return preferences.getString(DEMO_BUYER_PREFIX + account, "moadeal_host");
    }

    void applyFirebaseProfile(FirebaseAccounts.Profile profile) {
        if (!hasFirebaseProfile(profile.uid)) clearFirebaseTrades();
        firebaseProfile = profile;
        account = "firebase:" + profile.uid;
        firebaseNames.put(account, profile.name);
        startTradeObservation();
    }

    boolean hasFirebaseProfile(String uid) {
        return firebaseProfile != null && firebaseProfile.uid.equals(uid);
    }

    void clearFirebaseProfile() {
        firebaseProfile = null;
        account = BUYER;
        clearFirebaseTrades();
    }

    void useFirebaseTrades(FirebaseTrades repository) { trades = repository; }

    void addTradeObserver(Runnable changed, Consumer<Exception> error) {
        tradeObservers.put(changed, error);
        startTradeObservation();
    }

    void removeTradeObserver(Runnable changed) {
        tradeObservers.remove(changed);
        if (tradeObservers.isEmpty()) stopTradeObservation();
    }

    private void startTradeObservation() {
        if (trades == null || firebaseProfile == null || tradeObservers.isEmpty() || !tradeListeners.isEmpty()) return;
        int observation = ++tradeObservation;
        boolean[] errorShown = {false};
        Consumer<Exception> reportError = error -> {
            if (observation != tradeObservation || errorShown[0]) return;
            errorShown[0] = true;
            for (Consumer<Exception> callback : new ArrayList<>(tradeObservers.values())) callback.accept(error);
        };
        tradeListeners.add(trades.listenProducts(snapshot -> {
            if (observation != tradeObservation) return;
            applyTradeProducts(snapshot);
            notifyTradeObservers();
        }, reportError));
        tradeListeners.add(trades.listenMemberships(snapshot -> {
            if (observation != tradeObservation) return;
            tradeMemberships = snapshot;
            for (Product product : products) if (product.remote) applyTradeMembership(product);
            notifyTradeObservers();
        }, reportError));
        if (seller()) {
            tradeListeners.add(trades.listenViews(snapshot -> {
                if (observation != tradeObservation) return;
                tradeViews.clear();
                for (FirebaseTrades.DailyViews entry : snapshot) {
                    tradeViews.computeIfAbsent(entry.productId(), unused -> new HashMap<>()).put(entry.day(), entry.views());
                }
                for (Product product : products) if (product.remote) applyTradeViews(product);
                notifyTradeObservers();
            }, reportError));
        } else {
            tradeListeners.add(trades.listenLikes(snapshot -> {
                if (observation != tradeObservation) return;
                tradeLikes.clear();
                for (FirebaseTrades.DailyLikes entry : snapshot) {
                    tradeLikes.computeIfAbsent(entry.productId(), unused -> new HashMap<>()).put(entry.day(), entry.likes());
                }
                notifyTradeObservers();
            }, reportError));
        }
    }

    private void applyTradeProducts(List<Product> snapshot) {
        Map<String, Product> previous = new HashMap<>();
        for (Product product : products) if (product.remote) previous.put(product.id, product);
        products.removeIf(product -> product.remote);
        for (Product product : snapshot) {
            Product existing = previous.get(product.id);
            if (existing != null && product.hasRemotePhoto) product.photo = existing.photo;
            applyTradeMembership(product);
            applyTradeViews(product);
        }
        products.addAll(0, snapshot);
    }

    void putFirebaseProduct(Product product) {
        if (firebaseProfile == null || !account.equals(product.owner) || product(product.id) != null) return;
        product.remote = true;
        product.hasRemotePhoto = !product.photo.isEmpty();
        product.remoteParticipantCount = product.sellerListing() ? 0 : 1;
        applyTradeMembership(product);
        products.add(0, product);
        notifyTradeObservers();
    }

    private void applyTradeMembership(Product product) {
        product.members.clear();
        if (tradeMemberships.contains(product.id) || !product.sellerListing() && account.equals(product.owner)) {
            product.members.add(account);
        }
    }

    private void applyTradeViews(Product product) {
        product.remoteViews.clear();
        Map<Long, Integer> days = tradeViews.get(product.id);
        if (days != null) product.remoteViews.putAll(days);
    }

    private void notifyTradeObservers() {
        for (Runnable callback : new ArrayList<>(tradeObservers.keySet())) callback.run();
    }

    private void stopTradeObservation() {
        tradeObservation++;
        for (ListenerRegistration listener : tradeListeners) listener.remove();
        tradeListeners.clear();
    }

    private void clearFirebaseTrades() {
        stopTradeObservation();
        tradeMemberships = Collections.emptySet();
        tradeLikes.clear();
        tradeViews.clear();
        products.removeIf(product -> product.remote);
    }

    Map<String, String> demoBuyers() {
        Map<String, String> buyers = new LinkedHashMap<>();
        demoBuyerIds.forEach((name, id) -> buyers.put(id, name));
        return buyers;
    }

    String registerDemoBuyer(String name) {
        String existing = demoBuyerIds.get(name);
        if (existing != null) return existing;

        String id = "buyer-" + UUID.randomUUID();
        preferences.edit().putString(DEMO_BUYER_PREFIX + id, name)
            .putString("profile.name." + id, name).apply();
        demoBuyerIds.put(name, id);
        return id;
    }

    boolean seller() {
        return firebaseProfile == null ? SELLER.equals(account) : FirebaseAccounts.SELLER_ROLE.equals(firebaseProfile.role);
    }

    Product product(String id) {
        for (Product product : products) if (product.id.equals(id)) return product;
        return null;
    }

    private Product seed(String id, String title, int price, int target, String photo, Region region) {
        Product product = new Product();
        product.id = id; product.title = title; product.price = price; product.target = target;
        product.photo = photo; product.region = region.copy(); product.members.add(BUYER);
        products.add(product);
        return product;
    }

    private void seedCatalog() throws Exception {
        JSONObject catalog = readJson("data/catalog.json");
        JSONArray regionData = catalog.getJSONArray("regions");
        for (int i = 0; i < regionData.length(); i++) {
            JSONArray row = regionData.getJSONArray(i);
            demoRegions.add(new Region(row.getString(0), row.getString(1), row.getString(2)));
        }
        Product strawberry = seed("sample-strawberries", "딸기 1상자", 8000, 2, "requests/strawberries.jpg", demoRegions.get(0));
        strawberry.comparisonPrice = 11000; strawberry.likes = 29; strawberry.likesDate = today();
        Product dessert = seed("sample-dessert", "디저트 박스", 12000, 2, "requests/cookies.jpg", demoRegions.get(0));
        dessert.sampleParticipants = 1; dessert.comparisonPrice = 15000; dessert.likes = 21; dessert.likesDate = today();
        dessert.offers.add(new Offer(10500, 2, "광운대 앞 베이커리", "서울 노원구 광운로", System.currentTimeMillis() + 3L * 86400000, null));
        dessert.offers.add(new Offer(11000, 3, "월계동 과일가게", "서울 노원구 월계동", System.currentTimeMillis() + 2L * 86400000, SELLER));
        seedSeller("seller-apples", "햇사과 2kg", 9500, 6, 5, "requests/food-0.jpg",
            "아삭한 햇사과를 2kg씩 포장해 드려요. 공동구매가 성사되면 월계동 과일가게에서 수령할 수 있어요.",
            new int[]{9, 13, 16, 12, 23, 29, 34}, 0)
            .questions.add(new Question("수령할 때 장바구니가 필요한가요?", "상자에 담아 드려요. 그대로 가져가셔도 됩니다."));
        seedSeller("seller-tissues", "각티슈 6입 세트", 8900, 8, 3, "requests/household-1.jpg",
            "한 상자에 각티슈 6개가 들어 있어요. 매장에서 포장된 세트로 받아 가시면 됩니다.", new int[]{4, 6, 5, 12, 14, 10, 16}, 2);
        seedSeller("seller-bread", "우리밀 식빵 2개", 6900, 6, 6, "requests/food-26.jpg",
            "우리밀 식빵 두 개를 한 세트로 준비했어요.", new int[]{10, 15, 19, 23, 27, 24, 16}, 0);
        JSONObject categories = catalog.getJSONObject("products");
        for (int i = 0; i < 30; i++) {
            for (int category = 0; category < 3; category++) {
                JSONArray items = categories.getJSONArray(CATEGORY_KEYS[category]);
                if (i >= items.length()) continue;
                JSONArray item = items.getJSONArray(i);
                Product p = seed("sample-" + CATEGORY_KEYS[category] + "-" + i, item.getString(0), item.getInt(1), 6,
                    "requests/" + CATEGORY_KEYS[category] + "-" + i + ".jpg", demoRegions.get(i / 2 + (category == 0 ? 1 : 0)));
                p.category = category; p.sampleParticipants = 1 + i % 4;
                p.likes = (i * 11 + category * 7) % 26; p.likesDate = today();
                p.comparisonPrice = i % 3 != 2 ? p.price + 1200 + i % 5 * 500 : 0;
            }
        }
    }

    private Product seedSeller(String id, String title, int price, int target, int joined,
            String photo, String description, int[] views, int category) {
        Product p = seed(id, title, price, target, photo, demoRegions.get(0));
        p.members.clear(); p.owner = SELLER; p.sellerProduct = true; p.sampleParticipants = joined;
        p.description = description; p.sampleViews = views; p.category = category;
        p.deadline = System.currentTimeMillis() + 6L * 86400000;
        return p;
    }

    private void seedRooms() {
        addRoom("월계동 장바구니", "과일과 채소, 같이 살 이웃을 찾아요.", "서울 노원구", "식품", "requests/strawberries.jpg", 12,
            new Message("이번 주에는 딸기나 사과 어때요?", null, "월계동 이웃"),
            new Message("사과면 저도 같이 사고 싶어요!", null, "도토리상점"));
        addRoom("우리 동네 살림 모임", "자주 쓰는 생필품을 함께 고르고 나눠요.", "서울 송파구", "생필품", "requests/household-0.jpg", 8,
            new Message("이번에는 각티슈를 같이 구매해 볼까요?", null, "모아딜 이웃"));
        addRoom("알뜰 뷰티 공동구매", "스킨케어와 세안용품 특가를 공유해요.", "인천 연수구", "미용", "requests/beauty-0.jpg", 16,
            new Message("요즘 잘 쓰는 보습 제품 추천해 주세요.", null, "모아딜 이웃"));
        addRoom("동네 가게 소식", "우리 동네 가게의 모아딜 소식을 모아요.", "서울 노원구", "동네가게", "requests/cookies.jpg", 21,
            new Message("오늘 갓 구운 식빵이 나왔어요.", null, "월계동 베이커리"));
    }

    Room addRoom(String name, String description, String area, String category, String photo, int count, Message... messages) {
        Room room = new Room();
        room.id = "room-" + rooms.size() + "-" + name;
        room.name = name; room.description = description; room.area = area; room.category = category;
        room.photo = photo; room.sampleMembers = count; room.messages.addAll(Arrays.asList(messages));
        rooms.add(room);
        return room;
    }

    Room room(String id) {
        for (Room room : rooms) if (room.id.equals(id)) return room;
        return null;
    }

    String profileName() {
        return firebaseProfile == null ? preferences.getString("profile.name." + account, "") : firebaseProfile.name;
    }
    void saveProfile(String name) { preferences.edit().putString("profile.name." + account, name).apply(); }

    String favoriteKey(String account, Product product) { return "favorite." + account + "." + product.id; }
    String favoriteDate(String account, Product product) { return preferences.getString(favoriteKey(account, product), ""); }
    boolean favorite(Product product) { return !favoriteDate(account, product).isEmpty(); }
    void toggleFavorite(Product product) {
        preferences.edit().putString(favoriteKey(account, product), favorite(product) ? "" : today()).apply();
    }
    int likesToday(Product product) {
        if (product.remote) {
            Map<Long, Integer> days = tradeLikes.get(product.id);
            return days == null ? 0 : days.getOrDefault(epochDay(), 0);
        }
        String date = today();
        int count = (date.equals(product.likesDate) ? product.likes : 0)
            + (date.equals(favoriteDate(BUYER, product)) ? 1 : 0)
            + (date.equals(favoriteDate(SELLER, product)) ? 1 : 0);
        for (String buyer : demoBuyerIds.values()) if (date.equals(favoriteDate(buyer, product))) count++;
        for (String uid : firebaseNames.keySet()) if (date.equals(favoriteDate(uid, product))) count++;
        return count;
    }

    Set<String> attendance() {
        return new HashSet<>(preferences.getStringSet("attendance." + account, Collections.emptySet()));
    }
    boolean checkIn() {
        Set<String> dates = attendance();
        if (dates.size() >= 10 || !dates.add(today())) return false;
        preferences.edit().putStringSet("attendance." + account, dates).apply();
        return true;
    }

    static Integer special(Product p) {
        long day = ChronoUnit.DAYS.between(LocalDate.of(2026, 11, 16), LocalDate.parse(today()));
        if ("sample-strawberries".equals(p.id)) return 7500;
        int food = Math.floorMod(day, 28), beauty = Math.floorMod(day * 3, 30), household = Math.floorMod(day * 7, 30);
        if (p.id.equals("sample-food-" + food) || p.id.equals("sample-beauty-" + beauty)
                || p.id.equals("sample-beauty-" + (beauty + 11) % 30) || p.id.equals("sample-household-" + household)) return p.price - 700;
        return null;
    }

    List<Product> visible(Region region, int category, int stage, String query, boolean favorites) {
        String search = query.toLowerCase(java.util.Locale.KOREA);
        List<Product> matches = new ArrayList<>();
        for (Product p : products) {
            if (favorites) {
                if (favorite(p)) matches.add(p);
            } else if ((!region.city.isEmpty() || !p.id.startsWith("regional|"))
                    && region.includes(p.region) && (category < 0 || category == p.category)
                    && (stage < 0 || (stage == 0 ? p.recruiting() : stage == p.stage()))
                    && (p.title.toLowerCase(java.util.Locale.KOREA).contains(search) || p.region.label().contains(search))) matches.add(p);
        }
        if (favorites || region.city.isEmpty()) return matches;
        if (category >= 0) return new ArrayList<>(matches.subList(0, Math.min(6, matches.size())));
        List<Product> result = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            int count = 0;
            for (Product p : matches) if (p.category == c && count++ < 2) result.add(p);
        }
        return result;
    }

    void fillRegion(Region region) {
        if (region.city.isEmpty()) return;
        int seed = 0;
        for (char code : region.label().toCharArray()) seed = (seed * 31 + code) % 997;
        for (int c = 0; c < 3; c++) {
            Set<String> available = new HashSet<>();
            List<Product> templates = new ArrayList<>();
            for (Product p : products) {
                if (p.category != c) continue;
                if (region.includes(p.region)) available.add(p.title);
                if (p.id.startsWith("sample-")) templates.add(p);
            }
            for (int i = 0; available.size() < 3 && i < templates.size(); i++) {
                Product template = templates.get((seed + i) % templates.size());
                if (available.add(template.title)) addRegional(region, template);
            }
        }
    }

    private void addRegional(Region region, Product template) {
        String id = "regional|" + region.city + "|" + region.district + "|" + region.neighborhood + "|" + template.id;
        if (product(id) != null) return;
        Product p = seed(id, template.title, template.price, 6, template.photo, region);
        p.category = template.category; p.sampleParticipants = 2; p.comparisonPrice = template.comparisonPrice;
    }

    private void restoreRegionalFavorites() {
        for (String key : preferences.getAll().keySet()) {
            int index = key.indexOf("regional|");
            if (!key.startsWith("favorite.") || index < 0) continue;
            String[] parts = key.substring(index).split("\\|", -1);
            if (parts.length != 5) continue;
            Product template = product(parts[4]);
            if (template != null) addRegional(new Region(parts[1], parts[2], parts[3]), template);
        }
    }

    List<String> cities() { return names(regions); }
    List<String> districts(String city) { return names(regions.optJSONObject(city)); }
    List<String> neighborhoods(Region region) {
        JSONObject districts = regions.optJSONObject(region.city);
        JSONArray names = districts == null ? null : districts.optJSONArray(region.district);
        List<String> result = new ArrayList<>();
        if (names != null) for (int i = 0; i < names.length(); i++) result.add(names.optString(i));
        return result;
    }
    private List<String> names(JSONObject object) {
        List<String> result = new ArrayList<>();
        if (object != null) object.keys().forEachRemaining(key -> { if (!key.isEmpty()) result.add(key); });
        return result;
    }
}
