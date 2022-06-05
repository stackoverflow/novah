package novah;

public class Float32Ref {
    public float val;

    public Float32Ref(float value) {
        val = value;
    }

    public float plus(float f) {
        val += f;
        return val;
    }

    public float minus(float f) {
        val -= f;
        return val;
    }

    public float mult(float f) {
        val *= f;
        return val;
    }

    public float divide(float f) {
        val /= f;
        return val;
    }
}
