package com.moadeal;

import android.os.Bundle;

public class CommunityActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) { super.onCreate(state); title("커뮤니티"); }
    @Override protected void onResume() { super.onResume(); scroll(); new CommunityPanel(this, content).render(); }
}
