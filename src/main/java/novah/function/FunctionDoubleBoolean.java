package novah.function;

public interface FunctionDoubleBoolean extends Function<Double, Boolean> {

    @Override
    default Boolean apply(Double arg) {
        return applyBoolean(arg.doubleValue());
    }

    @Override
    default Boolean applyD(double arg) {
        return applyBoolean(arg);
    }

    @Override
    default boolean applyBoolean(Double arg) {
        return applyBoolean(arg.doubleValue());
    }
}