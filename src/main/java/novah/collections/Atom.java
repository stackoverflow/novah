/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
