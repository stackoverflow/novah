package novah.range;

import java.util.Iterator;
import java.util.Objects;

public class FloatOpenRange implements Range<Float> {
    public final float start;
    public final float end;
    public final boolean up;

    public FloatOpenRange(float start, float end, boolean up) {
        this.start = start;
        this.end = end;
        this.up = up;
    }

    @Override
    public Float start() {
        return start;
    }

    @Override
    public Float end() {
        return end;
    }

    /**
     * Returns true if i is in range
     */
    public boolean contains(float n) {
        if (up) return n >= start && n < end;
        return n <= start && n > end;
    }

    @Override
    public boolean contains(Float x) {
        return contains(x.floatValue());
    }

    /**
     * Returns true if this range is empty
     */
    @Override
    public boolean isEmpty() {
        return up ? start >= end : start <= end;
    }

    /**
     * Creates a iterator that will go through all numbers in this range
     */
    @Override
    public Iterator<Float> iterator() {
        return new Iterator<>() {
            float current = start;

            @Override
            public boolean hasNext() {
                return up ? current < end : current > end;
            }

            @Override
            public Float next() {
                float tmp = current;
                if (up) current++; else current--;
                return tmp;
            }
        };
    }

    @Override
    public String toString() {
        String format = up ? "(%s ... %s)" : "(%s ..< %s)";
        return String.format(format, start, end);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FloatOpenRange floats = (FloatOpenRange) o;
        return Float.compare(floats.start, start) == 0 && Float.compare(floats.end, end) == 0 && up == floats.up;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, up);
    }
}
