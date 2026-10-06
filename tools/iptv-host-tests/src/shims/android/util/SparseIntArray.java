package android.util;
import java.util.*;
public class SparseIntArray implements Cloneable {
    private TreeMap<Integer,Integer> m = new TreeMap<>();
    public SparseIntArray() {} public SparseIntArray(int c) {}
    public int get(int k) { return get(k, 0); }
    public int get(int k, int d) { Integer v = m.get(k); return v == null ? d : v; }
    public void put(int k, int v) { m.put(k, v); } public void append(int k, int v) { m.put(k, v); }
    public void delete(int k) { m.remove(k); } public void removeAt(int i) { m.remove(keyAt(i)); }
    public int size() { return m.size(); }
    public int keyAt(int i) { return new ArrayList<>(m.keySet()).get(i); }
    public int valueAt(int i) { return new ArrayList<>(m.values()).get(i); }
    public int indexOfKey(int k) { int i = 0; for (int x : m.keySet()) { if (x == k) return i; i++; } return -1; }
    public void clear() { m.clear(); }
    public SparseIntArray clone() { SparseIntArray c = new SparseIntArray(); c.m = new TreeMap<>(m); return c; }
}
