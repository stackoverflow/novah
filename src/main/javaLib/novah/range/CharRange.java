package novah.range;

import java.util.Iterator;
import java.util.Objects;

public class CharRange implements Range<Character> {
    public final int start;
    public final int end;
    public final int step;

    public CharRange(char start, char end, int step) {
        if (step == 0) throw new Error("Cannot create a range with a 0 step");
        this.start = start;
        this.end = end;
        this.step = step;
    }

    public CharRange(char start, char end) {
        int st = start <= end ? 1 : -1;
        this.start = start;
        this.end = end;
        step = st;
    }

    @Override
    public Character start() {
        return (char) start;
    }

    @Override
    public Character end() {
        return (char) end;
    }

    /**
     * Returns true if i is in range
     */
    public boolean contains(char n) {
        var first = step > 0 ? start : end;
        var last = step > 0 ? end : start;

        if (Math.abs(step) == 1) return n >= first && n <= last;
        if (n < first || n > last) return false;
        return n % step == first;
    }

    @Override
    public boolean contains(Character x) {
        return contains(x.charValue());
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
    public Iterator<Character> iterator() {
        return new Iterator<>() {
            int current = start;

            @Override
            public boolean hasNext() {
                return step > 0 ? current < end : current > end;
            }

            @Override
            public Character next() {
                char tmp = (char) current;
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
        return String.format("(CharRange %s %s %s)", start, end, step);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CharRange that = (CharRange) o;
        return start == that.start && end == that.end && step == that.step;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, step);
    }
}
