package novah.function;

public interface FunctionShortFloat extends Function<Short, Float> {

    @Override
    default Float apply(Short arg) {
        return applyFloat(arg.shortValue());
    }

    @Override
    default Float applyS(short arg) {
        return applyFloat(arg);
    }

    @Override
    default float applyFloat(Short arg) {
        return applyFloat(arg.shortValue());
    }
}