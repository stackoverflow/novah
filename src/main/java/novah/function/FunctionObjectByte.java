package novah.function;

public interface FunctionObjectByte<T> extends Function<T, Byte> {

    @Override
    default Byte apply(T arg) {
        return applyByte(arg);
    }
}