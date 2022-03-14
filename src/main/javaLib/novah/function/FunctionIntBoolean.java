package novah.function;

public interface FunctionIntBoolean extends Function<Integer, Boolean> {

    @Override
    default Boolean apply(Integer arg) {
        return applyBoolean(arg.intValue());
    }

    @Override
    default Boolean applyI(int arg) {
        return applyBoolean(arg);
    }

    @Override
    default boolean applyBoolean(Integer arg) {
        return applyBoolean(arg.intValue());
    }
}