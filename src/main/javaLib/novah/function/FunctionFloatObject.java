package novah.function;

public interface FunctionFloatObject<R> extends Function<Float, R> {

    @Override
    default R apply(Float arg) {
        return applyF(arg);
    }
}