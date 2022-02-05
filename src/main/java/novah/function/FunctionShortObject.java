package novah.function;

public interface FunctionShortObject<R> extends Function<Short, R> {

    @Override
    default R apply(Short arg) {
        return applyS(arg);
    }
}