package android.util;
import java.util.*;
public class SparseArray<E> implements Cloneable {
    private TreeMap<Integer,E> m = new TreeMap<>();
    public SparseArray() {} public SparseArray(int c) {}
    public E get(int k) { return m.get(k); }
    public E get(int k, E d) { E v = m.get(k); return v == null && !m.containsKey(k) ? d : v; }
    public void put(int k, E v) { m.put(k, v); } public void append(int k, E v) { m.put(k, v); }
    public void delete(int k) { m.remove(k); } public void remove(int k) { m.remove(k); }
    public void removeAt(int i) { m.remove(keyAt(i)); }
    public int size() { return m.size(); }
    public int keyAt(int i) { return new ArrayList<>(m.keySet()).get(i); }
    public E valueAt(int i) { return new ArrayList<>(m.values()).get(i); }
    public void setValueAt(int i, E v) { m.put(keyAt(i), v); }
    public int indexOfKey(int k) { int i = 0; for (int x : m.keySet()) { if (x == k) return i; i++; } return -1; }
    public int indexOfValue(E v) { int i = 0; for (E x : m.values()) { if (x == v) return i; i++; } return -1; }
    public boolean contains(int k) { return m.containsKey(k); }
    public void clear() { m.clear(); }
    @SuppressWarnings("unchecked") public SparseArray<E> clone() { SparseArray<E> c = new SparseArray<>(); c.m = new TreeMap<>(m); return c; }
}
