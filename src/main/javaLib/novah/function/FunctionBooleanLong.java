package novah.function;

public interface FunctionBooleanLong extends Function<Boolean, Long> {

    @Override
    default Long apply(Boolean arg) {
        return applyLong(arg.booleanValue());
    }

    @Override
    default Long applyZ(boolean arg) {
        return applyLong(arg);
    }

    @Override
    default long applyLong(Boolean arg) {
        return applyLong(arg.booleanValue());
    }
}