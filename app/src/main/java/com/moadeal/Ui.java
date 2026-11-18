package com.moadeal;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.InputStream;
import java.util.function.Consumer;

final class Ui {
    static final int BROWN = Color.rgb(147, 68, 35), ORANGE = Color.rgb(238, 126, 40);
    static final int INK = Color.rgb(61, 36, 24), CREAM = Color.rgb(255, 247, 238);
    private static final android.util.LruCache<String, Bitmap> images = new android.util.LruCache<>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    static int dp(android.content.Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Activity activity, ViewGroup parent, String text, int size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text); view.setTextSize(size); view.setTextColor(INK);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        parent.addView(view);
        return view;
    }

    static Button button(Activity activity, ViewGroup parent, String label, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    static void primary(Button button) {
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BROWN));
        button.setTextColor(Color.WHITE);
    }

    static LinearLayout row(Activity activity, ViewGroup parent) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new ViewGroup.LayoutParams(-1, -2));
        return row;
    }

    static LinearLayout column(Activity activity, ViewGroup parent) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        parent.addView(column, new ViewGroup.LayoutParams(-1, -2));
        return column;
    }

    static LinearLayout card(Activity activity, ViewGroup parent) {
        LinearLayout card = column(activity, parent);
        card.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
        card.setBackgroundResource(R.drawable.card_background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 12);
        card.setLayoutParams(params);
        return card;
    }

    static void weighted(View view, float weight) {
        view.setLayoutParams(new LinearLayout.LayoutParams(0, -2, weight));
    }

    static void image(Activity activity, ImageView view, String path) {
        view.setBackgroundColor(CREAM);
        if (path == null || path.isEmpty()) {
            view.setImageResource(R.drawable.ic_acorn);
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            view.setContentDescription("도토리 기본 이미지");
            return;
        }
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            Bitmap bitmap = images.get(path);
            if (bitmap == null) {
                try (InputStream input = path.startsWith("content:") || path.startsWith("file:")
                        ? activity.getContentResolver().openInputStream(Uri.parse(path)) : activity.getAssets().open(path)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(input, null, options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= 640) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    try (InputStream image = path.startsWith("content:") || path.startsWith("file:")
                            ? activity.getContentResolver().openInputStream(Uri.parse(path)) : activity.getAssets().open(path)) {
                        bitmap = BitmapFactory.decodeStream(image, null, options);
                    }
                }
                if (bitmap != null) images.put(path, bitmap);
            }
            if (bitmap == null) throw new IllegalArgumentException("사진을 읽을 수 없습니다.");
            view.setImageBitmap(bitmap);
        } catch (Exception e) {
            view.setImageResource(R.drawable.ic_acorn);
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
    }

    static ImageView photo(Activity activity, ViewGroup parent, String path, int height) {
        ImageView image = new ImageView(activity);
        parent.addView(image, new ViewGroup.LayoutParams(-1, dp(activity, height)));
        image(activity, image, path);
        return image;
    }

    static EditText field(Activity activity, LinearLayout fields, String label, String initial, int inputType, int id) {
        View item = activity.getLayoutInflater().inflate(R.layout.item_field, fields, false);
        ((TextView) item.findViewById(R.id.field_label)).setText(label);
        EditText input = item.findViewById(R.id.field_input);
        input.setId(id);
        input.setHint(label); input.setContentDescription(label);
        input.setInputType(inputType);
        input.setText(initial);
        fields.addView(item);
        return input;
    }

    static Spinner spinner(Activity activity, LinearLayout parent, String label, String[] options, int selected) {
        text(activity, parent, label, 14, false);
        Spinner spinner = new Spinner(activity);
        spinner.setContentDescription(label);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, options);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, selected));
        parent.addView(spinner, new ViewGroup.LayoutParams(-1, dp(activity, 48)));
        return spinner;
    }

    static String value(EditText field) { return field.getText().toString().trim(); }
    static boolean required(EditText field) {
        if (!value(field).isEmpty()) return true;
        field.setError("내용을 입력하세요."); field.requestFocus(); return false;
    }
    static int number(EditText field, int minimum) {
        try {
            int number = Integer.parseInt(value(field));
            if (number >= minimum) return number;
        } catch (NumberFormatException ignored) {}
        field.setError(minimum + " 이상 입력하세요."); field.requestFocus(); return -1;
    }

    static void watch(EditText input, Consumer<String> onChange) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { onChange.accept(text.toString()); }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    static void open(Activity activity, Class<? extends Activity> target, String key, String value) {
        Intent intent = new Intent(activity, target);
        if (key != null) intent.putExtra(key, value);
        activity.startActivity(intent);
    }

    static void productRow(BaseActivity activity, ViewGroup parent, Models.Product product, String description) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_row, parent, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(product.title);
        ((TextView) row.findViewById(R.id.row_description)).setText(description);
        image(activity, row.findViewById(R.id.row_photo), product.photo);
        row.setContentDescription(product.title + " " + description);
        row.setOnClickListener(view -> {
            if (product.sellerListing() && !activity.store.seller()) product.previewViews++;
            open(activity, DetailActivity.class, "product", product.id);
        });
        parent.addView(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(activity, 10); row.setLayoutParams(params);
    }

    static void grid(BaseActivity activity, LinearLayout parent, java.util.List<Models.Product> products,
            java.util.function.Function<Models.Product, String> detail) {
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(activity.getResources().getConfiguration().screenWidthDp >= 700 ? 3 : 2);
        parent.addView(grid, new ViewGroup.LayoutParams(-1, -2));
        for (Models.Product product : products) {
            View card = activity.getLayoutInflater().inflate(R.layout.item_product, grid, false);
            ((TextView) card.findViewById(R.id.product_title)).setText(product.title);
            ((TextView) card.findViewById(R.id.product_price)).setText(Store.won(product.price));
            ((TextView) card.findViewById(R.id.product_stage)).setText(product.stageLabel());
            ((TextView) card.findViewById(R.id.product_meta)).setText(product.region.label() + "\n참여 " + product.joined() + "/" + product.target + "명"
                + (detail == null ? "" : "\n" + detail.apply(product)));
            image(activity, card.findViewById(R.id.product_photo), product.photo);
            ImageView photo = card.findViewById(R.id.product_photo);
            photo.setContentDescription(product.title + " 사진");
            Button heart = card.findViewById(R.id.product_favorite);
            bindHeart(activity, heart, product);
            heart.setOnClickListener(view -> {
                activity.store.toggleFavorite(product);
                bindHeart(activity, heart, product);
                activity.onFavoriteChanged();
            });
            card.setOnClickListener(view -> {
                if (product.sellerListing() && !activity.store.seller()) product.previewViews++;
                open(activity, DetailActivity.class, "product", product.id);
            });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0; params.height = -2;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(activity, 3), 0, dp(activity, 3), dp(activity, 14));
            grid.addView(card, params);
        }
    }

    static void bindHeart(BaseActivity activity, Button button, Models.Product product) {
        boolean favorite = activity.store.favorite(product);
        button.setText(favorite ? "♥" : "♡");
        button.setTextColor(favorite ? ORANGE : BROWN);
        button.setContentDescription(product.title + (favorite ? " 관심상품에서 빼기" : " 관심상품에 담기"));
    }
}
