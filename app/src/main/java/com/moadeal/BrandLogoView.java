package com.moadeal;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

public class BrandLogoView extends View {
    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public BrandLogoView(Context context, AttributeSet attributes) {
        super(context, attributes);
        Bitmap loaded = null;
        try (var input = context.getAssets().open("brand/logo.png")) {
            loaded = BitmapFactory.decodeStream(input);
        } catch (Exception ignored) {}
        bitmap = loaded;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        // Same logo crop as the Flutter brand widget.
        Rect crop = new Rect(Math.round(bitmap.getWidth() * .1725f), Math.round(bitmap.getHeight() * .375f),
            Math.round(bitmap.getWidth() * .9225f), Math.round(bitmap.getHeight() * .625f));
        canvas.drawBitmap(bitmap, crop, new RectF(0, 0, getWidth(), getHeight()), paint);
    }
}
