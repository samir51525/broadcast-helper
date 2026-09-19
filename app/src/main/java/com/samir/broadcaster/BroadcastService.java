package com.samir.broadcaster;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Drives WhatsApp's "New broadcast" screen to create broadcast lists.
 *
 * How it works (English WhatsApp only):
 *   Chats screen -> "More options" (three dots) -> "New broadcast"
 *   -> tap contacts one by one (skipping contacts already used in an earlier list)
 *   -> tap the green check button -> list is created.
 * Repeats until the requested number of lists is made or contacts run out.
 */
public class BroadcastService extends AccessibilityService {

    static final String WHATSAPP = "com.whatsapp";
    static final String PREFS = "broadcaster";
    static final String KEY_USED = "used_names";

    private static final String INVITE = "\u0000INVITE";

    static volatile BroadcastService instance;
    static volatile String status = "Idle. Turn on the accessibility service, then press Start.";
    static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private volatile boolean stopRequested = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    private int perBroadcast = 250;
    private int howMany = 1;
    private boolean safe = true;

    private static class JobException extends Exception {
        JobException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        status = "Service is ON. Open Broadcast Helper and press Start.";
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used. We read the screen on demand.
    }

    @Override
    public void onInterrupt() {
        stopRequested = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopRequested = true;
        instance = null;
        return super.onUnbind(intent);
    }

    /** While a job runs, any volume key stops it. */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (running && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                stopRequested = true;
            }
            return true;
        }
        return super.onKeyEvent(event);
    }

    // ------------------------------------------------------------------
    // Called from MainActivity
    // ------------------------------------------------------------------

    synchronized boolean startJob(int perList, int lists, boolean safeMode) {
        if (running) {
            return false;
        }
        perBroadcast = Math.max(1, Math.min(256, perList));
        howMany = Math.max(1, lists);
        safe = safeMode;
        stopRequested = false;
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runJob();
            }
        }, "broadcast-worker");
        worker.start();
        return true;
    }

    void requestStop() {
        stopRequested = true;
    }

    static Set<String> loadUsed(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> saved = p.getStringSet(KEY_USED, new HashSet<String>());
        return new HashSet<String>(saved);
    }

    static void saveUsed(Context c, Set<String> names) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_USED, new HashSet<String>(names))
                .apply();
    }

    // ------------------------------------------------------------------
    // The job
    // ------------------------------------------------------------------

    private void runJob() {
        int created = 0;
        int totalContacts = 0;
        try {
            acquireWakeLock();
            setStatus("Starting... don't touch the phone. Press a Volume button to stop.");
            sleep(2500);

            for (int i = 0; i < howMany; i++) {
                setStatus("List " + (i + 1) + " of " + howMany + ": opening New broadcast...");
                if (!openNewBroadcast()) {
                    throw new JobException("Could not open 'New broadcast'. Check that WhatsApp is in English and press Start again.");
                }
                pause(safe ? 800 : 300, safe ? 1500 : 600);

                Set<String> picked = selectContacts(i + 1);
                if (picked.isEmpty()) {
                    backOutOfPicker();
                    setStatus("No more unused contacts found. Lists created: " + created + ".");
                    toast("No more unused contacts. Lists created: " + created);
                    return;
                }

                setStatus("List " + (i + 1) + ": " + picked.size() + " contacts selected. Creating list...");
                if (!confirmCreate()) {
                    throw new JobException("Could not press the green check button. Lists created so far: " + created + ".");
                }

                Set<String> used = loadUsed(this);
                used.addAll(picked);
                saveUsed(this, used);
                created++;
                totalContacts += picked.size();
                toast("List " + created + " created with " + picked.size() + " contacts");

                if (picked.size() < perBroadcast) {
                    // Contacts ran out, this was the last (smaller) list.
                    back();
                    sleep(800);
                    setStatus("All contacts used. Created " + created + " lists (" + totalContacts + " contacts).");
                    toast("Done. Created " + created + " lists.");
                    return;
                }

                if (i < howMany - 1) {
                    back();
                    sleep(1000);
                    int wait = safe ? 8000 + rnd.nextInt(12000) : 2000 + rnd.nextInt(2000);
                    setStatus("List " + created + " done. Waiting a few seconds before the next one...");
                    sleep(wait);
                }
            }
            back();
            setStatus("Finished. Created " + created + " lists (" + totalContacts + " contacts).");
            toast("Finished. Created " + created + " lists.");
        } catch (JobException e) {
            setStatus(e.getMessage() + " (Lists created: " + created + ")");
            toast(e.getMessage());
        } catch (Exception e) {
            setStatus("Error: " + e + " (Lists created: " + created + ")");
            toast("Error: " + e.getMessage());
        } finally {
            running = false;
            releaseWakeLock();
        }
    }

    private boolean openNewBroadcast() throws JobException {
        for (int attempt = 0; attempt < 6; attempt++) {
            AccessibilityNodeInfo more = findLabel("More options", true);
            if (more == null) {
                back();
                pause(700, 1000);
                continue;
            }
            click(more);
            sleep(1000);

            AccessibilityNodeInfo item = findLabel("New broadcast", false);
            if (item != null) {
                click(item);
                sleep(1500);
                if (waitForPicker(7000)) {
                    return true;
                }
            }
            // Menu had no "New broadcast": close the menu and leave this screen, then retry.
            back();
            sleep(600);
            back();
            sleep(900);
        }
        return false;
    }

    private boolean waitForPicker(long timeoutMs) throws JobException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo list = findList();
            if (list != null && list.getChildCount() > 0 && findLabel("New broadcast", false) != null) {
                return true;
            }
            sleep(400);
        }
        return false;
    }

    /**
     * Taps contacts in WhatsApp's own contact list (top to bottom), skipping any
     * contact already put into an earlier list, until 'perBroadcast' are selected
     * or the list ends. Returns the names that were selected.
     */
    private Set<String> selectContacts(int listNumber) throws JobException {
        Set<String> used = loadUsed(this);
        LinkedHashSet<String> picked = new LinkedHashSet<String>();
        String lastSignature = "";
        int stagnant = 0;
        int noListCount = 0;
        int guard = 0;

        while (picked.size() < perBroadcast && guard++ < 6000) {
            if (stopRequested) {
                throw new JobException("Stopped by you.");
            }
            AccessibilityNodeInfo list = findList();
            if (list == null) {
                noListCount++;
                if (noListCount > 10) {
                    throw new JobException("WhatsApp left the screen. Stopped.");
                }
                sleep(500);
                continue;
            }
            noListCount = 0;

            boolean clicked = false;
            boolean sawInvite = false;
            StringBuilder signature = new StringBuilder();
            java.util.HashMap<String, Integer> seenInView = new java.util.HashMap<String, Integer>();
            int n = list.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo row = list.getChild(i);
                if (row == null) {
                    continue;
                }
                String[] texts = rowTexts(row);
                if (texts == null) {
                    continue;
                }
                if (INVITE.equals(texts[0])) {
                    sawInvite = true;
                    break;
                }
                // A contact is identified by: name + the line under it (status) + which
                // one it is among identical rows. So "Ravi Patel" and "Ravi Shah", or two
                // different people both saved as "Ravi Patel", are kept apart.
                String base = texts[0] + "⁣" + texts[1];
                Integer prev = seenInView.get(base);
                int ordinal = (prev == null) ? 0 : prev.intValue() + 1;
                seenInView.put(base, Integer.valueOf(ordinal));
                String name = base + "⁣#" + ordinal;
                signature.append(name).append('|');
                if (used.contains(name) || picked.contains(name)) {
                    continue;
                }
                if (!isSelectableRow(row)) {
                    continue; // section header, not a contact
                }
                if (clickRow(row)) {
                    picked.add(name);
                    clicked = true;
                    setStatus("List " + listNumber + ": selected " + picked.size() + " of " + perBroadcast + " contacts");
                    if (safe) {
                        pause(350, 900);
                    } else {
                        pause(120, 250);
                    }
                    break; // layout changes after a tap, so scan again
                }
            }

            if (clicked) {
                stagnant = 0;
                continue;
            }
            if (sawInvite) {
                break;
            }

            // Nothing new on this screen: scroll down.
            String sig = signature.toString();
            if (sig.equals(lastSignature)) {
                stagnant++;
            } else {
                stagnant = 0;
            }
            lastSignature = sig;
            if (stagnant >= 3) {
                break; // reached the end of the list
            }
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            if (safe) {
                pause(700, 1200);
            } else {
                pause(350, 550);
            }
        }
        return picked;
    }

    private boolean confirmCreate() throws JobException {
        sleep(700);
        String[] labels = {"Create", "Done", "Next", "OK", "Continue"};
        boolean pressed = false;
        for (String label : labels) {
            AccessibilityNodeInfo node = findLabel(label, true);
            if (node != null && click(node)) {
                pressed = true;
                break;
            }
        }
        if (!pressed) {
            // The green check button sits at the bottom right.
            DisplayMetrics dm = getResources().getDisplayMetrics();
            tap(dm.widthPixels * 0.88f, dm.heightPixels * 0.90f);
        }
        sleep(2000);
        // Success = we are no longer on the "New broadcast" picker screen.
        return findLabel("New broadcast", false) == null;
    }

    private void backOutOfPicker() throws JobException {
        back();
        sleep(800);
        back();
        sleep(800);
    }

    // ------------------------------------------------------------------
    // Screen helpers
    // ------------------------------------------------------------------

    private boolean isWhatsApp(AccessibilityNodeInfo node) {
        CharSequence pkg = node.getPackageName();
        return pkg != null && WHATSAPP.contentEquals(pkg);
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && isWhatsApp(r)) {
                        out.add(r);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to the active window
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && isWhatsApp(r)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean matches(CharSequence value, String want, boolean exact) {
        if (value == null) {
            return false;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        return exact ? s.equals(want) : s.contains(want);
    }

    /** Finds a node whose text or content description matches the label. */
    private AccessibilityNodeInfo findLabel(String label, boolean exact) {
        String want = label.toLowerCase(Locale.ROOT);
        for (AccessibilityNodeInfo root : roots()) {
            LinkedList<AccessibilityNodeInfo> queue = new LinkedList<AccessibilityNodeInfo>();
            queue.add(root);
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo n = queue.removeFirst();
                if (matches(n.getText(), want, exact) || matches(n.getContentDescription(), want, exact)) {
                    return n;
                }
                int count = n.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) {
                        queue.add(c);
                    }
                }
            }
        }
        return null;
    }

    /** The big vertical contact list = the scrollable node with the largest height. */
    private AccessibilityNodeInfo findList() {
        AccessibilityNodeInfo best = null;
        int bestHeight = 0;
        Rect r = new Rect();
        for (AccessibilityNodeInfo root : roots()) {
            LinkedList<AccessibilityNodeInfo> queue = new LinkedList<AccessibilityNodeInfo>();
            queue.add(root);
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo n = queue.removeFirst();
                if (n.isScrollable()) {
                    n.getBoundsInScreen(r);
                    if (r.height() > bestHeight) {
                        bestHeight = r.height();
                        best = n;
                    }
                }
                int count = n.getChildCount();
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) {
                        queue.add(c);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Returns {name, secondLine} for a row (secondLine may be ""), or {INVITE, ""} for the
     * invite section, or null if the row has no text.
     */
    private String[] rowTexts(AccessibilityNodeInfo row) {
        LinkedList<AccessibilityNodeInfo> queue = new LinkedList<AccessibilityNodeInfo>();
        queue.add(row);
        String first = null;
        String second = "";
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.removeFirst();
            CharSequence t = n.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (s.length() > 0) {
                    String lower = s.toLowerCase(Locale.ROOT);
                    if (lower.equals("invite") || lower.startsWith("invite to whatsapp") || lower.equals("invite friends")) {
                        return new String[] {INVITE, ""};
                    }
                    // Normalise so Hindi/Gujarati (and other) text always compares the same way.
                    String norm = Normalizer.normalize(s, Normalizer.Form.NFC);
                    if (first == null) {
                        first = norm;
                    } else if (second.length() == 0 && !norm.equals(first)) {
                        second = norm;
                    }
                }
            }
            int count = n.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    queue.add(c);
                }
            }
        }
        if (first == null) {
            return null;
        }
        return new String[] {first, second};
    }

    private boolean isSelectableRow(AccessibilityNodeInfo row) {
        return row.isClickable() || firstClickableDescendant(row) != null;
    }

    private AccessibilityNodeInfo firstClickableDescendant(AccessibilityNodeInfo row) {
        LinkedList<AccessibilityNodeInfo> queue = new LinkedList<AccessibilityNodeInfo>();
        int count = row.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = row.getChild(i);
            if (c != null) {
                queue.add(c);
            }
        }
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.removeFirst();
            if (n.isClickable()) {
                return n;
            }
            int cc = n.getChildCount();
            for (int i = 0; i < cc; i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    queue.add(c);
                }
            }
        }
        return null;
    }

    private boolean clickRow(AccessibilityNodeInfo row) {
        if (row.isClickable() && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        AccessibilityNodeInfo d = firstClickableDescendant(row);
        if (d != null && d.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        return tapCenter(row);
    }

    /** Clicks the node, or the nearest clickable parent, or taps its centre as a last resort. */
    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo c = node;
        int depth = 0;
        while (c != null && depth < 6) {
            if (c.isClickable() && c.isEnabled()) {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
            c = c.getParent();
            depth++;
        }
        return tapCenter(node);
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) {
            return false;
        }
        return tap(r.centerX(), r.centerY());
    }

    private boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    private void back() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    // ------------------------------------------------------------------
    // Timing, status, wake lock
    // ------------------------------------------------------------------

    private void sleep(long ms) throws JobException {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            if (stopRequested) {
                throw new JobException("Stopped by you.");
            }
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(100L, left));
            } catch (InterruptedException e) {
                throw new JobException("Stopped.");
            }
        }
    }

    private void pause(int minMs, int maxMs) throws JobException {
        sleep(minMs + rnd.nextInt(Math.max(1, maxMs - minMs)));
    }

    private void setStatus(String s) {
        status = s;
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(BroadcastService.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "broadcaster:job");
            wakeLock.acquire(3L * 60L * 60L * 1000L);
        } catch (Exception ignored) {
            // not critical
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
            // not critical
        }
    }
}
