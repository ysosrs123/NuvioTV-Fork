package android.util;
import java.util.*;
public class SparseBooleanArray implements Cloneable {
    private TreeMap<Integer,Boolean> m = new TreeMap<>();
    public SparseBooleanArray() {} public SparseBooleanArray(int c) {}
    public boolean get(int k) { return get(k, false); }
    public boolean get(int k, boolean d) { Boolean v = m.get(k); return v == null ? d : v; }
    public void put(int k, boolean v) { m.put(k, v); } public void append(int k, boolean v) { m.put(k, v); }
    public void delete(int k) { m.remove(k); } public void removeAt(int i) { m.remove(keyAt(i)); }
    public int size() { return m.size(); }
    public int keyAt(int i) { return new ArrayList<>(m.keySet()).get(i); }
    public boolean valueAt(int i) { return new ArrayList<>(m.values()).get(i); }
    public int indexOfKey(int k) { int i = 0; for (int x : m.keySet()) { if (x == k) return i; i++; } return -1; }
    public void clear() { m.clear(); }
    public SparseBooleanArray clone() { SparseBooleanArray c = new SparseBooleanArray(); c.m = new TreeMap<>(m); return c; }
}
