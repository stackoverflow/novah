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

import io.lacuna.bifurcan.*;

import java.util.OptionalLong;
import java.util.function.*;

/**
 * The type of all novah records.
 * Allows duplicate keys.
 * Just a thin wrapper over bifurcan's Map.
 */
public class Record implements IMap<String, Record.ListValue> {

    private final IMap<String, Record.ListValue> inner;

    private Record(IMap<String, Record.ListValue> map) {
        inner = map;
    }

    public Record() {
        inner = Map.empty();
    }

    public static Record empty() {
        return new Record();
    }

    @Override
    public String toString() {
        return inner.toString();
    }

    @Override
    public ToLongFunction<String> keyHash() {
        return String::hashCode;
    }

    @Override
    public BiPredicate<String, String> keyEquality() {
        // TODO: use == because all Strings should have been interned
        return String::equals;
    }

    @Override
    public Record.ListValue get(String key, Record.ListValue defaultValue) {
        return inner.get(key, defaultValue);
    }

    /**
     * Because the typechecker makes sure one
     * can never access a non-existing key we
     * can (un)safely return the value without
     * checking the key is present.
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public Object unsafeGet(String key) {
        var index = inner.indexOf(key).getAsLong();
        return inner.nth(index).value().value;
    }

    @Override
    public IList<IEntry<String, Record.ListValue>> entries() {
        return inner.entries();
    }

    @Override
    public OptionalLong indexOf(String key) {
        return inner.indexOf(key);
    }

    @Override
    public ISet<String> keys() {
        return inner.keys();
    }

    @Override
    public IList<Record.ListValue> values() {
        return inner.values();
    }

    @Override
    public <U> IMap<String, U> mapValues(BiFunction<String, Record.ListValue, U> f) {
        return (IMap<String, U>) new Record(inner.mapValues((BiFunction<String, Record.ListValue, Record.ListValue>) f));
    }

//    @Override
//    public void forEach(Consumer<? super IEntry<String, Object>> action) {
//
//    }

    public IMap<String, Record.ListValue> merge(IMap<String, Record.ListValue> b, Function<Record.ListValue, Function<Record.ListValue, Record.ListValue>> mergeFn) {
        return merge(b, (e1, e2) -> mergeFn.apply(e1).apply(e2));
    }

    @Override
    public IMap<String, Record.ListValue> merge(IMap<String, Record.ListValue> b, BinaryOperator<Record.ListValue> mergeFn) {
        return new Record(inner.merge(b, mergeFn));
    }

    @Override
    public IMap<String, Record.ListValue> difference(ISet<String> keys) {
        return new Record(inner.difference(keys));
    }

    @Override
    public IMap<String, Record.ListValue> intersection(ISet<String> keys) {
        return new Record(inner.intersection(keys));
    }

    @Override
    public IMap<String, Record.ListValue> union(IMap<String, Record.ListValue> m) {
        return new Record(inner.union(m));
    }

    @Override
    public IMap<String, Record.ListValue> difference(IMap<String, ?> m) {
        return new Record(inner.difference(m));
    }

    @Override
    public IMap<String, Record.ListValue> intersection(IMap<String, ?> m) {
        return new Record(inner.intersection(m));
    }

    @Override
    public IMap<String, Record.ListValue> put(String key, Record.ListValue value, BinaryOperator<Record.ListValue> merge) {
        return new Record(inner.put(key, value, merge));
    }

    @Override
    public IMap<String, Record.ListValue> update(String key, UnaryOperator<Record.ListValue> update) {
        return new Record(inner.update(key, update));
    }

    /**
     * Updates the value represented by this key with
     * the supplied function.
     * We can ignore the case where the key is not present
     * as the typechecker disallows it.
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public <T> Record update(String key, Function<T, T> update) {
        var idx = inner.indexOf(key).getAsLong();
        var list = inner.nth(idx).value();
        var newVal = ListValue.of(update.apply((T) list.value), list.next);
        return new Record(inner.put(key.intern(), newVal));
    }

    @Override
    public IMap<String, Record.ListValue> put(String key, Record.ListValue value) {
        return new Record(inner.put(key, value));
    }

    /**
     * Adds a key to the map.
     * Accept duplicates.
     */
    public Record assoc(String key, Object value) {
        var idx = inner.indexOf(key);
        if (idx.isPresent()) {
            // duplicated key, add the value as the head of the list
            var list = inner.nth(idx.getAsLong()).value();
            return new Record(inner.put(key.intern(), ListValue.of(value, list)));
        }
        // key is not duplicated
        return new Record(inner.put(key.intern(), ListValue.of(value)));
    }

    @Override
    public IMap<String, Record.ListValue> remove(String key) {
        return new Record(inner.remove(key));
    }

    /**
     * Removes a key from the map.
     * If the key has more than one value
     * only remove the most recent one.
     * We can ignore the case where the key is not
     * present because the typechecker makes sure
     * this can't happen.
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public Record dissoc(String key) {
        var idx = inner.indexOf(key).getAsLong();
        var list = inner.nth(idx).value();
        if (list.next != null) {
            // key is duplicated, just drop the head of the list
            return new Record(inner.put(key.intern(), list.next));
        }
        return new Record(inner.remove(key));
    }

    @Override
    public IMap<String, Record.ListValue> forked() {
        return isLinear() ? new Record(inner.forked()) : this;
    }

    public Record _forked() {
        return isLinear() ? new Record(inner.forked()) : this;
    }

    @Override
    public IMap<String, Record.ListValue> linear() {
        return isLinear() ? this : new Record(inner.linear());
    }

    public Record _linear() {
        return isLinear() ? this : new Record(inner.linear());
    }

    @Override
    public boolean isLinear() {
        return inner.isLinear();
    }

    @Override
    public IList<? extends IMap<String, Record.ListValue>> split(int parts) {
        return inner.split(parts);
    }
//
//    @Override
//    public DurableMap<String, Object> save(IDurableEncoding encoding, Path directory) {
//        return null;
//    }

    @Override
    public long size() {
        return inner.size();
    }

    @Override
    public IEntry<String, Record.ListValue> nth(long idx) {
        return inner.nth(idx);
    }

    @Override
    public IEntry<String, Record.ListValue> nth(long idx, IEntry<String, Record.ListValue> defaultValue) {
        return inner.nth(idx, defaultValue);
    }

    @Override
    public IMap<String, Record.ListValue> clone() {
        return new Record(inner.clone());
    }

    /**
     * Because novah allows duplicate keys we need
     * some way to store many values in a key.
     * This is a bare-minimum immutable linked list
     * that allows us to store multiple values without
     * too much performance impact.
     */
    static class ListValue {
        final Object value;
        final ListValue next;

        public ListValue(Object val, ListValue next) {
            value = val;
            this.next = next;
        }

        public static ListValue of(Object val) {
            return new ListValue(val, null);
        }

        public static ListValue of(Object val, ListValue next) {
            return new ListValue(val, next);
        }

        @Override
        public String toString() {
            if (next == null) return value.toString();
            return value.toString() + ", " + next.toString();
        }
    }
}
