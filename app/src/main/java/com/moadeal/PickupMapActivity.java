package com.moadeal;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.naver.maps.geometry.LatLng;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.MapView;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.overlay.Marker;
import java.util.List;

public class PickupMapActivity extends BaseActivity {
    static final String EXTRA_ADDRESS = "manualPickupAddress";
    private static final long SEARCH_DELAY_MS = 350;
    private static final int MAX_ADDRESS_LENGTH = 300;
    private static final List<PickupSearch.Place> EXAMPLE_PLACES = List.of(
        new PickupSearch.Place("월계동 과일가게", "서울 노원구 월계동", Double.NaN, Double.NaN),
        new PickupSearch.Place("광운대 앞 베이커리", "서울 노원구 광운로", Double.NaN, Double.NaN));

    private TextView preview;
    private MapView mapView;
    private NaverMap naverMap;
    private Marker marker;
    private LatLng markerPosition;
    private Runnable scheduledSearch;
    private int lookupVersion;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        title("픽업 장소 검색");
        View page = getLayoutInflater().inflate(R.layout.page_pickup_map, body, false);
        body.addView(page);
        EditText search = page.findViewById(R.id.pickup_query);
        Button searchButton = page.findViewById(R.id.pickup_search_button);
        FrameLayout mapFrame = page.findViewById(R.id.pickup_map_frame);
        LinearLayout addressFields = page.findViewById(R.id.pickup_address_fields);
        preview = page.findViewById(R.id.pickup_preview);
        preview.setTextColor(Ui.INK);
        boolean exampleMode = accounts == null;
        boolean mapEnabled = !exampleMode && !BuildConfig.NAVER_MAP_CLIENT_ID.isEmpty();

        if (mapEnabled) {
            mapView = new MapView(this);
            mapFrame.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
            mapView.onCreate(null);
            mapView.getMapAsync(map -> {
                if (isFinishing() || isDestroyed()) return;
                naverMap = map;
                map.setOnMapClickListener((point, location) -> lookUpAddress(location));
                map.setOnSymbolClickListener(symbol -> {
                    lookUpAddress(symbol.getPosition());
                    return true;
                });
                updateMarker(true);
            });
        } else {
            mapFrame.setVisibility(View.GONE);
        }

        String initialAddress = getIntent().getStringExtra(EXTRA_ADDRESS);
        EditText address = Ui.field(this, addressFields, "픽업 주소 직접 입력", initialAddress == null ? "" : initialAddress,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, R.id.offer_pickup_address);
        address.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_ADDRESS_LENGTH)});
        primary("주소 적용", () -> {
            if (!Ui.required(address)) return;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_ADDRESS, Ui.value(address)));
            finish();
        });

        if (exampleMode) {
            Ui.watch(search, query -> showExamples(query.trim()));
            showExamples("");
        } else if (PickupSearch.isConfigured()) {
            TextView attribution = Ui.text(this, footer, "장소 검색 출처 · NAVER 오픈 API", 12, false);
            attribution.setGravity(Gravity.CENTER_HORIZONTAL);
            preview.setText(mapEnabled ? "검색어를 입력하세요." : "네이버 지도 설정을 확인해 주세요.");
            Ui.watch(search, query -> scheduleSearch(query.trim(), SEARCH_DELAY_MS));
        } else {
            preview.setText("픽업 장소 검색 서비스가 아직 설정되지 않았어요.");
            searchButton.setEnabled(false);
        }
        searchButton.setOnClickListener(view -> submitSearch(search, exampleMode));
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            submitSearch(search, exampleMode);
            return true;
        });
        page.requestFocus();
    }

    private void showExamples(String query) {
        for (PickupSearch.Place place : EXAMPLE_PLACES) {
            if ((place.name() + place.address()).contains(query)) {
                preview.setText(place.address());
                return;
            }
        }
        preview.setText("검색 결과가 없습니다.");
    }

    private void submitSearch(EditText search, boolean exampleMode) {
        hideKeyboard();
        String query = Ui.value(search);
        if (exampleMode) {
            showExamples(query);
        } else if (PickupSearch.isConfigured()) {
            scheduleSearch(query, 0);
        }
    }

    private void scheduleSearch(String query, long delayMs) {
        int version = cancelPendingLookup();
        showMarker(null, false);
        if (query.isEmpty()) {
            preview.setText("검색어를 입력하세요.");
            return;
        }
        preview.setText("장소를 검색하는 중이에요.");
        String uid = accounts.currentUid();
        scheduledSearch = () -> {
            scheduledSearch = null;
            if (!canHandleTrade(uid)) return;
            PickupSearch.search(accounts, query).addOnCompleteListener(result -> {
                if (lookupVersion != version || !canHandleTrade(uid)) return;
                if (!result.isSuccessful()) {
                    Exception error = result.getException();
                    preview.setText(error instanceof PickupSearch.SearchException
                        ? error.getMessage() : "장소를 검색하지 못했어요. 잠시 후 다시 검색해 주세요.");
                    return;
                }
                showResult(result.getResult(), version);
            });
        };
        handler.postDelayed(scheduledSearch, delayMs);
    }

    private int cancelPendingLookup() {
        if (scheduledSearch != null) {
            handler.removeCallbacks(scheduledSearch);
            scheduledSearch = null;
        }
        return ++lookupVersion;
    }

    private void showResult(PickupSearch.Place place, int version) {
        if (place == null) {
            preview.setText("검색 결과가 없습니다.");
            return;
        }
        LatLng position = place.hasCoordinates() ? new LatLng(place.latitude(), place.longitude()) : null;
        showMarker(position, true);
        if (place.address().isEmpty() && position != null) {
            requestAddress(position, version);
        } else {
            preview.setText(place.address().isEmpty() ? "표시할 주소가 없습니다." : place.address());
        }
    }

    private void lookUpAddress(LatLng location) {
        int version = cancelPendingLookup();
        hideKeyboard();
        showMarker(location, false);
        requestAddress(location, version);
    }

    private void requestAddress(LatLng location, int version) {
        preview.setText("주소 확인 중이에요.");
        String uid = accounts == null ? null : accounts.currentUid();
        PickupSearch.addressAt(accounts, location.latitude, location.longitude).addOnCompleteListener(result -> {
            if (version != lookupVersion || !canHandleTrade(uid)) return;
            if (!result.isSuccessful()) {
                Exception error = result.getException();
                preview.setText(error instanceof PickupSearch.SearchException
                    ? error.getMessage() : "선택한 위치의 주소를 확인하지 못했어요.");
                return;
            }
            String address = result.getResult();
            preview.setText(address.isEmpty() ? "이 위치의 주소가 없습니다." : address);
        });
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (focus != null && keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void showMarker(LatLng position, boolean center) {
        markerPosition = position;
        if (position == null) {
            if (marker != null) {
                marker.setMap(null);
                marker = null;
            }
            return;
        }
        updateMarker(center);
    }

    private void updateMarker(boolean center) {
        if (naverMap == null || markerPosition == null) return;
        if (marker == null) {
            marker = new Marker(markerPosition);
            marker.setOnClickListener(overlay -> {
                hideKeyboard();
                return true;
            });
            marker.setMap(naverMap);
        } else {
            marker.setPosition(markerPosition);
        }
        if (center) naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(markerPosition, 16));
    }

    @Override protected void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }

    @Override protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override protected void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (mapView != null) mapView.onStop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelPendingLookup();
        showMarker(null, false);
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }
}
