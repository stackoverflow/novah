package novah.range;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Objects;

public class BigDecimalOpenRange implements Range<BigDecimal> {
    public final BigDecimal start;
    public final BigDecimal end;
    public final boolean up;

    public BigDecimalOpenRange(BigDecimal start, BigDecimal end, boolean up) {
        this.start = start;
        this.end = end;
        this.up = up;
    }

    public BigDecimalOpenRange(BigDecimal start, BigDecimal end) {
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
        //return up ? end - 1 : end + 1;
        return up ? end.subtract(BigDecimal.ONE) : end.add(BigDecimal.ONE);
    }

    /**
     * Returns true if i is in range
     */
    @Override
    public boolean contains(BigDecimal n) {
        // if (up) return n >= start && n < end;
        if (up) return greaterOrEquals(n, start) && smaller(n, end);
        // return n <= start && n > end;
        return smallerOrEquals(n, start) && greater(n, end);
    }

    /**
     * Returns true if this range is empty
     */
    @Override
    public boolean isEmpty() {
        // return up ? start >= end : start <= end;
        return up ? greaterOrEquals(start, end) : smallerOrEquals(start, end);
    }

    /**
     * Creates a iterator that will go through all numbers in this range
     */
    @Override
    public Iterator<BigDecimal> iterator() {
        return new Iterator<>() {
            BigDecimal current = start;

            @Override
            public boolean hasNext() {
                // return up ? current < end : current > end;
                return up ? smaller(current, end) : greater(current, end);
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
        BigDecimalOpenRange doubles = (BigDecimalOpenRange) o;
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
