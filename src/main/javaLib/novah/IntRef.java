package novah;

public class IntRef {
    public int val;

    public IntRef(int value) {
        val = value;
    }

    public int plus(int i) {
        val += i;
        return val;
    }

    public int minus(int i) {
        val -= i;
        return val;
    }

    public int mult(int i) {
        val *= i;
        return val;
    }

    public int divide(int i) {
        val /= i;
        return val;
    }
}
