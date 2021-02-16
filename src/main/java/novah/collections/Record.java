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
 * Just a thin wrapper over bifurcan's Map.
 * It would be better to not have this wrapper
 * and just use Map directly but it shouldn't
 * impact performace.
 */
public class Record implements IMap<String, Object> {

    private final IMap<String, Object> inner;

    private Record(IMap<String, Object> map) {
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
    public Object get(String key, Object defaultValue) {
        return inner.get(key, defaultValue);
    }

    //TODO: change to Vector
    @Override
    public IList<IEntry<String, Object>> entries() {
        return inner.entries();
    }

    @Override
    public OptionalLong indexOf(String key) {
        return inner.indexOf(key);
    }

    //TODO: change to novah Set
    @Override
    public ISet<String> keys() {
        return inner.keys();
    }

    //TODO: change to Vector
    @Override
    public IList<Object> values() {
        return inner.values();
    }

    @SuppressWarnings("Unchecked")
    @Override
    public <U> IMap<String, U> mapValues(BiFunction<String, Object, U> f) {
        return (IMap<String, U>) new Record(inner.mapValues((BiFunction<String, Object, Object>) f));
    }

//    @Override
//    public void forEach(Consumer<? super IEntry<String, Object>> action) {
//
//    }

    public IMap<String, Object> merge(IMap<String, Object> b, Function<Object, Function<Object, Object>> mergeFn) {
        return merge(b, (e1, e2) -> mergeFn.apply(e1).apply(e2));
    }

    @Override
    public IMap<String, Object> merge(IMap<String, Object> b, BinaryOperator<Object> mergeFn) {
        return new Record(inner.merge(b, mergeFn));
    }

    @Override
    public IMap<String, Object> difference(ISet<String> keys) {
        return new Record(inner.difference(keys));
    }

    @Override
    public IMap<String, Object> intersection(ISet<String> keys) {
        return new Record(inner.intersection(keys));
    }

    @Override
    public IMap<String, Object> union(IMap<String, Object> m) {
        return new Record(inner.union(m));
    }

    @Override
    public IMap<String, Object> difference(IMap<String, ?> m) {
        return new Record(inner.difference(m));
    }

    @Override
    public IMap<String, Object> intersection(IMap<String, ?> m) {
        return new Record(inner.intersection(m));
    }

    @Override
    public IMap<String, Object> put(String key, Object value, BinaryOperator<Object> merge) {
        return new Record(inner.put(key, value, merge));
    }

    @Override
    public IMap<String, Object> update(String key, UnaryOperator<Object> update) {
        return new Record(inner.update(key, update));
    }

    @Override
    public IMap<String, Object> put(String key, Object value) {
        return new Record(inner.put(key, value));
    }

    public Record assoc(String key, Object value) {
        return new Record(inner.put(key.intern(), value));
    }

    @Override
    public IMap<String, Object> remove(String key) {
        return new Record(inner.remove(key));
    }

    public Record dissoc(String key) {
        return new Record(inner.remove(key));
    }

    @Override
    public IMap<String, Object> forked() {
        return isLinear() ? new Record(inner.forked()) : this;
    }

    public Record persistent() {
        return isLinear() ? new Record(inner.forked()) : this;
    }

    @Override
    public IMap<String, Object> linear() {
        return isLinear() ? this : new Record(inner.linear());
    }

    public Record toTransient() {
        return isLinear() ? this : new Record(inner.linear());
    }

    @Override
    public boolean isLinear() {
        return inner.isLinear();
    }

    // TODO: return Vector
    @Override
    public IList<? extends IMap<String, Object>> split(int parts) {
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
    public IEntry<String, Object> nth(long idx) {
        return inner.nth(idx);
    }

    @Override
    public IEntry<String, Object> nth(long idx, IEntry<String, Object> defaultValue) {
        return inner.nth(idx, defaultValue);
    }

    @Override
    public IMap<String, Object> clone() {
        return new Record(inner.clone());
    }
}
