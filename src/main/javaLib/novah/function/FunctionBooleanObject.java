package novah.function;

public interface FunctionBooleanObject<R> extends Function<Boolean, R> {

    @Override
    default R apply(Boolean arg) {
        return applyZ(arg);
    }
}