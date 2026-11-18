package com.moadeal;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

public class ViewsChart extends View {
    private Models.Product product;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public ViewsChart(Context context, AttributeSet attributes) { super(context, attributes); }
    void setProduct(Models.Product product) {
        this.product = product;
        setContentDescription("최근 7일 조회수 " + product.views() + "회" + (product.remote ? "" : ". 예시 통계"));
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (product == null) return;
        int max = 1;
        for (int i = 0; i < 7; i++) max = Math.max(max, product.viewsAt(i));
        float width = getWidth() / 7f, baseline = getHeight() - Ui.dp(getContext(), 28);
        for (int i = 0; i < 7; i++) {
            int count = product.viewsAt(i);
            float height = (baseline - Ui.dp(getContext(), 25)) * count / max;
            float center = width * (i + .5f), halfBar = Math.min(width / 4, Ui.dp(getContext(), 12));
            paint.setColor(i == 6 ? Ui.BROWN : Ui.ORANGE);
            canvas.drawRoundRect(center - halfBar, baseline - height, center + halfBar, baseline, 5, 5, paint);
            paint.setColor(Ui.INK); paint.setTextSize(Ui.dp(getContext(), 11)); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(Integer.toString(count), center, baseline - height - 8, paint);
            canvas.drawText(i == 6 ? "오늘" : (6 - i) + "일 전", center, baseline + Ui.dp(getContext(), 20), paint);
        }
    }
}
