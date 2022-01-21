package novah.range;

import java.util.Iterator;
import java.util.Objects;

public class LongOpenRange implements Range<Long> {
    public final long start;
    public final long end;
    public final long step;

    public LongOpenRange(long start, long end, long step) {
        if (step == 0) throw new Error("Cannot create a range with a 0 step");
        this.start = start;
        this.end = end;
        this.step = step;
    }

    @Override
    public Long start() {
        return start;
    }

    @Override
    public Long end() {
        return end;
    }

    /**
     * Returns true if i is in range
     */
    public boolean contains(int n) {
        if (step > 0) {
            if (step == 1) return n >= start && n < end;
            if (n < start || n >= end) return false;
            return n % step == start;
        }
        if (step == -1) return n > start && n <= end;
        if (n > start || n <= end) return false;
        return n % step == end;
    }

    @Override
    public boolean contains(Long x) {
        return contains(x.intValue());
    }

    /**
     * Returns true if this range is empty
     */
    @Override
    public boolean isEmpty() {
        return step > 0 ? start >= end : start <= end;
    }

    /**
     * Creates a iterator that will go through all numbers in this range
     */
    @Override
    public Iterator<Long> iterator() {
        return new Iterator<>() {
            long current = start;

            @Override
            public boolean hasNext() {
                return step > 0 ? current < end : current > end;
            }

            @Override
            public Long next() {
                long tmp = current;
                current = current + step;
                return tmp;
            }
        };
    }

    @Override
    public String toString() {
        if (Math.abs(step) == 1) {
            String format = step > 0 ? "(%s ... %s)" : "(%s ..< %s)";
            return String.format(format, start, end);
        }
        return String.format("(LongOpenRange %s %s %s)", start, end, step);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LongOpenRange longs = (LongOpenRange) o;
        return start == longs.start && end == longs.end && step == longs.step;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, step);
    }
}
