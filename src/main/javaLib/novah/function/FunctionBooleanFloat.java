package novah.function;

public interface FunctionBooleanFloat extends Function<Boolean, Float> {

    @Override
    default Float apply(Boolean arg) {
        return applyFloat(arg.booleanValue());
    }

    @Override
    default Float applyZ(boolean arg) {
        return applyFloat(arg);
    }

    @Override
    default float applyFloat(Boolean arg) {
        return applyFloat(arg.booleanValue());
    }
}