package novah;

public class Int64Ref {
    public long val;

    public Int64Ref(long value) {
        val = value;
    }

    public long plus(long i) {
        val += i;
        return val;
    }

    public long minus(long i) {
        val -= i;
        return val;
    }

    public long mult(long i) {
        val *= i;
        return val;
    }

    public long divide(long i) {
        val /= i;
        return val;
    }
}
