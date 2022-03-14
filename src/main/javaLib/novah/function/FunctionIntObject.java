package novah.function;

public interface FunctionIntObject<R> extends Function<Integer, R> {

    @Override
    default R apply(Integer arg) {
        return applyI(arg);
    }
}