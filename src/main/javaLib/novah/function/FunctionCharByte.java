package novah.function;

public interface FunctionCharByte extends Function<Character, Byte> {

    @Override
    default Byte apply(Character arg) {
        return applyByte(arg.charValue());
    }

    @Override
    default Byte applyC(char arg) {
        return applyByte(arg);
    }

    @Override
    default byte applyByte(Character arg) {
        return applyByte(arg.charValue());
    }
}