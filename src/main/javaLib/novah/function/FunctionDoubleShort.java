package novah.function;

public interface FunctionDoubleShort extends Function<Double, Short> {

    @Override
    default Short apply(Double arg) {
        return applyShort(arg.doubleValue());
    }

    @Override
    default Short applyD(double arg) {
        return applyShort(arg);
    }

    @Override
    default short applyShort(Double arg) {
        return applyShort(arg.doubleValue());
    }
}