package novah.function;

public interface FunctionShortBoolean extends Function<Short, Boolean> {

    @Override
    default Boolean apply(Short arg) {
        return applyBoolean(arg.shortValue());
    }

    @Override
    default Boolean applyS(short arg) {
        return applyBoolean(arg);
    }

    @Override
    default boolean applyBoolean(Short arg) {
        return applyBoolean(arg.shortValue());
    }
}