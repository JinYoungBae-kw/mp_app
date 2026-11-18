package com.moadeal;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class PickupSearch {
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int TIMEOUT_MILLIS = 6_000;
    private static final URI ENDPOINT = parseEndpoint(BuildConfig.PICKUP_SEARCH_URL);
    private static final Executor NETWORK = Executors.newFixedThreadPool(2);

    record Place(String name, String address, double latitude, double longitude) {
        boolean hasCoordinates() {
            return latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
        }
    }

    static final class SearchException extends IOException {
        SearchException(String message) { super(message); }
    }

    static boolean isConfigured() {
        return ENDPOINT != null;
    }

    static Task<Place> search(FirebaseAccounts accounts, String query) {
        String keyword = query.trim();
        if (keyword.isEmpty()) return Tasks.forResult(null);
        if (!isConfigured()) return Tasks.forException(new SearchException("픽업 장소 검색이 아직 설정되지 않았어요."));
        if (accounts == null) return Tasks.forException(new SearchException("로그인 후 장소를 검색해 주세요."));
        if (keyword.length() > MAX_QUERY_LENGTH) {
            return Tasks.forException(new SearchException("검색어를 100자 이내로 입력해 주세요."));
        }

        return accounts.idToken().onSuccessTask(token -> Tasks.call(NETWORK, () -> {
            String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name());
            URI url = ENDPOINT.resolve("/places?q=" + encoded);
            return parsePlace(request(url, token, null));
        }));
    }

    static Task<String> addressAt(FirebaseAccounts accounts, double latitude, double longitude) {
        if (!isConfigured()) return Tasks.forException(new SearchException("지도 주소 확인 서비스가 아직 설정되지 않았어요."));
        if (accounts == null) return Tasks.forException(new SearchException("로그인 후 주소를 확인해 주세요."));
        return accounts.idToken().onSuccessTask(token -> Tasks.call(NETWORK, () -> {
            JSONObject point = new JSONObject().put("lat", latitude).put("lng", longitude);
            JSONObject payload = request(ENDPOINT.resolve("/address"), token, point);
            if (payload.opt("address") instanceof String address) return address;
            throw new SearchException("지도에서 받은 주소를 읽을 수 없어요.");
        }));
    }

    private static URI parseEndpoint(String configured) {
        try {
            URI endpoint = URI.create(configured.trim());
            if ("https".equalsIgnoreCase(endpoint.getScheme()) && endpoint.getHost() != null
                && endpoint.getRawUserInfo() == null && endpoint.getRawQuery() == null
                && endpoint.getRawFragment() == null && "/places".equals(endpoint.getRawPath())) {
                return endpoint;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private static JSONObject request(URI endpoint, String token, JSONObject point) throws IOException, JSONException {
        URL url = endpoint.toURL();
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-Moadeal-Pickup-Mode", "reference");
            if (point != null) {
                byte[] body = point.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            } else {
                connection.setRequestMethod("GET");
            }

            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK) throw responseError(status, point != null);

            try (InputStream response = connection.getInputStream()) {
                return new JSONObject(readResponse(response));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static SearchException responseError(int status, boolean addressLookup) {
        return switch (status) {
            case 401 -> new SearchException("로그인을 다시 한 뒤 이용해 주세요.");
            case 403 -> new SearchException("이 계정은 픽업 장소 확인을 사용할 수 없어요.");
            case 404 -> new SearchException("픽업 장소 검색 서버의 배포 상태를 확인해 주세요.");
            case 429 -> new SearchException("검색 요청이 많아요. 잠시 후 다시 검색해 주세요.");
            case 503 -> new SearchException(addressLookup ? "네이버 지도 주소 확인 설정을 확인해 주세요."
                : "네이버 장소 검색 서비스 설정을 확인해 주세요.");
            default -> new SearchException(addressLookup ? "선택한 위치의 주소를 확인하지 못했어요."
                : "장소 검색을 완료하지 못했어요. 잠시 후 다시 검색해 주세요.");
        };
    }

    private static String readResponse(InputStream response) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        for (int count; (count = response.read(buffer)) != -1; ) {
            if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                throw new SearchException("장소 검색 결과를 읽을 수 없어요.");
            }
            bytes.write(buffer, 0, count);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static Place parsePlace(JSONObject payload) throws JSONException, SearchException {
        if (!"naver".equals(payload.opt("provider"))) {
            throw new SearchException("네이버 장소 검색 구성을 확인해 주세요.");
        }
        JSONArray rows = payload.getJSONArray("places");
        JSONObject row = rows.optJSONObject(0);
        if (row != null && row.opt("name") instanceof String name && row.opt("address") instanceof String address
            && (!name.isEmpty() || !address.isEmpty())) {
            return new Place(name, address, row.optDouble("latitude"), row.optDouble("longitude"));
        }
        return null;
    }
}
