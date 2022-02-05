package novah.function;

public interface FunctionCharObject<R> extends Function<Character, R> {

    @Override
    default R apply(Character arg) {
        return applyC(arg);
    }
}