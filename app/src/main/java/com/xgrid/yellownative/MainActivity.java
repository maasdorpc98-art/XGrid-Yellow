package com.xgrid.yellownative;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Stage 1 bootstrap activity. The GIF/search/pose modules are NOT implemented here.
 * This temporary screen only proves the native app package can compile and launch.
 */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(18, 20, 18);
    private static final int SURFACE = Color.rgb(29, 31, 27);
    private static final int BORDER = Color.rgb(54, 58, 48);
    private static final int YELLOW = Color.rgb(255, 207, 50);
    private static final int TEXT = Color.rgb(242, 242, 239);
    private static final int MUTED = Color.rgb(172, 177, 169);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BACKGROUND);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int outer = dp(22);
        page.setPadding(outer, dp(42), outer, dp(32));
        scroller.addView(page);

        addText(page, "XGRID  /  YELLOW", 15, YELLOW, true, 12);
        addText(page, "Native Foundation", 32, TEXT, true, 4);
        addText(page, "Stage 1 · v0.1.0", 14, MUTED, false, 28);

        addCard(page, "FOUNDATION READY", "Independent Android app, launcher, Gradle project, and yellow identity.", true);
        addCard(page, "POSE ENGINE · NEXT", "MediaPipe Pose Landmarker will be installed locally in Stage 2.", false);
        addCard(page, "GIF ENGINE · PLANNED", "Deterministic GIF frames, geometry, and motion analysis will be implemented in later stages.", false);
        addCard(page, "SEARCH UI · PLANNED", "The XSearch Quality UI, anchors, FIND MORE, and RE-SEARCH are not active yet.", false);

        addText(page, "This prototype has a different application ID from the installed XGrid Yellow. It will not replace your existing app.", 13, MUTED, false, 12);
        addText(page, "NEXT: Confirm that this screen opens on your phone, then return to ChatGPT.", 14, YELLOW, true, 0);

        setContentView(scroller);
    }

    private void addCard(LinearLayout page, String title, String body, boolean ready) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), ready ? YELLOW : BORDER);
        card.setBackground(background);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(12);
        page.addView(card, cardParams);

        addText(card, title, 15, ready ? YELLOW : TEXT, true, 6);
        addText(card, body, 13, MUTED, false, 0);
    }

    private void addText(LinearLayout box, String value, int sizeSp,
                         int color, boolean bold, int bottomDp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(bottomDp);
        box.addView(view, params);
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
