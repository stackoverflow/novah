package novah.function;

public interface FunctionDoubleDouble extends Function<Double, Double> {

    @Override
    default Double apply(Double arg) {
        return applyDouble(arg.doubleValue());
    }

    @Override
    default Double applyD(double arg) {
        return applyDouble(arg);
    }

    @Override
    default double applyDouble(Double arg) {
        return applyDouble(arg.doubleValue());
    }
}