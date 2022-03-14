package novah.range;

import java.util.Iterator;
import java.util.Objects;

public class DoubleRange implements Range<Double> {
    public final double start;
    public final double end;
    public final boolean up;

    public DoubleRange(double start, double end, boolean up) {
        this.start = start;
        this.end = end;
        this.up = up;
    }

    public DoubleRange(double start, double end) {
        this.start = start;
        this.end = end;
        this.up = start <= end;
    }

    @Override
    public Double start() {
        return start;
    }

    @Override
    public Double end() {
        return end;
    }

    /**
     * Returns true if i is in range
     */
    public boolean contains(double n) {
        if (up) return n >= start && n <= end;
        return n <= start && n >= end;
    }

    @Override
    public boolean contains(Double x) {
        return contains(x.doubleValue());
    }

    /**
     * Returns true if this range is empty
     */
    @Override
    public boolean isEmpty() {
        return up ? start > end : start < end;
    }

    /**
     * Creates a iterator that will go through all numbers in this range
     */
    @Override
    public Iterator<Double> iterator() {
        return new Iterator<>() {
            double current = start;

            @Override
            public boolean hasNext() {
                return up ? current <= end : current >= end;
            }

            @Override
            public Double next() {
                double tmp = current;
                if (up) current++; else current--;
                return tmp;
            }
        };
    }

    @Override
    public String toString() {
        String format = up ? "(%s .. %s)" : "(%s .< %s)";
        return String.format(format, start, end);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DoubleRange doubles = (DoubleRange) o;
        return Double.compare(doubles.start, start) == 0 && Double.compare(doubles.end, end) == 0 && up == doubles.up;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, up);
    }
}
