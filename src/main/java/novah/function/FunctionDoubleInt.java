package novah.function;

public interface FunctionDoubleInt extends Function<Double, Integer> {

    @Override
    default Integer apply(Double arg) {
        return applyInt(arg.doubleValue());
    }

    @Override
    default Integer applyD(double arg) {
        return applyInt(arg);
    }

    @Override
    default int applyInt(Double arg) {
        return applyInt(arg.doubleValue());
    }
}