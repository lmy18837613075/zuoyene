package com.dsh.cxtodo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍自动导航：点作业卡片 → 拉起学习通 → 找课程 → 作业/考试 → 对应条目。
 * step: 0 待机 1 找课程 2 课程页找作业Tab 3 作业列表找条目
 */
public class JumpService extends AccessibilityService {

    private static final String TAG = "CxJump";
    private static final String TARGET = "com.chaoxing.mobile";

    public static class Task {
        public final String course;
        public final String title;
        public final boolean exam;
        public final String url;

        Task(String course, String title, boolean exam, String url) {
            this.course = course == null ? "" : course;
            this.title = title == null ? "" : title;
            this.exam = exam;
            this.url = url == null ? "" : url;
        }
    }

    private static volatile Task sTask;
    private static volatile JumpService sInstance;

    public static void offer(String course, String title, boolean exam, String url) {
        sTask = new Task(course, title, exam, url);
        JumpService s = sInstance;
        if (s != null) {
            s.startNow();
        }
    }

    /** 不依赖窗口事件，立即开始导航（学习通已在前台时也能触发）。 */
    private void startNow() {
        if (sTask == null) {
            return;
        }
        step = 1;
        scrollCount = 0;
        moreTried = 0;
        openedCourseList = false;
        t0 = SystemClock.uptimeMillis();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 1500);
        Log.d(TAG, "startNow title=" + sTask.title);
    }

    public static boolean isAlive() {
        return sInstance != null;
    }

    public static String status() {
        JumpService s = sInstance;
        if (s == null) {
            return "service-off";
        }
        Task t = sTask;
        if (t == null) {
            return "idle";
        }
        return "step" + s.step + " course=[" + t.course + "] title=[" + t.title + "]";
    }

    private volatile int step = 0;
    private boolean openedCourseList = false;
    private int scrollCount = 0;
    private int moreTried = 0;
    private long t0 = 0;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            tickNow();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.d(TAG, "connected");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence p = e.getPackageName();
        String pkg = p == null ? "" : p.toString();
        if (sTask == null) {
            return;
        }
        if (TARGET.equals(pkg)) {
            if (step == 0) {
                step = 1;
                openedCourseList = false;
                scrollCount = 0;
                t0 = SystemClock.uptimeMillis();
                Log.d(TAG, "学习通到前台，开始导航");
                h.removeCallbacks(tick);
                h.postDelayed(tick, 900);
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    private void abort(String msg) {
        Log.d(TAG, "abort: " + msg);
        h.removeCallbacks(tick);
        sTask = null;
        step = 0;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void done() {
        Task t = sTask;
        Log.d(TAG, "done");
        h.removeCallbacks(tick);
        sTask = null;
        step = 0;
        if (t != null) {
            Toast.makeText(this, "已打开：" + t.title, Toast.LENGTH_SHORT).show();
        }
    }

    private void retry(long ms) {
        h.removeCallbacks(tick);
        h.postDelayed(tick, ms);
    }

    private void tickNow() {
        Task t = sTask;
        if (t == null || step == 0) {
            return;
        }
        if (SystemClock.uptimeMillis() - t0 > 90000) {
            abort("超时未完成，请手动进入");
            return;
        }
        AccessibilityNodeInfo root = targetRoot();
        if (root == null) {
            retry(600);
            return;
        }
        CharSequence p = root.getPackageName();
        if (!TARGET.equals(p == null ? "" : p.toString())) {
            retry(600);
            return;
        }
        Log.d(TAG, "tick step=" + step + " nodes=" + root.getChildCount() + " | " + snapshot(root));
        if (step == 1) {
            stepFindCourse(t, root);
        } else if (step == 2) {
            stepOpenTab(t, root);
        } else if (step == 3) {
            stepOpenWork(t, root);
        }
    }

    /** 多窗口 App：活动窗口可能只是空壳，遍历所有窗口找学习通的根节点。 */
    private AccessibilityNodeInfo targetRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && TARGET.equals(pkgOf(active)) && active.getChildCount() > 1) {
            return active;
        }
        List<AccessibilityWindowInfo> ws = getWindows();
        AccessibilityNodeInfo best = null;
        int bestCount = 0;
        for (int i = 0; i < ws.size(); i++) {
            AccessibilityNodeInfo r = ws.get(i).getRoot();
            if (r != null && TARGET.equals(pkgOf(r)) && r.getChildCount() > bestCount) {
                best = r;
                bestCount = r.getChildCount();
            }
        }
        if (best != null) {
            return best;
        }
        if (active != null && TARGET.equals(pkgOf(active))) {
            return active;
        }
        return active;
    }

    private String pkgOf(AccessibilityNodeInfo n) {
        CharSequence p = n.getPackageName();
        return p == null ? "" : p.toString();
    }

    /** 当前页面可见文本快照，便于诊断。 */
    private String snapshot(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = collect(root);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < all.size() && n < 12; i++) {
            String s = nodeText(all.get(i));
            if (s.length() >= 2) {
                sb.append(s.length() > 10 ? s.substring(0, 10) : s).append('/');
                n++;
            }
        }
        return sb.toString();
    }

    // ---- step 1：找课程（首页快捷方式 / 我的课程列表 / 搜索兜底） ----
    private void stepFindCourse(Task t, AccessibilityNodeInfo root) {
        if (t.course.length() >= 2) {
            AccessibilityNodeInfo courseNode = findText(root, t.course, true);
            if (courseNode != null) {
                Log.d(TAG, "课程节点命中: " + nodeText(courseNode));
                if (click(courseNode)) {
                    step = 2;
                    scrollCount = 0;
                    Log.d(TAG, "已点课程，-> step2");
                    retry(1400);
                    return;
                }
            }
        }
        if (!openedCourseList) {
            AccessibilityNodeInfo entry = findText(root, "我的课程", false);
            if (entry != null && click(entry)) {
                openedCourseList = true;
                Log.d(TAG, "已点「我的课程」");
                retry(1400);
                return;
            }
            AccessibilityNodeInfo more = findText(root, "查看更多", false);
            if (more != null && click(more)) {
                openedCourseList = true;
                Log.d(TAG, "已点「查看更多」");
                retry(1400);
                return;
            }
        } else {
            // 课程列表里滚动找
            AccessibilityNodeInfo courseNode = t.course.length() >= 2 ? findText(root, t.course, true) : null;
            if (courseNode != null && click(courseNode)) {
                step = 2;
                scrollCount = 0;
                Log.d(TAG, "列表中点课程，-> step2");
                retry(1400);
                return;
            }
            if (scrollCount < 14 && scrollForward(root)) {
                scrollCount++;
                retry(900);
                return;
            }
            // 列表滚完了没找到 → 回搜索
            if (t.course.length() >= 2) {
                AccessibilityNodeInfo back = findText(root, "返回", false);
                if (back != null && click(back)) {
                    Log.d(TAG, "列表未找到课程，返回");
                    retry(900);
                    return;
                }
            }
        }
        retry(900);
    }

    // ---- step 2：课程页 → 点「作业/考试」入口（或「更多」里的作业） ----
    private void stepOpenTab(Task t, AccessibilityNodeInfo root) {
        String[] keys = t.exam ? new String[]{"考试", "作业"} : new String[]{"作业", "考试"};
        for (int i = 0; i < keys.length; i++) {
            AccessibilityNodeInfo n = findText(root, keys[i], true);
            if (n != null) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                int h = getResources().getDisplayMetrics().heightPixels;
                if (r.top < h * 3 / 5 && click(n)) {
                    step = 3;
                    scrollCount = 0;
                    Log.d(TAG, "已点作业入口[" + nodeText(n) + "] -> step3");
                    retry(1800);
                    return;
                }
            }
        }
        // 兜底：进「更多」找作业入口
        AccessibilityNodeInfo more = findText(root, "更多", false);
        if (more != null && click(more)) {
            moreTried++;
            if (moreTried <= 2) {
                Log.d(TAG, "点「更多」找作业入口");
                retry(1500);
                return;
            }
        }
        retry(1000);
    }

    // ---- step 3：作业列表 → 点对应条目 ----
    private void stepOpenWork(Task t, AccessibilityNodeInfo root) {
        if (t.title.length() >= 2) {
            AccessibilityNodeInfo n = findText(root, t.title, true);
            if (n != null && click(n)) {
                Log.d(TAG, "作业条目命中: " + nodeText(n));
                done();
                return;
            }
        }
        if (scrollCount < 16 && scrollForward(root)) {
            scrollCount++;
            retry(800);
            return;
        }
        retry(900);
    }

    // ---- 节点工具 ----
    private String nodeText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) {
            return t.toString();
        }
        CharSequence d = n.getContentDescription();
        return d == null ? "" : d.toString();
    }

    private List<AccessibilityNodeInfo> collect(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<AccessibilityNodeInfo>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        int guard = 0;
        while (!q.isEmpty() && guard < 4000) {
            guard++;
            AccessibilityNodeInfo n = q.poll();
            all.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    q.add(c);
                }
            }
        }
        return all;
    }

    private AccessibilityNodeInfo findText(AccessibilityNodeInfo root, String needle, boolean contains) {
        if (needle == null || needle.length() == 0) {
            return null;
        }
        List<AccessibilityNodeInfo> all = collect(root);
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (int i = 0; i < all.size(); i++) {
            AccessibilityNodeInfo n = all.get(i);
            String s = nodeText(n);
            if (s.length() == 0) {
                continue;
            }
            boolean hit;
            if (contains) {
                hit = s.contains(needle) || (needle.contains(s) && s.length() >= 4);
            } else {
                hit = s.equals(needle);
            }
            if (hit) {
                int score = (n.isClickable() ? 2 : 0) + (s.equals(needle) ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
        }
        return best;
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            cur = cur.getParent();
        }
        if (Build.VERSION.SDK_INT >= 24) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0 && r.width() < 2000 && r.height() < 4000) {
                return tap(r.centerX(), r.centerY());
            }
        }
        return false;
    }

    private boolean tap(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            Log.d(TAG, "tap fail: " + t);
            return false;
        }
    }

    private boolean scrollForward(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = collect(root);
        for (int i = all.size() - 1; i >= 0; i--) {
            AccessibilityNodeInfo n = all.get(i);
            if (n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true;
            }
        }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                int w = getResources().getDisplayMetrics().widthPixels;
                int hgt = getResources().getDisplayMetrics().heightPixels;
                Path p = new Path();
                p.moveTo(w / 2, hgt * 2 / 3);
                p.lineTo(w / 2, hgt / 3);
                GestureDescription.Builder b = new GestureDescription.Builder();
                b.addStroke(new GestureDescription.StrokeDescription(p, 0, 260));
                return dispatchGesture(b.build(), null, null);
            } catch (Throwable t) {
                Log.d(TAG, "swipe fail: " + t);
            }
        }
        return false;
    }
}
