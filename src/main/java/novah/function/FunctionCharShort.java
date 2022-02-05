package novah.function;

public interface FunctionCharShort extends Function<Character, Short> {

    @Override
    default Short apply(Character arg) {
        return applyShort(arg.charValue());
    }

    @Override
    default Short applyC(char arg) {
        return applyShort(arg);
    }

    @Override
    default short applyShort(Character arg) {
        return applyShort(arg.charValue());
    }
}