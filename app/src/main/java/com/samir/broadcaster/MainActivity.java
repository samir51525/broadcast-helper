package com.samir.broadcaster;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView serviceView;
    private TextView statusView;
    private TextView usedView;
    private EditText perField;
    private EditText countField;
    private RadioButton safeBtn;
    private RadioButton fastBtn;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Broadcast Helper");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(24));
        scroll.addView(root);

        root.addView(text("WhatsApp Broadcast Helper", 22, true));
        root.addView(text("Creates WhatsApp broadcast lists for you. It uses the contacts WhatsApp shows in New broadcast (your WhatsApp contacts), in order, and never uses the same contact twice.", 14, false));

        // Step 1
        root.addView(gap());
        root.addView(text("Step 1: Turn on the helper", 17, true));
        root.addView(text("Open Accessibility settings, find 'Broadcast Helper' (may be under Installed apps), and turn it ON.", 14, false));
        Button openAcc = button("Open Accessibility settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(openAcc);
        root.addView(text("If the switch is greyed out (Android 13 or newer): open App info, tap the three dots at the top right, tap 'Allow restricted settings', then try again.", 13, false));
        Button openInfo = button("Open App info", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });
        root.addView(openInfo);
        serviceView = text("", 15, true);
        root.addView(serviceView);

        // Step 2
        root.addView(gap());
        root.addView(text("Step 2: Choose settings", 17, true));
        root.addView(text("Contacts in each broadcast list (max 256):", 14, false));
        perField = number("250");
        root.addView(perField);
        root.addView(text("How many lists to create:", 14, false));
        countField = number("4");
        root.addView(countField);

        RadioGroup group = new RadioGroup(this);
        safeBtn = new RadioButton(this);
        safeBtn.setId(View.generateViewId());
        safeBtn.setText("Safe mode: slow, human-like (recommended)");
        fastBtn = new RadioButton(this);
        fastBtn.setId(View.generateViewId());
        fastBtn.setText("Fast mode: quicker, higher chance WhatsApp notices");
        group.addView(safeBtn);
        group.addView(fastBtn);
        safeBtn.setChecked(true);
        root.addView(group);

        // Step 3
        root.addView(gap());
        root.addView(text("Step 3: Start", 17, true));
        root.addView(text("Keep the phone unlocked and do not touch it while it works. Press any Volume button to stop.", 14, false));
        Button start = button("START", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStart();
            }
        });
        root.addView(start);
        Button stop = button("STOP", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BroadcastService s = BroadcastService.instance;
                if (s != null) {
                    s.requestStop();
                }
            }
        });
        root.addView(stop);

        root.addView(gap());
        statusView = text("", 15, false);
        root.addView(statusView);
        usedView = text("", 13, false);
        root.addView(usedView);
        Button reset = button("Forget used contacts (start again from the first contact)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BroadcastService.saveUsed(MainActivity.this, new HashSet<String>());
                Toast.makeText(MainActivity.this, "Cleared. Next run starts from the first contact.", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        root.addView(reset);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private void onStart() {
        final BroadcastService service = BroadcastService.instance;
        if (service == null) {
            Toast.makeText(this, "Do Step 1 first: turn the helper ON in Accessibility settings.", Toast.LENGTH_LONG).show();
            return;
        }
        final int per = parse(perField, 250);
        final int count = parse(countField, 4);
        final boolean safe = safeBtn.isChecked();

        Intent launch = getPackageManager().getLaunchIntentForPackage(BroadcastService.WHATSAPP);
        if (launch == null) {
            Toast.makeText(this, "WhatsApp is not installed on this phone.", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!service.startJob(per, count, safe)) {
                    Toast.makeText(MainActivity.this, "Already running.", Toast.LENGTH_SHORT).show();
                }
            }
        }, 1200);
    }

    private void refresh() {
        boolean on = BroadcastService.instance != null;
        serviceView.setText(on ? "Helper is ON" : "Helper is OFF (do Step 1)");
        statusView.setText("Status: " + BroadcastService.status);
        int used = BroadcastService.loadUsed(this).size();
        usedView.setText("Contacts already used in earlier lists: " + used);
    }

    private int parse(EditText field, int fallback) {
        try {
            int v = Integer.parseInt(field.getText().toString().trim());
            return v > 0 ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---- small UI helpers ----

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(14)));
        return v;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private EditText number(String initial) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(initial);
        return e;
    }
}
