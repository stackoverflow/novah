package novah.function;

public interface FunctionFloatBoolean extends Function<Float, Boolean> {

    @Override
    default Boolean apply(Float arg) {
        return applyBoolean(arg.floatValue());
    }

    @Override
    default Boolean applyF(float arg) {
        return applyBoolean(arg);
    }

    @Override
    default boolean applyBoolean(Float arg) {
        return applyBoolean(arg.floatValue());
    }
}