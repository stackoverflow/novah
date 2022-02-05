package novah.function;

public interface FunctionIntFloat extends Function<Integer, Float> {

    @Override
    default Float apply(Integer arg) {
        return applyFloat(arg.intValue());
    }

    @Override
    default Float applyI(int arg) {
        return applyFloat(arg);
    }

    @Override
    default float applyFloat(Integer arg) {
        return applyFloat(arg.intValue());
    }
}