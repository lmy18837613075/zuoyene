package com.dsh.cxtodo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作业呢：学习通作业收集。
 *
 * 数据：扫描结果缓存到本地，打开秒显上次数据并后台刷新；
 * 课程学期判定与作业抓取见 CxClient / Term。
 */
public class MainActivity extends Activity {

    private FrameLayout root;
    private AuroraView aurora;
    private LinearLayout column;
    private TextView refreshHeader;
    private ScrollView scroll;
    private LinearLayout listBox;
    private LinearLayout doneBox, pastBox, submitBox, activeBox;
    private boolean doneOpen = false, pastOpen = false, submitOpen = false, activeOpen = false;
    private TextView doneToggle, pastToggle, submitToggle, activeToggle;
    private TextView heroCount;
    private TextView heroMeta;
    private TextView statusLine;
    private TextView accountLabel;
    private TextView actionBtn;
    private TextView termLine;
    private GlassView progressCard;
    private TextView progressLabel;
    private LinearLayout barTrack;
    private View barFill, barRest;
    private TextView cancelPill;
    private FrameLayout webLayer;
    private FrameLayout browseLayer;
    private WebView web;
    private WebView browseWeb;
    private TextView browseTitle;
    private View bottomBar;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private String cookie = "";
    private boolean demoMode = false;
    private boolean scanning = false;
    private boolean pullTriggered = false;
    private final List<CxClient.Item> items = new ArrayList<CxClient.Item>();
    private final List<CxClient.Item> customs = new ArrayList<CxClient.Item>();
    private String lastDebug = "";
    private long lastScanTime = 0;
    private final boolean[] cancelFlag = new boolean[]{false};
    private float downY = -1;
    private boolean pulling = false;

    private static final String SANS = "sans-serif";
    private static final String SANS_MEDIUM = "sans-serif-medium";
    private static final String SANS_BLACK = "sans-serif-black";
    private static final int PULL_MAX_DP = 96;
    private static final int PULL_TRIGGER_DP = 64;
    private static final int HEADER_OPEN_DP = 46;

    private int cPrimary;
    private int cFg;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadColors();
        Window w = getWindow();
        w.setStatusBarColor(0xFFEDE9F8);
        w.setNavigationBarColor(0xFFEDE9F8);
        int uiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(uiFlags);

        sp = getSharedPreferences("cxtodo", MODE_PRIVATE);
        cookie = sp.getString("cookie", "");

        buildUi();
        setContentView(root);
        ensureBackdrop();

        try {
            android.content.IntentFilter af = new android.content.IntentFilter("com.dsh.cxtodo.AUTOTEST");
            registerReceiver(autoTestRx, af, Context.RECEIVER_EXPORTED);
        } catch (Throwable ignored) {
        }

        demoMode = getIntent().getBooleanExtra("demo", false);
        if (demoMode) {
            cookie = "_uid=81234567; fid=9999";
            setAccountLabel(true);
            onScanDone(CxClient.demo());
            return;
        }
        if (cookie.isEmpty()) {
            showLogin();
            return;
        }
        setAccountLabel(true);
        if (loadCache()) {
            loadCustoms();
            renderHomeworks();
            setStatus(cacheLabel() + " · 正在刷新…");
        }
        startScan();

        // adb 调试钩子：am start ... --es autotest "标题片段" / --ei autotestIdx N
        handleAutoTest(getIntent());
    }

    private final android.content.BroadcastReceiver autoTestRx = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int idx = i.getIntExtra("idx", -1);
            String title = i.getStringExtra("title");
            android.util.Log.i("CxJump", "broadcast idx=" + idx + " title=" + title);
            String ac = i.getStringExtra("addcustom");
            if (ac != null && ac.length() > 0) {
                addCustomItem(ac, i.getStringExtra("coursename"), i.getStringExtra("deadline"));
                return;
            }
            if (idx >= 0) {
                fireAutoTestIdx(idx);
            } else if (title != null && title.length() > 0) {
                fireAutoTest(title);
            }
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutoTest(intent);
    }

    private void handleAutoTest(Intent intent) {
        if (intent == null) {
            return;
        }
        final int atIdx = intent.getIntExtra("autotestIdx", -1);
        String at = intent.getStringExtra("autotest");
        if (atIdx >= 0) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fireAutoTestIdx(atIdx);
                }
            }, 1200);
        } else if (at != null && at.length() > 0) {
            final String needle = at;
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fireAutoTest(needle);
                }
            }, 1200);
        }
    }

    /** adb 调试用：直接添加一条手动作业（与弹窗保存逻辑一致）。 */
    private void addCustomItem(String title, String course, String deadlineRaw) {
        if (title == null || title.trim().isEmpty()) {
            return;
        }
        long dl = parseCustomDeadline(deadlineRaw == null ? "" : deadlineRaw);
        CxClient.Item nit = new CxClient.Item();
        nit.kind = "custom";
        nit.title = title.trim();
        nit.course = course == null ? "" : course.trim();
        nit.deadline = dl > 0 ? fmtTime(dl) : null;
        nit.start = null;
        nit.state = "todo";
        nit.statusText = "手动";
        nit.url = "";
        customs.add(nit);
        saveCustoms();
        renderHomeworks();
        setStatus("CUSTOM: +" + nit.title + " | 截止 " + nit.deadline + " | 共 " + customs.size() + " 条手动");
        android.util.Log.i("CxJump", "addCustom " + nit.title + " dl=" + nit.deadline);
    }

    private void fireAutoTestIdx(int idx) {
        List<CxClient.Item> all = allItems();
        android.util.Log.i("CxJump", "autotestIdx=" + idx + " total=" + all.size()
                + " alive=" + JumpService.isAlive());
        setStatus("JUMP: idx=" + idx + " n=" + all.size() + " alive=" + JumpService.isAlive());
        if (idx >= all.size()) {
            setStatus("JUMP: idx 越界 " + idx + " >= " + all.size());
            return;
        }
        jumpToApp(all.get(idx));
    }

    @Override
    public void onBackPressed() {
        if (browseLayer != null && browseLayer.getVisibility() == View.VISIBLE) {
            if (browseWeb.canGoBack()) {
                browseWeb.goBack();
            } else {
                hideBrowse();
            }
            return;
        }
        if (webLayer != null && webLayer.getVisibility() == View.VISIBLE) {
            hideLogin();
            if (cookie.isEmpty()) {
                finish();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        if (browseWeb != null) {
            browseWeb.stopLoading();
            browseWeb.destroy();
        }
        ui.removeCallbacksAndMessages(null);
    }

    private int color(String name) {
        int id = getResources().getIdentifier(name, "color", getPackageName());
        return id == 0 ? Color.GRAY : getResources().getColor(id);
    }

    private void loadColors() {
        cPrimary = color("primary");
        cFg = color("fg");
    }

    // ------------------------------------------------------------ 界面

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF8F5FD);

        aurora = new AuroraView(this);
        root.addView(aurora, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 下拉刷新头 + 滚动区
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        refreshHeader = text("", 12, 0xFF6B5B8C, SANS_MEDIUM);
        refreshHeader.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        column.addView(refreshHeader, rhLp);

        scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(112));

        col.addView(buildHero(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = dp(14);
        col.addView(listBox, lbLp);

        scroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        attachPullGesture();

        // 进度块（扫描时浮在 dock 上方）
        progressCard = new GlassView(this);
        progressCard.radius(22).tint(0xA6FFFFFF).edge(0x99FFFFFF).highlight(0x55);
        softShadow(progressCard, 8);
        LinearLayout pcCol = new LinearLayout(this);
        pcCol.setOrientation(LinearLayout.VERTICAL);
        pcCol.setPadding(dp(14), dp(10), dp(14), dp(9));

        LinearLayout pcRow = new LinearLayout(this);
        pcRow.setOrientation(LinearLayout.HORIZONTAL);
        pcRow.setGravity(Gravity.CENTER_VERTICAL);
        progressLabel = text("", 12, 0xFF4A3F63, SANS);
        progressLabel.setSingleLine(true);
        progressLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pcRow.addView(progressLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cancelPill = pill("取消", 0xFFBE123C, 0x33BE123C);
        cancelPill.setPadding(dp(12), dp(5), dp(12), dp(5));
        cancelPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelFlag[0] = true;
                progressLabel.setText("正在取消…");
            }
        });
        pcRow.addView(cancelPill);
        pcCol.addView(pcRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        barTrack = new LinearLayout(this);
        barTrack.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(dp(2));
        trackBg.setColor(0x1A3B2A66);
        barTrack.setBackground(trackBg);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        btLp.topMargin = dp(9);
        barFill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(dp(2));
        fillBg.setColor(0xFF6C4BB6);
        barFill.setBackground(fillBg);
        barRest = new View(this);
        barTrack.addView(barFill, new LinearLayout.LayoutParams(0, dp(3), 0.02f));
        barTrack.addView(barRest, new LinearLayout.LayoutParams(0, dp(3), 0.98f));
        pcCol.addView(barTrack, btLp);
        progressCard.addView(pcCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams pcLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        pcLp.leftMargin = dp(14);
        pcLp.rightMargin = dp(14);
        pcLp.bottomMargin = dp(14) + dp(64) + dp(14);
        root.addView(progressCard, pcLp);
        progressCard.setVisibility(View.GONE);

        // dock
        GlassView dock = new GlassView(this);
        dock.radius(30).tint(0x80FFFFFF).edge(0x99FFFFFF).highlight(0x59).sheen();
        softShadow(dock, 10);

        actionBtn = text("刷新", 15, 0xFF3B2A66, SANS_MEDIUM);
        actionBtn.setGravity(Gravity.CENTER);
        actionBtn.setIncludeFontPadding(false);
        actionBtn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x1F6C4BB6), null, round(0xFFFFFFFF, 24, 0)));
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setPadding(dp(10), 0, dp(10), 0);
        dockRow.setGravity(Gravity.CENTER_VERTICAL);
        dockRow.addView(actionBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        dock.addView(dockRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        dockLp.leftMargin = dp(14);
        dockLp.rightMargin = dp(14);
        dockLp.bottomMargin = dp(14);
        root.addView(dock, dockLp);
        bottomBar = dock;

        webLayer = buildLoginLayer();
        webLayer.setElevation(dp(24));
        root.addView(webLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        browseLayer = buildBrowseLayer();
        browseLayer.setElevation(dp(24));
        root.addView(browseLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View buildHero() {
        GlassView glass = new GlassView(this);
        glass.radius(30).tint(0x59FFFFFF).edge(0xB3FFFFFF).highlight(0x66);
        softShadow(glass, 6);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(16), dp(20), dp(18));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        dot.setBackground(round(0xFF4ADE80, 999, 0));
        row.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        accountLabel = text("未登录", 12, 0xFF4A3F63, SANS);
        LinearLayout.LayoutParams accLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        accLp.leftMargin = dp(8);
        row.addView(accountLabel, accLp);

        TextView refresh = pill("刷新", 0xFF3B2A66, 0x4DFFFFFF);
        refresh.setPadding(dp(13), dp(6), dp(13), dp(6));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        row.addView(refresh);

        TextView switchAcc = pill("切换账号", 0xFF3B2A66, 0x4DFFFFFF);
        switchAcc.setPadding(dp(13), dp(6), dp(13), dp(6));
        switchAcc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogin();
            }
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.leftMargin = dp(8);
        row.addView(switchAcc, swLp);

        TextView addPill = pill("＋ 手动", 0xFF3B2A66, 0x4DFFFFFF);
        addPill.setPadding(dp(13), dp(6), dp(13), dp(6));
        addPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCustomDialog(null);
            }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.leftMargin = dp(8);
        row.addView(addPill, addLp);
        hero.addView(row);

        LinearLayout numRow = new LinearLayout(this);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams nrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nrLp.topMargin = dp(14);
        heroCount = text("0", 50, 0xFF3B2A66, SANS_BLACK);
        heroCount.setIncludeFontPadding(false);
        numRow.addView(heroCount);
        TextView unit = text("项待完成", 14, 0xFF5A4B78, SANS);
        unit.setPadding(0, 0, 0, dp(11));
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uLp.leftMargin = dp(10);
        numRow.addView(unit, uLp);
        hero.addView(numRow, nrLp);

        heroMeta = text("下拉刷新，或点「刷新」", 12.5f, 0xB33B2A66, SANS);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(2);
        hero.addView(heroMeta, mLp);

        // 学期行：点一下填入学年份，算出第几学期
        termLine = text(termSummary(), 12, 0xFF5A4B78, SANS_MEDIUM);
        termLine.setPadding(dp(10), dp(5), dp(10), dp(5));
        termLine.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x1F6C4BB6), round(0x4DFFFFFF, 999, 0x66FFFFFF), null));
        termLine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEnrollDialog();
            }
        });
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.topMargin = dp(10);
        hero.addView(termLine, tlLp);

        // 状态行 + 诊断入口
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusLine = text("", 11.5f, 0x993B2A66, SANS);
        statusLine.setSingleLine(true);
        statusLine.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        statusLine.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (lastDebug.length() > 0) {
                    copyText(lastDebug, "已复制诊断信息");
                }
                return true;
            }
        });
        statusRow.addView(statusLine, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView diagPill = pill("诊断", 0xFF3B2A66, 0x4DFFFFFF);
        diagPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        diagPill.setTextSize(11);
        diagPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiag();
            }
        });
        LinearLayout.LayoutParams dgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dgLp.leftMargin = dp(8);
        statusRow.addView(diagPill, dgLp);

        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(8);
        hero.addView(statusRow, srLp);

        glass.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return glass;
    }

    private FrameLayout buildLoginLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(Color.WHITE);
        layer.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(20), dp(10), dp(14), dp(12));
        bar.setElevation(dp(1));

        TextView title = text("登录学习通", 17, cFg, SANS_MEDIUM);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = pill("完成登录", Color.WHITE, cPrimary);
        done.setPadding(dp(16), dp(9), dp(16), dp(9));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryExtractCookie(true);
            }
        });
        bar.addView(done);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                v.loadUrl(url);
                return true;
            }
        });
        col.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        layer.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return layer;
    }

    /** 点作业卡片后的应用内浏览层。 */
    private FrameLayout buildBrowseLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(Color.WHITE);
        layer.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setElevation(dp(1));

        TextView back = pill("← 返回", 0xFF3B2A66, 0x262B1B4D);
        back.setPadding(dp(12), dp(7), dp(12), dp(7));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideBrowse();
            }
        });
        bar.addView(back);

        browseTitle = text("", 14, 0xFF241C3D, SANS_MEDIUM);
        browseTitle.setSingleLine(true);
        browseTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btLp.leftMargin = dp(10);
        bar.addView(browseTitle, btLp);

        TextView open = pill("↗ 浏览器", 0xFF3B2A66, 0x262B1B4D);
        open.setPadding(dp(10), dp(7), dp(10), dp(7));
        open.setTextSize(11);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (browseWeb.getUrl() != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse(browseWeb.getUrl())));
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        bar.addView(open);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        browseWeb = new WebView(this);
        WebSettings bs = browseWeb.getSettings();
        bs.setJavaScriptEnabled(true);
        bs.setDomStorageEnabled(true);
        bs.setLoadWithOverviewMode(true);
        bs.setUseWideViewPort(true);
        bs.setSupportZoom(true);
        bs.setBuiltInZoomControls(true);
        bs.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browseWeb, true);
        browseWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                v.loadUrl(url);
                return true;
            }
        });
        col.addView(browseWeb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        layer.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return layer;
    }

    // ------------------------------------------------------------ 下拉刷新

    private void attachPullGesture() {
        scroll.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = ev.getY();
                        pulling = !scanning && scroll.getScrollY() == 0;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (pulling && scroll.getScrollY() == 0) {
                            float dy = ev.getY() - downY;
                            if (dy > 0) {
                                int h = (int) Math.min(dp(PULL_MAX_DP), dy);
                                setHeaderHeight(h);
                                refreshHeader.setText(h >= dp(PULL_TRIGGER_DP)
                                        ? "松手刷新" : "下拉刷新");
                                return false;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (pulling) {
                            int h = refreshHeader.getHeight();
                            if (h >= dp(PULL_TRIGGER_DP)) {
                                pullTriggered = true;
                                startScan();
                            } else {
                                collapseHeader();
                            }
                            pulling = false;
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private void setHeaderHeight(int px) {
        if (refreshHeader == null) {
            return;
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) refreshHeader.getLayoutParams();
        if (lp.height != px) {
            lp.height = px;
            refreshHeader.setLayoutParams(lp);
        }
    }

    private void collapseHeader() {
        setHeaderHeight(0);
        if (refreshHeader != null) {
            refreshHeader.setText("");
        }
        pullTriggered = false;
    }

    // ------------------------------------------------------------ 登录

    private void showLogin() {
        if (webLayer == null) {
            return;
        }
        webLayer.setVisibility(View.VISIBLE);
        if (bottomBar != null) {
            bottomBar.setVisibility(View.GONE);
        }
        web.loadUrl("https://i.chaoxing.com/base");
        startPollLogin();
    }

    private void hideLogin() {
        if (webLayer == null) {
            return;
        }
        webLayer.setVisibility(View.GONE);
        if (bottomBar != null) {
            bottomBar.setVisibility(View.VISIBLE);
        }
        stopPollLogin();
    }

    private Runnable pollLogin;

    private void startPollLogin() {
        stopPollLogin();
        pollLogin = new Runnable() {
            @Override
            public void run() {
                String ck = readCookie();
                if (ck.contains("_uid=")) {
                    cookie = ck;
                    sp.edit().putString("cookie", ck).apply();
                    hideLogin();
                    setAccountLabel(true);
                    Toast.makeText(MainActivity.this, "登录成功，正在扫描作业", Toast.LENGTH_SHORT).show();
                    startScan();
                    return;
                }
                ui.postDelayed(this, 800);
            }
        };
        ui.postDelayed(pollLogin, 800);
    }

    private void stopPollLogin() {
        if (pollLogin != null) {
            ui.removeCallbacks(pollLogin);
            pollLogin = null;
        }
    }

    private void tryExtractCookie(boolean toast) {
        String ck = readCookie();
        if (ck.contains("_uid=")) {
            cookie = ck;
            sp.edit().putString("cookie", ck).apply();
            hideLogin();
            setAccountLabel(true);
            startScan();
        } else if (toast) {
            Toast.makeText(this, "还没登录成功，先完成扫码或账号登录", Toast.LENGTH_SHORT).show();
        }
    }

    private String readCookie() {
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        String[] hosts = {"https://i.chaoxing.com", "https://mooc1.chaoxing.com",
                "https://mooc1-api.chaoxing.com", "https://chaoxing.com",
                "https://passport2.chaoxing.com"};
        Map<String, String> merged = new HashMap<String, String>();
        for (int i = 0; i < hosts.length; i++) {
            String raw = cm.getCookie(hosts[i]);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String[] parts = raw.split(";");
            for (int j = 0; j < parts.length; j++) {
                int eq = parts[j].indexOf('=');
                if (eq > 0) {
                    merged.put(parts[j].substring(0, eq).trim(), parts[j].substring(eq + 1).trim());
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void setAccountLabel(boolean loggedIn) {
        if (accountLabel == null) {
            return;
        }
        String label = "未登录";
        if (loggedIn && !cookie.isEmpty()) {
            String uid = "";
            String[] parts = cookie.split(";");
            for (int i = 0; i < parts.length; i++) {
                String[] kv = parts[i].trim().split("=", 2);
                if (kv.length == 2 && ("_uid".equals(kv[0]) || "UID".equals(kv[0]))) {
                    uid = kv[1];
                }
            }
            label = uid.isEmpty() ? "已登录" : "已登录 · " + mask(uid);
        }
        accountLabel.setText(label);
    }

    private String mask(String uid) {
        if (uid.length() <= 4) {
            return uid;
        }
        return uid.substring(0, 3) + "***" + uid.substring(uid.length() - 2);
    }

    // ------------------------------------------------------------ 扫描

    private void startScan() {
        if (scanning) {
            return;
        }
        if (demoMode) {
            onScanDone(CxClient.demo());
            return;
        }
        if (cookie.isEmpty()) {
            showLogin();
            return;
        }
        scanning = true;
        cancelFlag[0] = false;
        actionBtn.setText("扫描中…");
        progressCard.setVisibility(View.VISIBLE);
        progressLabel.setText("正在获取课程列表…");
        setBar(0);
        if (pullTriggered) {
            setHeaderHeight(dp(HEADER_OPEN_DP));
            refreshHeader.setText("正在刷新…");
        }
        final Map<String, String> learnedIn = loadTermCache();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final CxClient.ScanResult r = CxClient.scan(cookie, new CxClient.Progress() {
                    @Override
                    public void step(final int done, final int total, final String course) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                progressLabel.setText("读取 " + done + "/" + total + " · " + course);
                                setBar(total <= 0 ? 0 : (float) done / total);
                            }
                        });
                    }
                }, cancelFlag, learnedIn);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        actionBtn.setText("刷新");
                        progressCard.setVisibility(View.GONE);
                        collapseHeader();
                        onScanDone(r);
                    }
                });
            }
        }).start();
    }

    private void setBar(float p) {
        if (barFill == null || barRest == null) {
            return;
        }
        LinearLayout.LayoutParams lf = (LinearLayout.LayoutParams) barFill.getLayoutParams();
        LinearLayout.LayoutParams lr = (LinearLayout.LayoutParams) barRest.getLayoutParams();
        float fill = Math.max(0.03f, Math.min(1f, p));
        lf.weight = fill;
        lr.weight = 1f - fill;
        barFill.setLayoutParams(lf);
        barRest.setLayoutParams(lr);
    }

    private void onScanDone(CxClient.ScanResult r) {
        items.clear();
        items.addAll(r.items);
        lastScanTime = System.currentTimeMillis();
        saveCache();
        if (!r.learnedTerms.isEmpty()) {
            mergeTermCache(r.learnedTerms);
        }
        lastDebug = "course=" + r.courseCount + " items=" + r.items.size() + "\n" + r.debug;
        doneOpen = false;
        pastOpen = false;
        submitOpen = false;
        activeOpen = false;
        renderHomeworks();
        if (r.error != null) {
            setStatus(r.error + " · 点「诊断」看原因");
        } else {
            String skip = r.skippedCourses > 0 ? ("，跳过 " + r.skippedCourses + " 门往期") : "";
            setStatus(r.scannedCount + " 门课" + skip + " · " + r.items.size() + " 条 · "
                    + timeOf(lastScanTime));
        }
    }

    private String timeOf(long t) {
        if (t <= 0) {
            return "";
        }
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(t));
    }

    private String cacheLabel() {
        if (lastScanTime > 0) {
            return "缓存于 " + timeOf(lastScanTime);
        }
        return "缓存数据";
    }

    private void setStatus(String s) {
        if (statusLine != null) {
            statusLine.setText(s);
        }
    }

    // ------------------------------------------------------------ 缓存

    private boolean loadCache() {
        String blob = sp.getString("cache", "");
        if (blob == null || blob.isEmpty()) {
            return false;
        }
        lastScanTime = sp.getLong("cacheTime", 0);
        items.clear();
        String[] lines = blob.split("\n");
        for (int i = 0; i < lines.length; i++) {
            CxClient.Item it = decodeItem(lines[i]);
            if (it != null) {
                items.add(it);
            }
        }
        lastDebug = sp.getString("cacheDebug", "");
        return !items.isEmpty();
    }

    private void saveCache() {
        sp.edit().putString("cache", encodeItems()).putLong("cacheTime", lastScanTime)
                .putString("cacheDebug", lastDebug).apply();
    }

    private String encodeItems() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            CxClient.Item it = items.get(i);
            sb.append(f(it.kind)).append('\t').append(f(it.title)).append('\t')
                    .append(f(it.course)).append('\t').append(f(it.deadline)).append('\t')
                    .append(f(it.start)).append('\t').append(f(it.state)).append('\t')
                    .append(f(it.statusText)).append('\t').append(f(it.url)).append('\n');
        }
        return sb.toString();
    }

    private CxClient.Item decodeItem(String line) {
        if (line == null || line.length() < 3) {
            return null;
        }
        String[] f = line.split("\t", -1);
        if (f.length < 8) {
            return null;
        }
        CxClient.Item it = new CxClient.Item();
        it.kind = f[0];
        it.title = f[1];
        it.course = f[2];
        it.deadline = f[3].isEmpty() ? null : f[3];
        it.start = f[4].isEmpty() ? null : f[4];
        it.state = f[5];
        it.statusText = f[6];
        it.url = f[7];
        return it;
    }

    private String f(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    /** 课程学期自学习缓存：classId → "26-27-1"。 */
    private Map<String, String> loadTermCache() {
        Map<String, String> out = new HashMap<String, String>();
        String blob = sp.getString("termcache", "");
        if (blob != null && !blob.isEmpty()) {
            String[] lines = blob.split("\n");
            for (int i = 0; i < lines.length; i++) {
                int t = lines[i].indexOf('\t');
                if (t > 0) {
                    out.put(lines[i].substring(0, t), lines[i].substring(t + 1));
                }
            }
        }
        return out;
    }

    private void mergeTermCache(Map<String, String> add) {
        Map<String, String> all = loadTermCache();
        all.putAll(add);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        sp.edit().putString("termcache", sb.toString()).apply();
    }

    // ------------------------------------------------------------ 学期

    /** 一条记录的学期归属：截止时间 → 开始时间 → 课程名标记。 */
    private Term termOfItem(CxClient.Item it) {
        Term t = Term.ofTime(Term.parse(it.deadline));
        if (t == null) {
            t = Term.ofTime(Term.parse(it.start));
        }
        if (t == null) {
            t = Term.ofName(it.course);
        }
        return t;
    }

    private boolean isCurrentTerm(CxClient.Item it) {
        Term t = termOfItem(it);
        return t == null || t.same(Term.now());
    }

    private String termSummary() {
        Term cur = Term.now();
        StringBuilder sb = new StringBuilder();
        sb.append("学期 ").append(cur.label());
        int enrollYear = sp.getInt("enrollYear", 0);
        if (enrollYear > 0) {
            int idx = (cur.startYear - enrollYear) * 2 + cur.half;
            if (idx < 1) {
                idx = 1;
            }
            sb.append(" · ").append(enrollYear).append(" 级 · 第 ").append(idx).append(" 学期");
        } else {
            sb.append(" · 点这里填入学年份");
        }
        return sb.toString();
    }

    private void refreshTermLine() {
        if (termLine != null) {
            termLine.setText(termSummary());
        }
    }

    private void showEnrollDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("例如 2025");
        int enrollYear = sp.getInt("enrollYear", 0);
        if (enrollYear > 0) {
            input.setText(String.valueOf(enrollYear));
        }
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("入学年份")
                .setMessage("填 4 位年份，用来算「第几学期」。学期按 8 月—次年 1 月为秋季、2—7 月为春季。")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int y = Integer.parseInt(input.getText().toString().trim());
                            if (y > 1990 && y < 2100) {
                                sp.edit().putInt("enrollYear", y).apply();
                                refreshTermLine();
                                renderHomeworks();
                            } else {
                                Toast.makeText(MainActivity.this, "填 4 位年份，例如 2025",
                                        Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable t) {
                            Toast.makeText(MainActivity.this, "填 4 位年份，例如 2025",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 渲染

    private List<CxClient.Item> allItems() {
        List<CxClient.Item> out = new ArrayList<CxClient.Item>(items);
        out.addAll(customs);
        return out;
    }

    private List<CxClient.Item> activeItems() {
        List<CxClient.Item> out = new ArrayList<CxClient.Item>();
        List<CxClient.Item> src = allItems();
        for (int i = 0; i < src.size(); i++) {
            CxClient.Item it = src.get(i);
            if (!"finished".equals(it.state) && !"submitted".equals(it.state)
                    && isCurrentTerm(it)) {
                out.add(it);
            }
        }
        sortByDeadline(out, false);
        return out;
    }

    private List<CxClient.Item> submittedItems() {
        List<CxClient.Item> out = new ArrayList<CxClient.Item>();
        List<CxClient.Item> src = allItems();
        for (int i = 0; i < src.size(); i++) {
            CxClient.Item it = src.get(i);
            if ("submitted".equals(it.state) && isCurrentTerm(it)) {
                out.add(it);
            }
        }
        sortByDeadline(out, false);
        return out;
    }

    private List<CxClient.Item> pastItems() {
        List<CxClient.Item> out = new ArrayList<CxClient.Item>();
        List<CxClient.Item> src = allItems();
        for (int i = 0; i < src.size(); i++) {
            CxClient.Item it = src.get(i);
            if (!"finished".equals(it.state) && !isCurrentTerm(it)) {
                out.add(it);
            }
        }
        sortByDeadline(out, true);
        return out;
    }

    private List<CxClient.Item> doneItems() {
        List<CxClient.Item> out = new ArrayList<CxClient.Item>();
        List<CxClient.Item> src = allItems();
        for (int i = 0; i < src.size(); i++) {
            if ("finished".equals(src.get(i).state)) {
                out.add(src.get(i));
            }
        }
        return out;
    }

    private void sortByDeadline(List<CxClient.Item> list, final boolean newestFirst) {
        java.util.Collections.sort(list, new java.util.Comparator<CxClient.Item>() {
            @Override
            public int compare(CxClient.Item a, CxClient.Item b) {
                long ta = Term.parse(a.deadline);
                long tb = Term.parse(b.deadline);
                if (ta == 0 && tb == 0) {
                    return 0;
                }
                if (ta == 0) {
                    return 1;
                }
                if (tb == 0) {
                    return -1;
                }
                int r = ta < tb ? -1 : (ta > tb ? 1 : 0);
                return newestFirst ? -r : r;
            }
        });
    }

    private void renderHomeworks() {
        if (listBox == null) {
            return;
        }
        listBox.removeAllViews();
        List<CxClient.Item> active = activeItems();
        List<CxClient.Item> submitted = submittedItems();
        List<CxClient.Item> past = pastItems();
        List<CxClient.Item> done = doneItems();

        heroCount.setText(String.valueOf(active.size()));
        String nearest = null;
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).deadline != null && active.get(i).deadline.length() >= 10) {
                nearest = active.get(i).deadline;
                break;
            }
        }
        if (allItems().isEmpty()) {
            heroMeta.setText("下拉或点「刷新」拉取作业，或「＋」手动添加");
            listBox.addView(buildEmptyHint(), wrapLp());
            return;
        }
        StringBuilder meta = new StringBuilder();
        meta.append("待完成 ").append(active.size());
        if (!submitted.isEmpty()) {
            meta.append(" · 待批 ").append(submitted.size());
        }
        if (!past.isEmpty()) {
            meta.append(" · 往期 ").append(past.size());
        }
        meta.append(" · 已完成 ").append(done.size());
        if (nearest != null) {
            meta.append(" · 最近截止 ").append(shortDate(nearest));
        }
        heroMeta.setText(meta.toString());

        if (active.isEmpty() && submitted.isEmpty() && past.isEmpty() && done.isEmpty()) {
            listBox.addView(buildEmptyHint(), wrapLp());
            return;
        }

        if (!active.isEmpty()) {
            listBox.addView(buildFoldRow("待完成", active.size(), K_ACTIVE), wrapLp());
            activeBox = new LinearLayout(this);
            activeBox.setOrientation(LinearLayout.VERTICAL);
            activeBox.setVisibility(View.VISIBLE);
            listBox.addView(activeBox, wrapLp(9));
            fillActiveBox();
        } else if (!allItems().isEmpty()) {
            listBox.addView(sectionLabel("当前没有要交的作业"), wrapLp());
        }

        if (!submitted.isEmpty()) {
            listBox.addView(buildFoldRow("已交待批阅", submitted.size(), K_SUBMIT), wrapLp(9));
            submitBox = new LinearLayout(this);
            submitBox.setOrientation(LinearLayout.VERTICAL);
            submitBox.setVisibility(submitOpen ? View.VISIBLE : View.GONE);
            if (submitOpen) {
                fillBox(submitBox, submittedItems());
            }
            listBox.addView(submitBox, wrapLp(9));
        }

        if (!past.isEmpty()) {
            listBox.addView(buildFoldRow("往期学期", past.size(), K_PAST), wrapLp(9));
            pastBox = new LinearLayout(this);
            pastBox.setOrientation(LinearLayout.VERTICAL);
            pastBox.setVisibility(pastOpen ? View.VISIBLE : View.GONE);
            if (pastOpen) {
                fillBox(pastBox, pastItems());
            }
            listBox.addView(pastBox, wrapLp(9));
        }

        if (!done.isEmpty()) {
            listBox.addView(buildFoldRow("已完成", done.size(), K_DONE), wrapLp(9));
            doneBox = new LinearLayout(this);
            doneBox.setOrientation(LinearLayout.VERTICAL);
            doneBox.setVisibility(doneOpen ? View.VISIBLE : View.GONE);
            if (doneOpen) {
                fillBox(doneBox, doneItems());
            }
            listBox.addView(doneBox, wrapLp(9));
        }

        TextView foot = text("— 本学期就这些 —", 11.5f, 0x663B2A66, SANS);
        foot.setGravity(Gravity.CENTER);
        listBox.addView(foot, wrapLp(26));
    }

    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapLp(int topMarginDp) {
        LinearLayout.LayoutParams lp = wrapLp();
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = wrapLp();
        lp.bottomMargin = dp(9);
        return lp;
    }

    private void fillBox(LinearLayout box, List<CxClient.Item> data) {
        if (box == null) {
            return;
        }
        box.removeAllViews();
        for (int i = 0; i < data.size(); i++) {
            box.addView(buildItemCard(data.get(i)), cardLp());
        }
    }

    /** 待完成默认只露最近一项，展开才看全部。 */
    private void fillActiveBox() {
        if (activeBox == null) {
            return;
        }
        activeBox.removeAllViews();
        List<CxClient.Item> data = activeItems();
        int show = activeOpen ? data.size() : Math.min(1, data.size());
        for (int i = 0; i < show; i++) {
            activeBox.addView(buildItemCard(data.get(i)), cardLp());
        }
    }

    private static final int K_ACTIVE = 0;
    private static final int K_SUBMIT = 1;
    private static final int K_PAST = 2;
    private static final int K_DONE = 3;

    /** 折叠行：label · n + 展开/收起。点整行切换。 */
    private View buildFoldRow(String label, int n, final int kind) {
        GlassView card = new GlassView(this);
        card.radius(22).tint(0x59FFFFFF).edge(0x8CFFFFFF).highlight(0x4D);
        softShadow(card, 2);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));

        String glyph = kind == K_DONE ? "✓" : (kind == K_SUBMIT ? "批" : "!");
        int ink;
        int markBg;
        if (kind == K_DONE) {
            ink = 0xFF15803D;
            markBg = 0xFFDCFCE7;
        } else if (kind == K_SUBMIT) {
            ink = 0xFF4C5BB8;
            markBg = 0xFFE0E4F8;
        } else if (kind == K_PAST) {
            ink = 0xFF8A6D3B;
            markBg = 0xFFF3EAD8;
        } else {
            ink = 0xFFBE123C;
            markBg = 0xFFFFE4E6;
        }
        TextView mark = text(glyph, 15, ink, SANS_BLACK);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(markBg);
        mark.setBackground(bg);
        row.addView(mark, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView title = text(label + " · " + n, 14, 0xFF4A3F63, SANS_MEDIUM);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lLp.leftMargin = dp(11);
        row.addView(title, lLp);

        boolean open = kind == K_ACTIVE ? activeOpen
                : (kind == K_SUBMIT ? submitOpen
                : (kind == K_PAST ? pastOpen : doneOpen));
        final TextView toggle = text(open ? "收起" : "展开", 12, 0xFF6B5B8C, SANS_MEDIUM);
        row.addView(toggle);
        if (kind == K_ACTIVE) {
            activeToggle = toggle;
        } else if (kind == K_SUBMIT) {
            submitToggle = toggle;
        } else if (kind == K_PAST) {
            pastToggle = toggle;
        } else {
            doneToggle = toggle;
        }

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (kind == K_ACTIVE) {
                    activeOpen = !activeOpen;
                    if (activeToggle != null) {
                        activeToggle.setText(activeOpen ? "收起" : "展开");
                    }
                    fillActiveBox();
                } else if (kind == K_SUBMIT) {
                    submitOpen = !submitOpen;
                    if (submitToggle != null) {
                        submitToggle.setText(submitOpen ? "收起" : "展开");
                    }
                    if (submitBox != null) {
                        if (submitOpen) {
                            fillBox(submitBox, submittedItems());
                            submitBox.setVisibility(View.VISIBLE);
                        } else {
                            submitBox.setVisibility(View.GONE);
                        }
                    }
                } else if (kind == K_PAST) {
                    pastOpen = !pastOpen;
                    if (pastToggle != null) {
                        pastToggle.setText(pastOpen ? "收起" : "展开");
                    }
                    if (pastBox != null) {
                        if (pastOpen) {
                            fillBox(pastBox, pastItems());
                            pastBox.setVisibility(View.VISIBLE);
                        } else {
                            pastBox.setVisibility(View.GONE);
                        }
                    }
                } else {
                    doneOpen = !doneOpen;
                    if (doneToggle != null) {
                        doneToggle.setText(doneOpen ? "收起" : "展开");
                    }
                    if (doneBox != null) {
                        if (doneOpen) {
                            fillBox(doneBox, doneItems());
                            doneBox.setVisibility(View.VISIBLE);
                        } else {
                            doneBox.setVisibility(View.GONE);
                        }
                    }
                }
            }
        });
        return card;
    }

    private View sectionLabel(String s) {
        TextView tv = text(s, 12.5f, 0x993B2A66, SANS_MEDIUM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        lp.bottomMargin = dp(9);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildEmptyHint() {
        GlassView card = new GlassView(this);
        card.radius(24).tint(0x59FFFFFF).edge(0x8CFFFFFF).highlight(0x40);
        softShadow(card, 2);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(20), dp(30), dp(20), dp(30));

        TextView title = text("没有作业", 16, 0xFF3B2A66, SANS_MEDIUM);
        title.setGravity(Gravity.CENTER);
        col.addView(title);

        TextView hint = text("下拉或点「刷新」重新拉一次\n还是空的就点「诊断」看原因", 12, 0x993B2A66, SANS);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(8);
        col.addView(hint, hLp);

        card.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View buildItemCard(final CxClient.Item it) {
        final boolean finished = "finished".equals(it.state);
        final boolean submitted = "submitted".equals(it.state);
        final boolean custom = "custom".equals(it.kind);
        GlassView card = new GlassView(this);
        card.radius(22)
                .tint(finished ? 0x40FFFFFF : (submitted ? 0x59FFFFFF : 0x66FFFFFF))
                .edge(finished ? 0x8CFFFFFF : 0x99FFFFFF)
                .highlight(finished ? 0x40 : 0x59);
        softShadow(card, finished ? 2 : 3);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));

        String glyph = custom ? "✎" : (finished ? "✓" : (submitted ? "批"
                : ("unknown".equals(it.state) ? "?" : "!")));
        int ink = custom ? 0xFF6C4BB6 : (finished ? 0xFF15803D : (submitted ? 0xFF4C5BB8
                : ("unknown".equals(it.state) ? 0xFFB45309 : 0xFFBE123C)));
        int markBg = custom ? 0xFFEDE6F8 : (finished ? 0xFFDCFCE7 : (submitted ? 0xFFE0E4F8
                : ("unknown".equals(it.state) ? 0xFFFEF3C7 : 0xFFFFE4E6)));
        TextView mark = text(glyph, 16, ink, SANS_BLACK);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(markBg);
        mark.setBackground(bg);
        row.addView(mark, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = dp(12);
        colLp.rightMargin = dp(10);

        TextView title = text(it.title, 14.5f,
                finished ? 0xA63B2A66 : 0xFF241C3D, SANS_MEDIUM);
        title.setMaxLines(2);
        title.setLineSpacing(0, 1.05f);
        col.addView(title);

        String kind = custom ? "手动" : ("work".equals(it.kind) ? "作业" : "考试");
        Term itTerm = termOfItem(it);
        String scope = custom ? "" : ((itTerm != null && !isCurrentTerm(it)) ? (" · " + itTerm.shortLabel()) : "");
        String coursePart = (it.course == null || it.course.length() == 0) ? "未写课程" : it.course;
        TextView sub = text(kind + " · " + coursePart + scope, 11.5f, 0x993B2A66, SANS);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        col.addView(sub, subLp);
        row.addView(col, colLp);

        String badgeText = shortDeadline(it);
        boolean overdue = badgeText.startsWith("已逾期");
        int badgeInk = finished ? 0xFF15803D
                : (submitted ? 0xFF4C5BB8 : (overdue ? 0xFFBE123C : 0xFF3B2A66));
        int badgeBg = finished ? 0xFFDCFCE7
                : (submitted ? 0xFFE0E4F8 : (overdue ? 0xFFFFE4E6 : 0x4DFFFFFF));
        TextView badge = pill(badgeText, badgeInk, badgeBg);
        badge.setTextSize(11);
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        row.addView(badge);

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (custom) {
                    showCustomDialog(it);
                    return;
                }
                jumpToApp(it);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (custom) {
                    copyText(customLine(it), "已复制该条信息");
                    return true;
                }
                if (it.url != null && it.url.length() > 8) {
                    showBrowse(it.url, it.title);
                } else {
                    copyText(customLine(it), "该条没有链接，已复制信息");
                }
                return true;
            }
        });
        return card;
    }

    private String customLine(CxClient.Item it) {
        String kind = "custom".equals(it.kind) ? "手动" : ("work".equals(it.kind) ? "作业" : "考试");
        return kind + " | " + (it.course == null ? "-" : it.course)
                + " | " + it.title + " | " + (it.deadline == null ? "-" : it.deadline)
                + " | " + it.statusText;
    }

    // ------------------------------------------------------------ 跳转学习通

    private void jumpToApp(final CxClient.Item it) {
        boolean exam = "exam".equals(it.kind);
        android.util.Log.i("CxJump", "jumpToApp title=" + it.title + " alive=" + JumpService.isAlive());
        if (JumpService.isAlive()) {
            setStatus("JUMP: 服务已就绪，拉起学习通…");
            JumpService.offer(it.course, it.title, exam, it.url);
            // 显式组件启动（包可见性限制下 getLaunchIntentForPackage 可能返回 null）
            Intent launch = new Intent();
            launch.setComponent(new android.content.ComponentName("com.chaoxing.mobile",
                    "com.chaoxing.mobile.activity.SplashActivity"));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
                setStatus("JUMP: 已拉起学习通，等待自动导航");
                android.util.Log.i("CxJump", "launched chaoxing by component");
            } catch (Throwable t) {
                android.util.Log.i("CxJump", "launch fail: " + t);
                Toast.makeText(this, "拉起学习通失败：" + t.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
                if (it.url != null && it.url.length() > 8) {
                    showBrowse(it.url, it.title);
                }
            }
            return;
        }
        setStatus("JUMP: 服务未就绪 alive=false");
        boolean self = trySelfEnableAccessibility();
        android.util.Log.i("CxJump", "selfEnable=" + self);
        if (self) {
            Toast.makeText(this, "已尝试开启跳转服务，再点一次即可跳转", Toast.LENGTH_SHORT).show();
            setStatus("JUMP: 已尝试自开服务");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("开启「自动跳转」？")
                .setMessage("开启后，点作业卡片会直接打开学习通并自动进入对应作业。\n\n"
                        + "路径：设置 → 无障碍 → 已下载的应用 → 作业呢 → 开启。\n\n"
                        + "长按卡片可在应用内打开网页版。")
                .setPositiveButton("去开启", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNeutralButton("应用内打开", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (it.url != null && it.url.length() > 8) {
                            showBrowse(it.url, it.title);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 一键自开无障碍；需 adb pm grant WRITE_SECURE_SETTINGS，失败走手动设置。 */
    private boolean trySelfEnableAccessibility() {
        try {
            String comp = getPackageName() + "/" + JumpService.class.getName();
            String cur = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String next = comp;
            if (cur != null && cur.length() > 0 && !cur.contains(comp)) {
                next = cur + ":" + comp;
            }
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void fireAutoTest(final String needle) {
        if (!JumpService.isAlive()) {
            if (autoEnableTries >= 3) {
                Toast.makeText(this, "AUTO: 无障碍服务没起来", Toast.LENGTH_SHORT).show();
                return;
            }
            autoEnableTries++;
            trySelfEnableAccessibility();
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fireAutoTest(needle);
                }
            }, 1500);
            return;
        }
        List<CxClient.Item> all = allItems();
        for (int i = 0; i < all.size(); i++) {
            CxClient.Item it = all.get(i);
            if (it.title != null && it.title.contains(needle)) {
                Toast.makeText(this, "AUTO: " + it.title, Toast.LENGTH_SHORT).show();
                jumpToApp(it);
                return;
            }
        }
        Toast.makeText(this, "AUTO 未命中: " + needle, Toast.LENGTH_SHORT).show();
    }

    private int autoEnableTries = 0;

    // ------------------------------------------------------------ 自定义作业

    private void loadCustoms() {
        customs.clear();
        String blob = sp.getString("customs", "");
        if (blob == null || blob.isEmpty()) {
            return;
        }
        String[] lines = blob.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                continue;
            }
            CxClient.Item it = decodeItem(lines[i]);
            if (it != null) {
                customs.add(it);
            }
        }
    }

    private void saveCustoms() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < customs.size(); i++) {
            CxClient.Item it = customs.get(i);
            sb.append(f(it.kind)).append('\t').append(f(it.title)).append('\t')
                    .append(f(it.course)).append('\t').append(f(it.deadline)).append('\t')
                    .append(f(it.start)).append('\t').append(f(it.state)).append('\t')
                    .append(f(it.statusText)).append('\t').append(f(it.url)).append('\n');
        }
        sp.edit().putString("customs", sb.toString()).apply();
    }

    private EditText input(String val, String hint) {
        EditText e = new EditText(this);
        e.setText(val);
        e.setHint(hint);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private void showCustomDialog(final CxClient.Item edit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        final EditText inTitle = input(edit == null ? "" : it(edit, "title"), "标题（必填）如 数据结构实验三");
        final EditText inCourse = input(edit == null ? "" : it(edit, "course"), "课程名（选填）如 数据结构");
        final EditText inDeadline = input(edit == null ? "" : it(edit, "deadline"), "截止（选填）如 11-25 或 11-25 23:59");
        box.addView(inTitle);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(8);
        box.addView(inCourse, cLp);
        box.addView(inDeadline, cLp);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(edit == null ? "添加作业" : "编辑作业")
                .setView(box)
                .setPositiveButton(edit == null ? "添加" : "保存",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                String title = inTitle.getText().toString().trim();
                                if (title.isEmpty()) {
                                    Toast.makeText(MainActivity.this, "标题必填", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                String course = inCourse.getText().toString().trim();
                                long dl = parseCustomDeadline(inDeadline.getText().toString());
                                String deadline = dl > 0 ? fmtTime(dl) : null;
                                if (edit == null) {
                                    CxClient.Item nit = new CxClient.Item();
                                    nit.kind = "custom";
                                    nit.title = title;
                                    nit.course = course;
                                    nit.deadline = deadline;
                                    nit.start = null;
                                    nit.state = "todo";
                                    nit.statusText = "手动";
                                    nit.url = "";
                                    customs.add(nit);
                                    Toast.makeText(MainActivity.this, "已添加", Toast.LENGTH_SHORT).show();
                                } else {
                                    edit.title = title;
                                    edit.course = course;
                                    edit.deadline = deadline;
                                    Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                                }
                                saveCustoms();
                                renderHomeworks();
                            }
                        })
                .setNegativeButton("取消", null);
        if (edit != null) {
            builder.setNeutralButton("删除", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    customs.remove(edit);
                    saveCustoms();
                    renderHomeworks();
                    Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                }
            });
        }
        builder.show();
    }

    private String it(CxClient.Item i, String field) {
        if ("title".equals(field)) {
            return i.title == null ? "" : i.title;
        }
        if ("course".equals(field)) {
            return i.course == null ? "" : i.course;
        }
        return i.deadline == null ? "" : i.deadline;
    }

    private long parseCustomDeadline(String s) {
        if (s == null) {
            return 0;
        }
        s = s.trim();
        if (s.length() < 5) {
            return 0;
        }
        long t = Term.parse(s);
        if (t > 0) {
            return t;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,2})-(\\d{1,2})").matcher(s);
        if (m.find()) {
            int mm = Integer.parseInt(m.group(1));
            int yr = Term.now().startYear;
            int fixed = (mm <= 7) ? yr + 1 : yr;
            t = Term.parse(fixed + "-" + s);
        }
        return t;
    }

    private String fmtTime(long t) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(t));
    }

    // ------------------------------------------------------------ 浏览

    private void showBrowse(String url, String title) {
        if (browseLayer == null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
            } catch (Throwable ignored) {
            }
            return;
        }
        browseLayer.setVisibility(View.VISIBLE);
        browseTitle.setText(title == null ? "" : title);
        browseWeb.loadUrl(url);
    }

    private void hideBrowse() {
        if (browseLayer != null) {
            browseLayer.setVisibility(View.GONE);
            browseWeb.stopLoading();
        }
    }

    // ------------------------------------------------------------ 诊断

    private void showDiag() {
        final String body = lastDebug.length() > 0 ? lastDebug : "还没有诊断信息，先扫描一次";
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(10.5f);
        tv.setTextColor(0xFF241C3D);
        tv.setText(body);
        tv.setPadding(dp(16), dp(14), dp(16), dp(16));
        sv.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("扫描诊断")
                .setView(sv)
                .setPositiveButton("复制全部", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        copyText(body, "已复制诊断信息");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------ 文本

    private String shortDate(String deadline) {
        if (deadline == null || deadline.length() < 10) {
            return deadline == null ? "无截止" : deadline;
        }
        String s = deadline.trim();
        if (s.length() >= 16) {
            return s.substring(5, 16);
        }
        return s.substring(5, 10);
    }

    private String shortDeadline(CxClient.Item it) {
        if ("finished".equals(it.state)) {
            return "已完成";
        }
        if ("submitted".equals(it.state)) {
            return "已交";
        }
        long t = Term.parse(it.deadline);
        if (t == 0) {
            return "无截止";
        }
        long diff = t - System.currentTimeMillis();
        if (diff < 0) {
            long days = (-diff) / 86400000L;
            if (days < 1) {
                return "已逾期";
            }
            return "已逾期 " + days + " 天";
        }
        long h = diff / 3600000L;
        if (h < 1) {
            return "不到 1 小时";
        }
        if (h < 24) {
            return h + " 小时";
        }
        long d = h / 24;
        if (d <= 9) {
            return d + " 天";
        }
        return shortDate(it.deadline);
    }

    private void copyText(String s, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("cxtodo", s));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 绘制工具

    private boolean backdropReady = false;

    private void buildBackdrop() {
        if (aurora == null || root == null) {
            return;
        }
        int w = root.getWidth(), h = root.getHeight();
        android.util.Log.d("CxDebug", "backdrop root=" + w + "x" + h
                + " aurora=" + aurora.getWidth() + "x" + aurora.getHeight()
                + " scroll=" + (scroll == null ? -1 : scroll.getWidth()) + "x"
                + (scroll == null ? -1 : scroll.getHeight())
                + " header=" + (refreshHeader == null ? -1 : refreshHeader.getHeight()));
        if (w <= 0 || h <= 0 || aurora.getWidth() < w || aurora.getHeight() < h) {
            return;
        }
        Bitmap full;
        try {
            full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return;
        }
        Canvas c = new Canvas(full);
        aurora.draw(c);
        int sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);
        Bitmap small = Bitmap.createScaledBitmap(full, sw, sh, true);
        full.recycle();
        int[] pix = new int[sw * sh];
        small.getPixels(pix, 0, sw, 0, 0, sw, sh);
        GlassView.blur(pix, sw, sh, 7);
        small.setPixels(pix, 0, sw, 0, 0, sw, sh);
        int[] loc = new int[2];
        aurora.getLocationInWindow(loc);
        android.util.Log.d("CxDebug", "backdrop bmp=" + small.getWidth() + "x" + small.getHeight()
                + " scale=" + ((float) sw / w) + " origin=" + loc[0] + "," + loc[1]
                + " centerPix=" + Integer.toHexString(pix[sh / 2 * sw + sw / 2])
                + " bottomPix=" + Integer.toHexString(pix[(sh - 3) * sw + sw / 2]));
        GlassView.setBackdrop(small, (float) sw / w, loc[0], loc[1]);
        backdropReady = true;
        // 状态栏/导航栏取极光上下沿同色，背景通到屏幕边
        try {
            getWindow().setStatusBarColor(pix[sw / 2] | 0xFF000000);
            getWindow().setNavigationBarColor(pix[(sh - 1) * sw + sw / 2] | 0xFF000000);
        } catch (Throwable ignored) {
        }
        refreshGlass(root);
    }

    /** root.post 时机不保证布局已完成：布局稳定前每帧兜底重试，成功一次即停。 */
    private void ensureBackdrop() {
        root.post(new Runnable() {
            @Override
            public void run() {
                if (!backdropReady) {
                    buildBackdrop();
                }
            }
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (backdropReady) {
                            root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            return;
                        }
                        buildBackdrop();
                    }
                });
    }

    private void refreshGlass(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                refreshGlass(g.getChildAt(i));
            }
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 紫色调软投影，替代默认灰黑影。 */
    private void softShadow(View v, int elevDp) {
        v.setElevation(dp(elevDp));
        if (Build.VERSION.SDK_INT >= 28) {
            v.setOutlineSpotShadowColor(0x333B2A66);
            v.setOutlineAmbientShadowColor(0x1A3B2A66);
        }
    }

    private TextView text(CharSequence s, float sp, int textColor, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(textColor);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private TextView pill(String label, int textColor, int bgColor) {
        TextView t = text(label, 12, textColor, SANS_MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackground(round(bgColor, 999, 0x59FFFFFF));
        return t;
    }

    private GradientDrawable round(int fill, float radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            d.setStroke(dp(1), strokeColor);
        }
        return d;
    }
}