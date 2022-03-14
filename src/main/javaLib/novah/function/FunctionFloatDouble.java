package novah.function;

public interface FunctionFloatDouble extends Function<Float, Double> {

    @Override
    default Double apply(Float arg) {
        return applyDouble(arg.floatValue());
    }

    @Override
    default Double applyF(float arg) {
        return applyDouble(arg);
    }

    @Override
    default double applyDouble(Float arg) {
        return applyDouble(arg.floatValue());
    }
}