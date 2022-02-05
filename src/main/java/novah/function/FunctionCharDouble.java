package novah.function;

public interface FunctionCharDouble extends Function<Character, Double> {

    @Override
    default Double apply(Character arg) {
        return applyDouble(arg.charValue());
    }

    @Override
    default Double applyC(char arg) {
        return applyDouble(arg);
    }

    @Override
    default double applyDouble(Character arg) {
        return applyDouble(arg.charValue());
    }
}