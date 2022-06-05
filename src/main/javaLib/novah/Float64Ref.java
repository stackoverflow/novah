package novah;

public class Float64Ref {
    public double val;

    public Float64Ref(double value) {
        val = value;
    }

    public double plus(double f) {
        val += f;
        return val;
    }

    public double minus(double f) {
        val -= f;
        return val;
    }

    public double mult(double f) {
        val *= f;
        return val;
    }

    public double divide(double f) {
        val /= f;
        return val;
    }
}
