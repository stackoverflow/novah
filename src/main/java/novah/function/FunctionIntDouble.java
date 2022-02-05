package novah.function;

public interface FunctionIntDouble extends Function<Integer, Double> {

    @Override
    default Double apply(Integer arg) {
        return applyDouble(arg.intValue());
    }

    @Override
    default Double applyI(int arg) {
        return applyDouble(arg);
    }

    @Override
    default double applyDouble(Integer arg) {
        return applyDouble(arg.intValue());
    }
}