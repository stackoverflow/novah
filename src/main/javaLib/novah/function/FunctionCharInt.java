package novah.function;

public interface FunctionCharInt extends Function<Character, Integer> {

    @Override
    default Integer apply(Character arg) {
        return applyInt(arg.charValue());
    }

    @Override
    default Integer applyC(char arg) {
        return applyInt(arg);
    }

    @Override
    default int applyInt(Character arg) {
        return applyInt(arg.charValue());
    }
}