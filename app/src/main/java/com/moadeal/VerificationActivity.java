package com.moadeal;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;

public class VerificationActivity extends BaseActivity {
    private int step;
    private boolean attached;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) { step = state.getInt("step"); attached = state.getBoolean("attached"); }
        render();
    }

    private void render() {
        title("판매자 가입 · 사업자 인증"); scroll();
        Ui.text(this, content, (step + 1) + "/3 · 가입 화면 체험", 15, false);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(3); progress.setProgress(step + 1); content.addView(progress);
        Ui.text(this, content, new String[]{"사업자등록증 제출", "심사 대기", "승인 완료"}[step], 25, true);
        Ui.text(this, content, new String[]{
            "화면 체험에는 예시 사업자등록증을 사용합니다.",
            "등록한 서류를 확인하는 단계입니다. 예시 심사 결과를 확인할 수 있어요.",
            "예시 판매자 계정으로 상품 등록과 판매관리를 체험할 수 있어요."
        }[step], 16, false);
        if (step == 0) Ui.button(this, content, attached ? "예시 사업자등록증 첨부됨" : "예시 사업자등록증 첨부", () -> { attached = true; render(); });
        Button next = primary(new String[]{"심사 요청", "예시 심사 결과 보기", "판매자 홈으로"}[step], () -> {
            if (step == 2) {
                startActivity(new Intent(this, HomeActivity.class)); finish();
            } else { step++; render(); }
        });
        next.setEnabled(step != 0 || attached);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("step", step); state.putBoolean("attached", attached);
        super.onSaveInstanceState(state);
    }
}
