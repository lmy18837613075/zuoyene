package com.dsh.cxtodo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学习通作业 / 考试抓取。
 *
 * 抓取链路完全走网页 HTML（学习通的 JSON 作业接口需要页面生成的 enc 签名，
 * 直接拼参数拿不到东西）：
 *
 *   GET /visit/courses                                  → 课程四元组 courseId/classId/cpi/name
 *   GET mooc1-api/mycourse/backclazzdata                → 每门课 beginDate（判学期）
 *   GET /visit/stucoursemiddle?courseid=&clazzid=&cpi=  → 页面里带签名的 /work/getAllWork?...
 *   GET /mooc-ans{该路径}&start=0&size=50                → 作业列表 HTML
 *   解析 <div class="titTxt"> 块                         → 标题 / 状态 / 起止时间 / enc
 *
 * 链路与 cx-pilot（HEDBS/cx-pilot, core/works.py）一致，属实测可用路径。
 *
 * 课程学期判定（扫描前跳过往期课，不发作业请求）：
 *   1. backclazzdata 的 beginDate（最准，覆盖不全）
 *   2. 上次扫描学到的学期（作业时间多数派回填，存调用方）
 *   3. 课程名里的学期标记
 *   4. 都没有 → 照扫，扫完把作业时间多数派记下来供下次用
 */
public class CxClient {

    public static final class Item {
        public String title = "";
        public String course = "";
        public String deadline;
        public String start;
        public String kind = "work";
        /** finished=已完成/已批阅；submitted=已交待批阅；todo=待做/未交/已过期；unknown=没读到状态。 */
        public String state = "unknown";
        public String url = "";
        public String statusText = "";
    }

    public static final class ScanResult {
        public final List<Item> items = new ArrayList<Item>();
        /** 全部课程数。 */
        public int courseCount;
        /** 实际发请求扫描的课程数。 */
        public int scannedCount;
        public int skippedCourses;
        public String error;
        public final StringBuilder debug = new StringBuilder();
        /** 本次从作业时间学到的课程学期：classId → "26-27-1"。 */
        public final Map<String, String> learnedTerms = new HashMap<String, String>();
    }

    public interface Progress {
        /** done 从 1 起，total 是本次要扫的课程数。 */
        void step(int done, int total, String course);
    }

    private static final String MOOC = "https://mooc1.chaoxing.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern COURSE_A = Pattern.compile(
            "<a[^>]*class=\"courseName\"[^>]*href='([^']*)'[^>]*title=\"([^\"]*)\"");
    private static final Pattern COURSE_B = Pattern.compile(
            "<a[^>]*class=\"courseName\"[^>]*href=\"([^\"]*)\"[^>]*title=\"([^\"]*)\"");
    private static final Pattern PARAM_ID = Pattern.compile("courseid=(\\d+)");
    private static final Pattern PARAM_CLS = Pattern.compile("clazzid=(\\d+)");
    private static final Pattern PARAM_CPI = Pattern.compile("cpi=(\\d+)");
    private static final Pattern WORK_LINK = Pattern.compile("(/work/getAllWork\\?[^'\"\\s]+)");
    private static final Pattern INSPECT = Pattern.compile(
            "class=\"inspectTask\"[^>]*?data=\"(\\d+)\"[^>]*?data2=\"(\\d+)\"[^>]*?data3=\"(\\d+)\""
                    + "[^>]*?title=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern YIPIYUE = Pattern.compile(
            "selectWorkQuestionYiPiYue\\?[^\"]*workId=(\\d+)&workAnswerId=(\\d+)[^\"]*\"[^>]*title=\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("<strong>\\s*([^<]+?)\\s*</strong>");
    private static final Pattern END = Pattern.compile("截止时间：</span>([^<]+)");
    private static final Pattern START = Pattern.compile("开始时间：</span>([^<]+)");
    private static final Pattern ENC = Pattern.compile("enc=([0-9a-fA-F]{32})");
    private static final Pattern BC_KEY = Pattern.compile("\"key\":(\\d+)");
    private static final Pattern BC_BEGIN = Pattern.compile("\"beginDate\":\"([^\"]+)\"");
    private static final Pattern EXAM_LINK = Pattern.compile(
            "href=\"([^\"]*exam[^\"]*)\"[^>]*>([^<]{2,60})<");

    // ------------------------------------------------------------ 入口

    /**
     * @param learnedIn 上次扫描学到的课程学期（classId → "26-27-1"），会被本次结果覆盖更新。
     * @param cancel    单元素数组，置 true 中途取消。
     */
    public static ScanResult scan(String cookie, Progress p, boolean[] cancel,
                                  Map<String, String> learnedIn) {
        ScanResult r = new ScanResult();
        String coursesHtml = get(MOOC + "/visit/courses", cookie, MOOC + "/", r);
        if (coursesHtml == null) {
            r.error = "课程列表请求失败（可能登录已失效）";
            return r;
        }
        List<String[]> courses = parseCourses(coursesHtml);
        r.debug.append("courses=").append(courses.size())
                .append(" html=").append(coursesHtml.length()).append('\n');
        if (courses.isEmpty()) {
            r.error = "没解析到课程，登录可能已失效";
            return r;
        }
        r.courseCount = courses.size();

        Map<String, String> beginDates = fetchBeginDates(cookie, r);
        Term cur = Term.now();
        List<String[]> toScan = new ArrayList<String[]>();
        int skipped = 0;
        for (int i = 0; i < courses.size(); i++) {
            String[] c = courses.get(i);
            if (courseTerm(c, beginDates, learnedIn) == null) {
                toScan.add(c);
                continue;
            }
            Term t = courseTerm(c, beginDates, learnedIn);
            if (t.same(cur)) {
                toScan.add(c);
            } else {
                skipped++;
                r.debug.append("skip 往期 ").append(c[3]).append(' ').append(t.shortLabel()).append('\n');
            }
        }
        r.skippedCourses = skipped;
        r.scannedCount = toScan.size();
        r.debug.append("skip-past=").append(skipped).append(" scan=").append(toScan.size()).append('\n');

        List<Item> all = new ArrayList<Item>();
        for (int i = 0; i < toScan.size(); i++) {
            if (cancel != null && cancel[0]) {
                r.error = "已取消";
                break;
            }
            String[] c = toScan.get(i);
            if (p != null) {
                p.step(i + 1, toScan.size(), c[3]);
            }
            List<Item> got = new ArrayList<Item>();
            try {
                got = scanCourse(c, cookie, r);
            } catch (Throwable t) {
                r.debug.append("ERR ").append(c[3]).append(' ')
                        .append(t.getClass().getSimpleName()).append('\n');
            }
            all.addAll(got);
            // 这门课没有学期信号：用作业时间多数派学一次，下次直接跳过
            if (courseTerm(c, beginDates, learnedIn) == null && !got.isEmpty()) {
                Term voted = majorityTerm(got);
                if (voted != null) {
                    r.learnedTerms.put(c[1], voted.shortLabel());
                    r.debug.append("learn ").append(c[3]).append(' ').append(voted.shortLabel())
                            .append('\n');
                }
            }
            try {
                Thread.sleep(260);
            } catch (InterruptedException ignored) {
            }
        }
        r.items.addAll(all);
        if (r.error == null && r.items.isEmpty()) {
            r.error = "本学期的课程里没有作业或考试";
        }
        return r;
    }

    /** 课程学期：beginDate → 上次学到的 → 课程名。都没有返回 null。 */
    private static Term courseTerm(String[] c, Map<String, String> beginDates,
                                   Map<String, String> learnedIn) {
        String begin = beginDates.get(c[1]);
        if (begin != null && begin.length() >= 10) {
            Term t = Term.ofTime(Term.parse(begin));
            if (t != null) {
                return t;
            }
        }
        if (learnedIn != null && learnedIn.containsKey(c[1])) {
            Term t = Term.ofShort(learnedIn.get(c[1]));
            if (t != null) {
                return t;
            }
        }
        return Term.ofName(c[3]);
    }

    /** 作业时间的多数派学期（截止优先，开始兜底）。 */
    private static Term majorityTerm(List<Item> items) {
        Map<String, Integer> votes = new HashMap<String, Integer>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            Term t = Term.ofTime(Term.parse(it.deadline));
            if (t == null) {
                t = Term.ofTime(Term.parse(it.start));
            }
            if (t != null) {
                String k = t.shortLabel();
                votes.put(k, (votes.containsKey(k) ? votes.get(k) : 0) + 1);
            }
        }
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        if (bestN < 1) {
            return null;
        }
        return Term.ofShort(best);
    }

    /**
     * backclazzdata 里每门课带 beginDate，用来判学期。
     * 取不到不影响扫描，只是退化成按课程名判断。
     */
    private static Map<String, String> fetchBeginDates(String cookie, ScanResult r) {
        Map<String, String> out = new HashMap<String, String>();
        try {
            String json = get("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1",
                    cookie, MOOC + "/", r);
            if (json == null) {
                return out;
            }
            String[] blocks = json.split("\\{\"cfid\":");
            for (int i = 0; i < blocks.length; i++) {
                Matcher k = BC_KEY.matcher(blocks[i]);
                Matcher b = BC_BEGIN.matcher(blocks[i]);
                if (k.find() && b.find()) {
                    out.put(k.group(1), b.group(1));
                }
            }
            r.debug.append("bc=").append(out.size()).append('\n');
        } catch (Throwable t) {
            r.debug.append("BC ").append(t.getClass().getSimpleName()).append('\n');
        }
        return out;
    }

    /** 课程四元组：courseId, classId, cpi, name。 */
    private static List<String[]> parseCourses(String html) {
        List<String[]> out = new ArrayList<String[]>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        collectCourses(html, COURSE_A, out, seen);
        collectCourses(html, COURSE_B, out, seen);
        return out;
    }

    private static void collectCourses(String html, Pattern p, List<String[]> out,
                                       LinkedHashSet<String> seen) {
        Matcher m = p.matcher(html);
        while (m.find()) {
            String href = m.group(1).replace("&amp;", "&");
            String name = clean(m.group(2));
            String cid = pick(PARAM_ID, href);
            String cls = pick(PARAM_CLS, href);
            String cpi = pick(PARAM_CPI, href);
            if (cid == null || cls == null) {
                continue;
            }
            if (!seen.add(cid)) {
                continue;
            }
            out.add(new String[]{cid, cls, cpi == null ? "0" : cpi, name});
        }
    }

    private static List<Item> scanCourse(String[] c, String cookie, ScanResult r) {
        List<Item> out = new ArrayList<Item>();
        String cid = c[0], cls = c[1], cpi = c[2], name = c[3];
        String portalUrl = MOOC + "/visit/stucoursemiddle?courseid=" + cid
                + "&clazzid=" + cls + "&vc=1&cpi=" + cpi;
        String portal = get(portalUrl, cookie, MOOC + "/visit/courses", r);
        if (portal == null) {
            return out;
        }
        Matcher lm = WORK_LINK.matcher(portal);
        if (lm.find()) {
            String path = lm.group(1).replace("&amp;", "&");
            String listUrl = MOOC + "/mooc-ans" + path + "&start=0&size=50";
            String listHtml = get(listUrl, cookie, portalUrl, r);
            if (listHtml != null) {
                out.addAll(parseWorks(listHtml, c, r));
            }
        } else {
            r.debug.append("no-worklink ").append(name).append('\n');
        }
        out.addAll(parseExams(portal, c));
        return out;
    }

    private static List<Item> parseWorks(String html, String[] c, ScanResult r) {
        List<Item> out = new ArrayList<Item>();
        String[] blocks = html.split("<div class=\"titTxt\"");
        int found = 0;
        for (int i = 1; i < blocks.length; i++) {
            String blk = blocks[i];
            String wid = null, waid = null, title = null;
            Matcher a = INSPECT.matcher(blk);
            if (a.find()) {
                wid = a.group(1);
                waid = a.group(2);
                title = a.group(4);
            } else {
                Matcher b = YIPIYUE.matcher(blk);
                if (b.find()) {
                    wid = b.group(1);
                    waid = b.group(2);
                    title = b.group(3);
                }
            }
            if (wid == null || title == null) {
                continue;
            }
            title = clean(title);
            if (title.contains("分组任务") || title.contains("PBL")) {
                continue;
            }
            Item it = new Item();
            it.kind = "work";
            it.title = title;
            it.course = c[3];
            Matcher st = STATUS.matcher(blk);
            it.statusText = st.find() ? clean(st.group(1)) : "";
            Matcher en = END.matcher(blk);
            if (en.find()) {
                it.deadline = en.group(1).trim();
            }
            Matcher sa = START.matcher(blk);
            if (sa.find()) {
                it.start = sa.group(1).trim();
            }
            Matcher ea = ENC.matcher(blk);
            String enc = ea.find() ? ea.group(1) : "";
            it.url = MOOC + "/mooc-ans/work/doHomeWorkNew?courseId=" + c[0]
                    + "&classId=" + c[1] + "&workId=" + wid
                    + (enc.isEmpty() ? "" : "&enc=" + enc);
            it.state = classify(it.statusText);
            out.add(it);
            found++;
        }
        r.debug.append("works ").append(clean(c[3])).append('=').append(found).append('\n');
        return out;
    }

    /**
     * 考试条目：只收门户页里指向 exam 页面、且文字像考试名的链接。
     * 其余链接（test 工具页之类）一律不要，避免凑出一堆没有截止时间的垃圾条目。
     */
    private static List<Item> parseExams(String portal, String[] c) {
        List<Item> out = new ArrayList<Item>();
        Matcher m = EXAM_LINK.matcher(portal);
        int n = 0;
        while (m.find() && n < 5) {
            String href = m.group(1).replace("&amp;", "&");
            String text = clean(m.group(2));
            if (href.contains("/work/") || text.length() < 3) {
                continue;
            }
            if (!text.contains("考试") && !text.contains("测验") && !text.contains("期中")
                    && !text.contains("期末") && !text.contains("测试")) {
                continue;
            }
            Item it = new Item();
            it.kind = "exam";
            it.title = text;
            it.course = c[3];
            it.state = "unknown";
            it.url = href.startsWith("http") ? href : MOOC + href;
            out.add(it);
            n++;
        }
        return out;
    }

    // ------------------------------------------------------------ 状态

    /** 作业状态文案 → finished / submitted / todo / unknown。 */
    static String classify(String status) {
        String s = status == null ? "" : status;
        if (s.contains("待批阅") || s.contains("已提交") || s.contains("已上交")) {
            return "submitted";
        }
        if (s.contains("已批阅") || s.contains("已完成")) {
            return "finished";
        }
        if (s.contains("待做") || s.contains("未交") || s.contains("未完成")
                || s.contains("已过期") || s.contains("未开始")) {
            return "todo";
        }
        return s.isEmpty() ? "unknown" : "todo";
    }

    // ------------------------------------------------------------ 网络

    private static String get(String url, String cookie, String referer, ScanResult r) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(18000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) {
                if (r != null) {
                    r.debug.append("HTTP ").append(code).append(' ').append(tail(url)).append('\n');
                }
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String body = new String(bos.toByteArray(), "UTF-8");
            if (r != null && body.contains("请重新登录")) {
                r.debug.append("NEED-LOGIN ").append(tail(url)).append('\n');
            }
            return body;
        } catch (Throwable t) {
            if (r != null) {
                r.debug.append("EX ").append(t.getClass().getSimpleName()).append(' ')
                        .append(tail(url)).append('\n');
            }
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String tail(String url) {
        return url.length() > 90 ? url.substring(url.length() - 90) : url;
    }

    private static String pick(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------ 演示数据

    public static ScanResult demo() {
        ScanResult r = new ScanResult();
        r.courseCount = 8;
        r.scannedCount = 8;
        r.items.add(item("第三章 新时代坚持和发展中国特色社会主义", "形势与政策", "2026-11-17 23:59", "work", "todo", "待做"));
        r.items.add(item("期中测验", "数据结构", "2026-11-19 22:00", "exam", "todo", "未开始"));
        r.items.add(item("Unit 4 写作任务", "大学英语（三）", "2026-11-20 12:00", "work", "todo", "待做"));
        r.items.add(item("实验二 线性表", "数据结构", "2026-11-16 10:00", "work", "submitted", "已完成待批阅"));
        r.items.add(item("第四章 课后习题", "线性代数", null, "work", "unknown", ""));
        r.items.add(item("单元小测", "概率论", "2026-11-10 18:00", "exam", "finished", "已批阅"));
        r.items.add(item("第二章作业", "高等数学", "2026-11-08 23:59", "work", "finished", "已完成"));
        r.items.add(item("作业20260511", "写作与沟通", "2026-05-11 12:47", "work", "todo", "待做"));
        return r;
    }

    private static Item item(String title, String course, String deadline, String kind,
                             String state, String status) {
        Item it = new Item();
        it.title = title;
        it.course = course;
        it.deadline = deadline;
        it.kind = kind;
        it.state = state;
        it.statusText = status;
        it.url = "https://mooc1.chaoxing.com/";
        return it;
    }
}