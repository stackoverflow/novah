package novah.function;

public interface FunctionDoubleLong extends Function<Double, Long> {

    @Override
    default Long apply(Double arg) {
        return applyLong(arg.doubleValue());
    }

    @Override
    default Long applyD(double arg) {
        return applyLong(arg);
    }

    @Override
    default long applyLong(Double arg) {
        return applyLong(arg.doubleValue());
    }
}