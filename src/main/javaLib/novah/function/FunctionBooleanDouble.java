package novah.function;

public interface FunctionBooleanDouble extends Function<Boolean, Double> {

    @Override
    default Double apply(Boolean arg) {
        return applyDouble(arg.booleanValue());
    }

    @Override
    default Double applyZ(boolean arg) {
        return applyDouble(arg);
    }

    @Override
    default double applyDouble(Boolean arg) {
        return applyDouble(arg.booleanValue());
    }
}