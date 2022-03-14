package novah.function;

public interface FunctionDoubleChar extends Function<Double, Character> {

    @Override
    default Character apply(Double arg) {
        return applyChar(arg.doubleValue());
    }

    @Override
    default Character applyD(double arg) {
        return applyChar(arg);
    }

    @Override
    default char applyChar(Double arg) {
        return applyChar(arg.doubleValue());
    }
}