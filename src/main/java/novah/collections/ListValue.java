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
        return value.toString() + ", " + next.toString();
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
