package android.util;
import java.util.Objects;
public class Pair<F, S> {
    public final F first; public final S second;
    public Pair(F first, S second) { this.first = first; this.second = second; }
    public static <A, B> Pair<A, B> create(A a, B b) { return new Pair<>(a, b); }
    @Override public boolean equals(Object o) { return o instanceof Pair && Objects.equals(((Pair<?,?>) o).first, first) && Objects.equals(((Pair<?,?>) o).second, second); }
    @Override public int hashCode() { return Objects.hashCode(first) ^ Objects.hashCode(second); }
}
