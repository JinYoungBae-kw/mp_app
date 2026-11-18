package com.moadeal;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.widget.*;

public class AttendanceActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) { super.onCreate(state); render(); }

    private void render() {
        title("출석체크"); scroll();
        Ui.photo(this, content, "brand/forest-home.png", 275).setContentDescription("도토리를 들고 숲속 나무집으로 돌아오는 모아딜 다람쥐");
        int count = store.attendance().size(), percent = Math.min(100, count * 10);
        boolean checked = store.attendance().contains(Store.today());
        LinearLayout card = Ui.card(this, content); card.setBackgroundColor(Ui.CREAM);
        Ui.text(this, card, "출석 진행률     " + percent + "%", 25, true).setTextColor(Ui.BROWN);
        Ui.text(this, card, count == 0 ? "첫 출석을 시작해요" : count + "일차 출석", 17, true);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); card.addView(progress);
        ObjectAnimator.ofInt(progress, "progress", 0, percent).setDuration(360).start();
        Ui.text(this, card, percent == 100 ? "출석 목표를 모두 채웠어요." : "한국 시간으로 하루에 한 번, 출석하면 10%씩 올라가요.", 16, false);
        Button checkIn = primary(checked ? "오늘 출첵 완료" : percent == 100 ? "출석 목표 달성" : "출첵!", () -> { store.checkIn(); render(); });
        checkIn.setEnabled(!checked && percent < 100);
    }
    @Override void onDayChanged() { render(); }
}
