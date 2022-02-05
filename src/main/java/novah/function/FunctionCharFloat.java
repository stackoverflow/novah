package novah.function;

public interface FunctionCharFloat extends Function<Character, Float> {

    @Override
    default Float apply(Character arg) {
        return applyFloat(arg.charValue());
    }

    @Override
    default Float applyC(char arg) {
        return applyFloat(arg);
    }

    @Override
    default float applyFloat(Character arg) {
        return applyFloat(arg.charValue());
    }
}