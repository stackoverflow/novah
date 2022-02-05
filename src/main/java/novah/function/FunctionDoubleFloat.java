package novah.function;

public interface FunctionDoubleFloat extends Function<Double, Float> {

    @Override
    default Float apply(Double arg) {
        return applyFloat(arg.doubleValue());
    }

    @Override
    default Float applyD(double arg) {
        return applyFloat(arg);
    }

    @Override
    default float applyFloat(Double arg) {
        return applyFloat(arg.doubleValue());
    }
}