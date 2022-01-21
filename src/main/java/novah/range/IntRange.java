package novah.range;

import java.util.Iterator;
import java.util.Objects;

public class IntRange implements Range<Integer> {
    public final int start;
    public final int end;
    public final int step;

    public IntRange(int start, int end, int step) {
        if (step == 0) throw new Error("Cannot create a range with a 0 step");
        this.start = start;
        this.end = end;
        this.step = step;
    }

    @Override
    public Integer start() {
        return start;
    }

    @Override
    public Integer end() {
        return end;
    }

    /**
     * Returns true if i is in range
     */
    public boolean contains(int n) {
        var first = step > 0 ? start : end;
        var last = step > 0 ? end : start;

        if (Math.abs(step) == 1) return n >= first && n <= last;
        if (n < first || n > last) return false;
        return n % step == first;
    }

    @Override
    public boolean contains(Integer x) {
        return contains(x.intValue());
    }

    /**
     * Returns true if this range is empty
     */
    @Override
    public boolean isEmpty() {
        return step > 0 ? start > end : start < end;
    }

    /**
     * Creates a iterator that will go through all numbers in this range
     */
    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            int current = start;

            @Override
            public boolean hasNext() {
                return step > 0 ? current <= end : current >= end;
            }

            @Override
            public Integer next() {
                int tmp = current;
                current = current + step;
                return tmp;
            }
        };
    }

    @Override
    public String toString() {
        if (Math.abs(step) == 1) {
            String format = step > 0 ? "(%s .. %s)" : "(%s .< %s)";
            return String.format(format, start, end);
        }
        return String.format("(IntRange %s %s %s)", start, end, step);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IntRange integers = (IntRange) o;
        return start == integers.start && end == integers.end && step == integers.step;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, step);
    }
}
