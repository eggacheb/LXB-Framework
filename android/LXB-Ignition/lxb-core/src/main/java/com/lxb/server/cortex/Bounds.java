package com.lxb.server.cortex;

import java.util.List;
import java.util.ArrayList;

public class Bounds {
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;

    public Bounds(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int centerX() {
        return (left + right) / 2;
    }

    public int centerY() {
        return (top + bottom) / 2;
    }

    public String toJsonArrayString() {
        return "[" + left + "," + top + "," + right + "," + bottom + "]";
    }

    public List<Object> toList() {
        List<Object> a = new ArrayList<>(4);
        a.add(left);
        a.add(top);
        a.add(right);
        a.add(bottom);
        return a;
    }

    public static Bounds fromList(List<Object> arr) {
        if (arr == null || arr.size() < 4) return null;
        return new Bounds(toInt(arr.get(0)), toInt(arr.get(1)), toInt(arr.get(2)), toInt(arr.get(3)));
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
