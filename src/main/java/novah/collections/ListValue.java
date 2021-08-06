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

import java.util.Objects;

/**
 * Because novah allows duplicate keys we need
 * some way to store many values in a key.
 * This is a bare-minimum immutable linked list
 * that allows us to store multiple values without
 * too much performance impact.
 */
public class ListValue {
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
        return value.toString() + ", " + next;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ListValue listValue = (ListValue) o;
        return Objects.equals(value, listValue.value) && Objects.equals(next, listValue.next);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, next);
    }
}
