package novah;

public final class Unit {

    public static final Unit INSTANCE = new Unit();
    
    private Unit() {
    }

    @Override
    public String toString() {
        return "novah.Unit";
    }
}