package novah.function;

public interface FunctionByteObject<R> extends Function<Byte, R> {

    @Override
    default R apply(Byte arg) {
        return applyB(arg);
    }
}