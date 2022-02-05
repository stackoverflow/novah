package novah.function;

public interface FunctionDoubleObject<R> extends Function<Double, R> {

    @Override
    default R apply(Double arg) {
        return applyD(arg);
    }
}