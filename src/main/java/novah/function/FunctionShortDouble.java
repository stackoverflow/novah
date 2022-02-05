package novah.function;

public interface FunctionShortDouble extends Function<Short, Double> {

    @Override
    default Double apply(Short arg) {
        return applyDouble(arg.shortValue());
    }

    @Override
    default Double applyS(short arg) {
        return applyDouble(arg);
    }

    @Override
    default double applyDouble(Short arg) {
        return applyDouble(arg.shortValue());
    }
}