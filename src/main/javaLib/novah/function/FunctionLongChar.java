package novah.function;

public interface FunctionLongChar extends Function<Long, Character> {

    @Override
    default Character apply(Long arg) {
        return applyChar(arg.longValue());
    }

    @Override
    default Character applyJ(long arg) {
        return applyChar(arg);
    }

    @Override
    default char applyChar(Long arg) {
        return applyChar(arg.longValue());
    }
}