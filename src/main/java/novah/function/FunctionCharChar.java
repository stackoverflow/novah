package novah.function;

public interface FunctionCharChar extends Function<Character, Character> {

    @Override
    default Character apply(Character arg) {
        return applyChar(arg.charValue());
    }

    @Override
    default Character applyC(char arg) {
        return applyChar(arg);
    }

    @Override
    default char applyChar(Character arg) {
        return applyChar(arg.charValue());
    }
}