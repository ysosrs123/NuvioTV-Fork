package android.util;
import java.util.*;
public class SparseLongArray implements Cloneable {
    private TreeMap<Integer,Long> m = new TreeMap<>();
    public SparseLongArray() {} public SparseLongArray(int c) {}
    public long get(int k) { return get(k, 0L); }
    public long get(int k, long d) { Long v = m.get(k); return v == null ? d : v; }
    public void put(int k, long v) { m.put(k, v); } public void append(int k, long v) { m.put(k, v); }
    public void delete(int k) { m.remove(k); } public void removeAt(int i) { m.remove(keyAt(i)); }
    public int size() { return m.size(); }
    public int keyAt(int i) { return new ArrayList<>(m.keySet()).get(i); }
    public long valueAt(int i) { return new ArrayList<>(m.values()).get(i); }
    public int indexOfKey(int k) { int i = 0; for (int x : m.keySet()) { if (x == k) return i; i++; } return -1; }
    public void clear() { m.clear(); }
    public SparseLongArray clone() { SparseLongArray c = new SparseLongArray(); c.m = new TreeMap<>(m); return c; }
}
