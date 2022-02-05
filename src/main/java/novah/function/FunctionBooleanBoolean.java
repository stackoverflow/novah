package novah.function;

public interface FunctionBooleanBoolean extends Function<Boolean, Boolean> {

    @Override
    default Boolean apply(Boolean arg) {
        return applyBoolean(arg.booleanValue());
    }

    @Override
    default Boolean applyZ(boolean arg) {
        return applyBoolean(arg);
    }

    @Override
    default boolean applyBoolean(Boolean arg) {
        return applyBoolean(arg.booleanValue());
    }
}