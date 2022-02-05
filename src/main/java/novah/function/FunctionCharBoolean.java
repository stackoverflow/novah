package novah.function;

public interface FunctionCharBoolean extends Function<Character, Boolean> {

    @Override
    default Boolean apply(Character arg) {
        return applyBoolean(arg.charValue());
    }

    @Override
    default Boolean applyC(char arg) {
        return applyBoolean(arg);
    }

    @Override
    default boolean applyBoolean(Character arg) {
        return applyBoolean(arg.charValue());
    }
}