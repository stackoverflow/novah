package novah.range;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Objects;

public class BigDecimalRange implements Range<BigDecimal> {
    public final BigDecimal start;
    public final BigDecimal end;
    public final boolean up;

    public BigDecimalRange(BigDecimal start, BigDecimal end, boolean up) {
        this.start = start;
        this.end = end;
        this.up = up;
    }

    public BigDecimalRange(BigDecimal start, BigDecimal end) {
        this.start = start;
        this.end = end;
        //this.up = start <= end;
        this.up = smallerOrEquals(start, end);
    }

    @Override
    public BigDecimal start() {
        return start;
    }

    @Override
    public BigDecimal end() {
        return end;
    }

    /**
     * Returns true if i is in range
     */
    @Override
    public boolean contains(BigDecimal n) {
        // if (up) return n >= start && n <= end;
        if (up) return greaterOrEquals(n, start) && smallerOrEquals(n, end);
        // return n <= start && n >= end;
        return smallerOrEquals(n, start) && greaterOrEquals(n, end);
    }

    /**
     * Returns true if this range is empty
     */
    @Override
    public boolean isEmpty() {
        //return up ? start > end : start < end;
        return up ? greater(start, end) : smaller(start, end);
    }

    /**
     * Creates an iterator that will go through all numbers in this range
     */
    @Override
    public Iterator<BigDecimal> iterator() {
        return new Iterator<>() {
            BigDecimal current = start;

            @Override
            public boolean hasNext() {
                // return up ? current <= end : current >= end;
                return up ? smallerOrEquals(current, end) : greaterOrEquals(current, end);
            }

            @Override
            public BigDecimal next() {
                BigDecimal tmp = current;
                if (up) {
                    current = current.add(BigDecimal.ONE);
                } else {
                    current = current.subtract(BigDecimal.ONE);
                }
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
        BigDecimalRange doubles = (BigDecimalRange) o;
        return start.equals(doubles.start) && end.equals(doubles.end) && up == doubles.up;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, up);
    }

    private static boolean smaller(BigDecimal x, BigDecimal y) {
        return x.compareTo(y) == -1;
    }

    private static boolean smallerOrEquals(BigDecimal x, BigDecimal y) {
        return x.compareTo(y) != 1;
    }

    private static boolean greater(BigDecimal x, BigDecimal y) {
        return x.compareTo(y) == 1;
    }

    private static boolean greaterOrEquals(BigDecimal x, BigDecimal y) {
        return x.compareTo(y) != -1;
    }
}
