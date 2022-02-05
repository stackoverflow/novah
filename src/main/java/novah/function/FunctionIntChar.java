package novah.function;

public interface FunctionIntChar extends Function<Integer, Character> {

    @Override
    default Character apply(Integer arg) {
        return applyChar(arg.intValue());
    }

    @Override
    default Character applyI(int arg) {
        return applyChar(arg);
    }

    @Override
    default char applyChar(Integer arg) {
        return applyChar(arg.intValue());
    }
}