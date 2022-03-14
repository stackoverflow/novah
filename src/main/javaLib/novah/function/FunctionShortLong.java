package novah.function;

public interface FunctionShortLong extends Function<Short, Long> {

    @Override
    default Long apply(Short arg) {
        return applyLong(arg.shortValue());
    }

    @Override
    default Long applyS(short arg) {
        return applyLong(arg);
    }

    @Override
    default long applyLong(Short arg) {
        return applyLong(arg.shortValue());
    }
}