package com.airmic.app;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Single-screen controller: discovery / manual connection, start-stop of
 * {@link MicStreamService}, status pill and mic level meter.
 * Premium iOS-style dark UI, brand red accent. All user-facing strings
 * are Italian.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "airmic";
    private static final String KEY_LAST_IP = "last_ip";
    private static final int REQ_PERMISSIONS = 42;
    private static final Pattern IPV4 =
            Pattern.compile("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");

    private static final int HERO_IDLE_START = 0xFFE53238;
    private static final int HERO_IDLE_END = 0xFFC6090D;
    private static final int HERO_LIVE_START = 0xFFC6090D;
    private static final int HERO_LIVE_END = 0xFF7A0508;
    private static final long HERO_TINT_MS = 220;
    private static final long HERO_PRESS_MS = 160;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DecelerateInterpolator decelerate = new DecelerateInterpolator();
    private final ArgbEvaluator argb = new ArgbEvaluator();

    private View statusDot;
    private TextView statusText;
    private LevelMeterView levelMeter;
    private FrameLayout heroFrame;
    private View heroGlow;
    private Button startStopButton;
    private GradientDrawable heroCircle;
    private Button discoverButton;
    private Button connectButton;
    private EditText ipField;

    private ValueAnimator dotPulse;
    private ValueAnimator glowPulse;
    private boolean heroTintLive;

    private SharedPreferences prefs;
    private WifiManager.MulticastLock multicastLock;

    // Streaming target chosen via discovery or manual entry.
    private String targetHost;
    private int targetPort = Discovery.DEFAULT_AUDIO_PORT;
    private String pcName;

    private boolean streaming;
    private boolean discovering;
    private boolean pendingStartAfterDiscovery;

    private enum Dot { IDLE, WARN, OK }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra(MicStreamService.EXTRA_ERROR)) {
                Toast.makeText(MainActivity.this, R.string.toast_mic_error,
                        Toast.LENGTH_LONG).show();
            }
            streaming = intent.getBooleanExtra(MicStreamService.EXTRA_STREAMING, false);
            boolean ackFresh = intent.getBooleanExtra(MicStreamService.EXTRA_ACK_FRESH, false);
            String name = intent.getStringExtra(MicStreamService.EXTRA_PC_NAME);
            if (name != null && !name.isEmpty()) {
                pcName = name;
            }
            levelMeter.setLevel(streaming
                    ? intent.getFloatExtra(MicStreamService.EXTRA_LEVEL, 0f) : 0f);

            if (streaming) {
                setStatus(ackFresh
                                ? getString(R.string.status_connected, displayName())
                                : getString(R.string.status_waiting),
                        ackFresh ? Dot.OK : Dot.WARN);
            } else if (!discovering) {
                setStatus(R.string.status_idle, Dot.IDLE);
            }
            updateControls();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildContentView());
        ipField.setText(prefs.getString(KEY_LAST_IP, ""));
        requestMissingPermissions();
        setStatus(R.string.status_idle, Dot.IDLE);
        updateControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(MicStreamService.BROADCAST_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        // Sync with the service snapshot in case state changed while paused.
        streaming = MicStreamService.sStreaming;
        if (streaming) {
            pcName = MicStreamService.sPcName;
            boolean ackFresh = MicStreamService.sLastAckAt != 0
                    && android.os.SystemClock.elapsedRealtime() - MicStreamService.sLastAckAt < 4000;
            setStatus(ackFresh
                            ? getString(R.string.status_connected, displayName())
                            : getString(R.string.status_waiting),
                    ackFresh ? Dot.OK : Dot.WARN);
        } else if (!discovering) {
            setStatus(R.string.status_idle, Dot.IDLE);
            levelMeter.setLevel(0f);
        }
        updateControls();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(stateReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseMulticastLock();
        cancelPulses();
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, dp(28), pad, dp(24));

        // iOS-style large title, top-left.
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(getColor(R.color.text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(-0.02f);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.app_subtitle);
        subtitle.setTextColor(getColor(R.color.text_dim));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams subLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        root.addView(subtitle, subLp);

        root.addView(spacer(1));

        // Status pill: dot + text inside a rounded card.
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setBackgroundResource(R.drawable.card_status);
        statusCard.setPadding(dp(18), dp(14), dp(18), dp(14));

        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = lp(dp(10), dp(10));
        dotLp.rightMargin = dp(12);
        statusCard.addView(statusDot, dotLp);

        statusText = new TextView(this);
        statusText.setTextColor(getColor(R.color.text));
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusCard.addView(statusText, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(statusCard, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Mic level section.
        TextView levelLabel = new TextView(this);
        levelLabel.setText(R.string.section_level);
        levelLabel.setTextColor(getColor(R.color.text_dim));
        levelLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        levelLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams levelLabelLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        levelLabelLp.topMargin = dp(26);
        root.addView(levelLabel, levelLabelLp);

        levelMeter = new LevelMeterView(this);
        LinearLayout.LayoutParams meterLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
        meterLp.topMargin = dp(10);
        root.addView(levelMeter, meterLp);

        root.addView(spacer(1.4f));

        // Hero: glow layer behind the big circular AVVIA/STOP button.
        heroFrame = new FrameLayout(this);
        LinearLayout.LayoutParams heroLp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.gravity = Gravity.CENTER_HORIZONTAL;

        heroGlow = new View(this);
        heroGlow.setBackground(buildGlow());
        heroGlow.setAlpha(0.55f);
        FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(dp(196), dp(196));
        glowLp.gravity = Gravity.CENTER;
        heroFrame.addView(heroGlow, glowLp);

        heroCircle = new GradientDrawable();
        heroCircle.setShape(GradientDrawable.OVAL);
        heroCircle.setOrientation(GradientDrawable.Orientation.TL_BR);
        heroCircle.setColors(new int[]{HERO_IDLE_START, HERO_IDLE_END});

        startStopButton = new Button(this);
        startStopButton.setText(R.string.btn_start);
        startStopButton.setTextColor(Color.WHITE);
        startStopButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        startStopButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        startStopButton.setLetterSpacing(0.06f);
        startStopButton.setAllCaps(false);
        startStopButton.setBackground(heroCircle);
        startStopButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            onStartStopClicked();
        });
        startStopButton.setOnTouchListener(this::onHeroTouch);
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(dp(150), dp(150));
        btnLp.gravity = Gravity.CENTER;
        heroFrame.addView(startStopButton, btnLp);
        root.addView(heroFrame, heroLp);

        root.addView(spacer(1.6f));

        // Connection section.
        TextView connLabel = new TextView(this);
        connLabel.setText(R.string.section_connection);
        connLabel.setTextColor(getColor(R.color.text_dim));
        connLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        connLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(connLabel, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout manualRow = new LinearLayout(this);
        manualRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams manualLp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        manualLp.topMargin = dp(10);
        root.addView(manualRow, manualLp);

        ipField = new EditText(this);
        ipField.setHint(R.string.hint_ip);
        ipField.setHintTextColor(getColor(R.color.text_dim));
        ipField.setTextColor(getColor(R.color.text));
        ipField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        ipField.setSingleLine(true);
        ipField.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        ipField.setBackgroundResource(R.drawable.edit_bg);
        LinearLayout.LayoutParams ipLp = lp(0, dp(50), 1f);
        ipLp.rightMargin = dp(10);
        manualRow.addView(ipField, ipLp);

        connectButton = new Button(this);
        connectButton.setText(R.string.btn_connect);
        connectButton.setTextColor(getColor(R.color.text));
        connectButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        connectButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        connectButton.setAllCaps(false);
        connectButton.setStateListAnimator(null);
        connectButton.setBackgroundResource(R.drawable.btn_small);
        connectButton.setOnClickListener(v -> onManualConnectClicked());
        manualRow.addView(connectButton, lp(dp(116), dp(50)));

        discoverButton = new Button(this);
        discoverButton.setText(R.string.btn_discover);
        discoverButton.setTextColor(getColor(R.color.text));
        discoverButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        discoverButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        discoverButton.setAllCaps(false);
        discoverButton.setStateListAnimator(null);
        discoverButton.setBackgroundResource(R.drawable.btn_secondary);
        discoverButton.setOnClickListener(v -> onDiscoverClicked());
        LinearLayout.LayoutParams discLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        discLp.topMargin = dp(12);
        root.addView(discoverButton, discLp);

        return root;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private View spacer(float weight) {
        View v = new View(this);
        LinearLayout.LayoutParams p = lp(1, 0);
        p.weight = weight;
        v.setLayoutParams(p);
        return v;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** Soft radial red halo shown behind the hero button. */
    private GradientDrawable buildGlow() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientRadius(dp(98));
        d.setColors(new int[]{0x66E53238, 0x00E53238});
        return d;
    }

    private GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    // ---------------------------------------------------------- Animations

    private boolean onHeroTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.animate().scaleX(0.96f).scaleY(0.96f)
                        .setDuration(HERO_PRESS_MS).setInterpolator(decelerate).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(HERO_PRESS_MS).setInterpolator(decelerate).start();
                break;
            default:
                break;
        }
        return false;
    }

    /** Cross-fades the hero gradient between the idle and live palettes. */
    private void animateHeroTint(boolean live) {
        if (live == heroTintLive) {
            return;
        }
        heroTintLive = live;
        final int fromStart = live ? HERO_IDLE_START : HERO_LIVE_START;
        final int fromEnd = live ? HERO_IDLE_END : HERO_LIVE_END;
        final int toStart = live ? HERO_LIVE_START : HERO_IDLE_START;
        final int toEnd = live ? HERO_LIVE_END : HERO_IDLE_END;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(HERO_TINT_MS);
        anim.setInterpolator(decelerate);
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            heroCircle.setColors(new int[]{
                    (Integer) argb.evaluate(t, fromStart, toStart),
                    (Integer) argb.evaluate(t, fromEnd, toEnd)});
        });
        anim.start();
    }

    private void setGlowLive(boolean live) {
        if (glowPulse != null) {
            glowPulse.cancel();
            glowPulse = null;
        }
        if (live) {
            glowPulse = ValueAnimator.ofFloat(0.45f, 1f);
            glowPulse.setDuration(1100);
            glowPulse.setRepeatMode(ValueAnimator.REVERSE);
            glowPulse.setRepeatCount(ValueAnimator.INFINITE);
            glowPulse.addUpdateListener(a ->
                    heroGlow.setAlpha((Float) a.getAnimatedValue()));
            glowPulse.start();
        } else {
            heroGlow.animate().alpha(0.55f).setDuration(HERO_TINT_MS).start();
        }
    }

    private void setDotPulsing(boolean pulsing) {
        if (dotPulse != null) {
            dotPulse.cancel();
            dotPulse = null;
        }
        if (pulsing) {
            dotPulse = ValueAnimator.ofFloat(0.25f, 1f);
            dotPulse.setDuration(700);
            dotPulse.setRepeatMode(ValueAnimator.REVERSE);
            dotPulse.setRepeatCount(ValueAnimator.INFINITE);
            dotPulse.addUpdateListener(a ->
                    statusDot.setAlpha((Float) a.getAnimatedValue()));
            dotPulse.start();
        } else {
            statusDot.setAlpha(1f);
        }
    }

    private void cancelPulses() {
        setDotPulsing(false);
        setGlowLive(false);
    }

    // ------------------------------------------------------------- Behavior

    private void onStartStopClicked() {
        if (streaming) {
            startService(new Intent(this, MicStreamService.class)
                    .setAction(MicStreamService.ACTION_STOP));
            return;
        }
        if (!hasMicPermission()) {
            requestMissingPermissions();
            return;
        }
        if (targetHost == null) {
            // No target yet: look for a PC first, then start automatically.
            pendingStartAfterDiscovery = true;
            runDiscovery(null);
        } else {
            startStreaming();
        }
    }

    private void onDiscoverClicked() {
        if (!discovering) {
            pendingStartAfterDiscovery = false;
            runDiscovery(null);
        }
    }

    private void onManualConnectClicked() {
        hideKeyboard();
        String ip = ipField.getText().toString().trim();
        if (!IPV4.matcher(ip).matches()) {
            Toast.makeText(this, R.string.toast_invalid_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!discovering) {
            pendingStartAfterDiscovery = false;
            runDiscovery(ip);
        }
    }

    /**
     * Runs the discovery handshake off the UI thread.
     *
     * @param unicastIp null for LAN broadcast, or a manual target IP. Manual mode
     *                  falls back to the default audio port when the PC doesn't
     *                  answer, as the protocol allows streaming without handshake.
     */
    private void runDiscovery(String unicastIp) {
        discovering = true;
        setStatus(R.string.status_searching, Dot.WARN);
        updateControls();
        acquireMulticastLock(unicastIp == null);

        executor.execute(() -> {
            // Broadcast: 10 attempts x 500 ms per protocol; manual: 3 quick tries.
            int attempts = unicastIp == null ? 10 : 3;
            Discovery.Result result = Discovery.discover(unicastIp, attempts, 500);
            handler.post(() -> onDiscoveryResult(unicastIp, result));
        });
    }

    private void onDiscoveryResult(String unicastIp, Discovery.Result result) {
        discovering = false;
        releaseMulticastLock();

        if (result != null) {
            targetHost = result.host;
            targetPort = result.audioPort;
            pcName = result.pcName;
            if (unicastIp != null) {
                prefs.edit().putString(KEY_LAST_IP, unicastIp).apply();
            }
            Toast.makeText(this, getString(R.string.toast_pc_found, result.pcName),
                    Toast.LENGTH_SHORT).show();
            if (pendingStartAfterDiscovery) {
                pendingStartAfterDiscovery = false;
                startStreaming();
                return;
            }
        } else {
            pendingStartAfterDiscovery = false;
            if (unicastIp != null) {
                // Manual mode: connect anyway with the default audio port.
                targetHost = unicastIp;
                targetPort = Discovery.DEFAULT_AUDIO_PORT;
                pcName = null;
                prefs.edit().putString(KEY_LAST_IP, unicastIp).apply();
                Toast.makeText(this, R.string.toast_manual_fallback, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.toast_no_pc_found, Toast.LENGTH_LONG).show();
            }
        }
        setStatus(streaming ? R.string.status_waiting : R.string.status_idle,
                streaming ? Dot.WARN : Dot.IDLE);
        updateControls();
    }

    private void startStreaming() {
        Intent i = new Intent(this, MicStreamService.class)
                .setAction(MicStreamService.ACTION_START)
                .putExtra(MicStreamService.EXTRA_HOST, targetHost)
                .putExtra(MicStreamService.EXTRA_PORT, targetPort)
                .putExtra(MicStreamService.EXTRA_PC_NAME,
                        pcName != null ? pcName : targetHost);
        startForegroundService(i);
    }

    private String displayName() {
        if (pcName != null && !pcName.isEmpty()) {
            return pcName;
        }
        return targetHost != null ? targetHost : "";
    }

    private void setStatus(int textRes, Dot dot) {
        setStatus(getString(textRes), dot);
    }

    private void setStatus(String text, Dot dotState) {
        statusText.setText(text);
        int color;
        switch (dotState) {
            case OK:
                color = getColor(R.color.status_ok);
                break;
            case WARN:
                color = getColor(R.color.status_warn);
                break;
            default:
                color = getColor(R.color.status_idle);
                break;
        }
        statusDot.setBackground(dot(color));
        setDotPulsing(dotState == Dot.WARN);
    }

    private void updateControls() {
        startStopButton.setText(streaming ? R.string.btn_stop : R.string.btn_start);
        animateHeroTint(streaming);
        setGlowLive(streaming);
        startStopButton.setEnabled(!discovering);
        heroFrame.setAlpha(discovering ? 0.45f : 1f);
        discoverButton.setEnabled(!discovering && !streaming);
        connectButton.setEnabled(!discovering && !streaming);
        ipField.setEnabled(!discovering && !streaming);
        float controlsAlpha = (discovering || streaming) ? 0.45f : 1f;
        discoverButton.setAlpha(controlsAlpha);
        connectButton.setAlpha(controlsAlpha);
        ipField.setAlpha(controlsAlpha);
    }

    // ----------------------------------------------------------- Permissions

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMissingPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (!hasMicPermission()) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && !hasMicPermission()) {
            Toast.makeText(this, R.string.toast_mic_denied, Toast.LENGTH_LONG).show();
        }
    }

    // --------------------------------------------------------------- Helpers

    /** Broadcast delivery on Android requires a multicast lock on some devices. */
    private void acquireMulticastLock(boolean forBroadcast) {
        releaseMulticastLock();
        if (!forBroadcast) {
            return;
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wm.createMulticastLock("airmic_discovery");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }
}
