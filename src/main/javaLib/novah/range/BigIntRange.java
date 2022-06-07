package novah.range;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.Objects;

public class BigIntRange implements Range<BigInteger> {
    public final BigInteger start;
    public final BigInteger end;
    public final BigInteger step;

    public BigIntRange(BigInteger start, BigInteger end, BigInteger step) {
        if (step.equals(BigInteger.ZERO)) throw new Error("Cannot create a range with a 0 step");
        this.start = start;
        this.end = end;
        this.step = step;
    }

    public BigIntRange(BigInteger start, BigInteger end) {
        BigInteger st = smallerOrEquals(start, end) ? BigInteger.ONE : BigInteger.valueOf(-1);
        this.start = start;
        this.end = end;
        step = st;
    }

    @Override
    public BigInteger start() {
        return start;
    }

    @Override
    public BigInteger end() {
        return end;
    }

    /**
     * Returns true if i is in range
     */
    @Override
    public boolean contains(BigInteger n) {
        // var first = step > 0 ? start : end;
        // var last = step > 0 ? end : start;
        var first = greater(step, BigInteger.ZERO) ? start : end;
        var last = greater(step, BigInteger.ZERO) ? end : start;

        // if (Math.abs(step) == 1) return n >= first && n <= last;
        if (step.abs().equals(BigInteger.ONE)) return greaterOrEquals(n, first) && smallerOrEquals(n, last);
        //if (n < first || n > last) return false;
        if (smaller(n, first) || greater(n, last)) return false;
        //return n % step == first;
        return n.mod(step).equals(first);
    }

    /**
     * Returns true if this range is empty
     */
    @Override
    public boolean isEmpty() {
        //return step > 0 ? start > end : start < end;
        return greater(step, BigInteger.ZERO) ? greater(start, end) : smaller(start, end);
    }

    /**
     * Creates an iterator that will go through all numbers in this range
     */
    @Override
    public Iterator<BigInteger> iterator() {
        return new Iterator<>() {
            BigInteger current = start;

            @Override
            public boolean hasNext() {
                //return step > 0 ? current <= end : current >= end;
                return greater(step, BigInteger.ZERO) ? smallerOrEquals(current, end) : greaterOrEquals(current, end);
            }

            @Override
            public BigInteger next() {
                BigInteger tmp = current;
                current = current.add(step);
                return tmp;
            }
        };
    }

    @Override
    public String toString() {
        if (step.abs().equals(BigInteger.ONE)) {
            String format = greater(step, BigInteger.ZERO) ? "(%s .. %s)" : "(%s .< %s)";
            return String.format(format, start, end);
        }
        return String.format("(BigIntRange %s %s %s)", start, end, step);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BigIntRange integers = (BigIntRange) o;
        return start.equals(integers.start) && end.equals(integers.end) && step.equals(integers.step);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, step);
    }

    private static boolean smaller(BigInteger x, BigInteger y) {
        return x.compareTo(y) == -1;
    }

    private static boolean smallerOrEquals(BigInteger x, BigInteger y) {
        return x.compareTo(y) != 1;
    }

    private static boolean greater(BigInteger x, BigInteger y) {
        return x.compareTo(y) == 1;
    }

    private static boolean greaterOrEquals(BigInteger x, BigInteger y) {
        return x.compareTo(y) != -1;
    }
}
