package com.dsh.cxtodo;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学年学期：startYear 是学年起始年（2026 表示 2026-2027 学年），half 1=秋 2=春。
 * 口径：8 月—次年 1 月为秋季，2—7 月为春季。
 */
public final class Term {

    public final int startYear;
    public final int half;

    public Term(int startYear, int half) {
        this.startYear = startYear;
        this.half = half;
    }

    public static Term now() {
        return ofTime(System.currentTimeMillis());
    }

    public static Term ofTime(long t) {
        if (t <= 0) {
            return null;
        }
        Calendar c = Calendar.getInstance();
        if (t > 100000000000L) {
            c.setTimeInMillis(t);
        } else {
            // 允许传秒
            c.setTimeInMillis(t * 1000L);
        }
        int y = c.get(Calendar.YEAR);
        int m = c.get(Calendar.MONTH) + 1;
        if (m == 1) {
            return new Term(y - 1, 1);
        }
        if (m >= 8) {
            return new Term(y, 1);
        }
        return new Term(y - 1, 2);
    }

    /** "2026-11-28 08:50" 或 "2026-11-28" → 毫秒；只有日期时补 23:59。解析不了返回 0。 */
    public static long parse(String s) {
        if (s == null) {
            return 0;
        }
        String v = s.trim();
        if (v.length() < 10) {
            return 0;
        }
        try {
            int y = Integer.parseInt(v.substring(0, 4));
            int mo = Integer.parseInt(v.substring(5, 7));
            int d = Integer.parseInt(v.substring(8, 10));
            int hh = 23;
            int mm = 59;
            if (v.length() >= 16) {
                hh = Integer.parseInt(v.substring(11, 13));
                mm = Integer.parseInt(v.substring(14, 16));
            }
            Calendar c = Calendar.getInstance();
            c.set(y, mo - 1, d, hh, mm, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final Pattern P_FULL = Pattern.compile(
            "(20\\d{2})\\s*[-–—]\\s*(20\\d{2})\\s*[-–—_]?\\s*0?([12])(?!\\d)");
    private static final Pattern P_CN = Pattern.compile(
            "(20\\d{2})\\s*[-–—]\\s*(20\\d{2})\\s*[（(]?\\s*第?\\s*([一二12])\\s*[)）]?");
    private static final Pattern P_SHORT = Pattern.compile(
            "(?<!\\d)(\\d{2})\\s*[-–—]\\s*(\\d{2})\\s*[-–—_]\\s*([12])(?!\\d)");
    private static final Pattern P_SEASON = Pattern.compile("(20\\d{2})\\s*年?\\s*(秋|春)");
    private static final Pattern P_YEAR = Pattern.compile("(?<!\\d)(20[12]\\d)(?!\\d)");

    /**
     * 从课程名里读学期：2026-2027-01 / 25-26-2 / 2025-2026（二）/ 2025秋季 / 单独年份。
     * 读不出来返回 null。
     */
    public static Term ofName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Matcher m = P_FULL.matcher(name);
        if (m.find()) {
            return new Term(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)));
        }
        m = P_CN.matcher(name);
        if (m.find()) {
            String h = m.group(3);
            int half = ("一".equals(h) || "1".equals(h)) ? 1 : 2;
            return new Term(Integer.parseInt(m.group(1)), half);
        }
        m = P_SHORT.matcher(name);
        if (m.find()) {
            return new Term(2000 + Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)));
        }
        m = P_SEASON.matcher(name);
        if (m.find()) {
            return new Term(Integer.parseInt(m.group(1)), "秋".equals(m.group(2)) ? 1 : 2);
        }
        m = P_YEAR.matcher(name);
        if (m.find()) {
            // 只说年份时按该年秋季记
            return new Term(Integer.parseInt(m.group(1)), 1);
        }
        return null;
    }

    public boolean same(Term o) {
        return o != null && o.startYear == startYear && o.half == half;
    }

    /** "26-27-1" 反解析回 Term。 */
    public static Term ofShort(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d{2})-(\\d{2})-([12])").matcher(s);
        if (m.find()) {
            return new Term(2000 + Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)));
        }
        return null;
    }

    public String label() {
        return startYear + "-" + (startYear + 1) + (half == 1 ? " 秋" : " 春");
    }

    public String shortLabel() {
        return two(startYear % 100) + "-" + two((startYear + 1) % 100) + "-" + half;
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}