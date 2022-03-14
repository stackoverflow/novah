package novah.function;

public interface FunctionCharLong extends Function<Character, Long> {

    @Override
    default Long apply(Character arg) {
        return applyLong(arg.charValue());
    }

    @Override
    default Long applyC(char arg) {
        return applyLong(arg);
    }

    @Override
    default long applyLong(Character arg) {
        return applyLong(arg.charValue());
    }
}