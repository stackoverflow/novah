package novah.collections;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * An atomic reference that can be safely mutated
 * without locks.
 * Based on Clojure's atom.
 */
public class Atom<V> {
    private final AtomicReference<V> state;

    public Atom(V value) {
        state = new AtomicReference<>(value);
    }

    /**
     * Get the value this atom holds.
     */
    public V deref() {
        return state.get();
    }

    /**
     * Sets this atom to a new value.
     */
    public V reset(V newValue) {
        state.set(newValue);
        return newValue;
    }

    /**
     * Change the old value of this atom by applying
     * a function to it.
     */
    public V swap(Function<V, V> f) {
        for (;;) {
            var v = deref();
            var newv = f.apply(v);
            if (state.compareAndSet(v, newv)) {
                return newv;
            }
        }
    }
}
