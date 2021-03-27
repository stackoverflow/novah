package novah;

import io.lacuna.bifurcan.List;

/**
 * Core language functions
 */
public class Core {
    
    public static int sum(int x, int y) {
        return x + y;
    }

    public static long sum(long x, long y) {
        return x + y;
    }

    public static byte sum(byte x, byte y) {
        return (byte) (x + y);
    }

    public static short sum(short x, short y) {
        return (short) (x + y);
    }

    public static float sum(float x, float y) {
        return x + y;
    }

    public static double sum(double x, double y) {
        return x + y;
    }

    public static int sub(int x, int y) {
        return x - y;
    }

    public static long sub(long x, long y) {
        return x - y;
    }

    public static byte sub(byte x, byte y) {
        return (byte) (x - y);
    }

    public static short sub(short x, short y) {
        return (short) (x - y);
    }

    public static float sub(float x, float y) {
        return x - y;
    }

    public static double sub(double x, double y) {
        return x - y;
    }

    public static int mult(int x, int y) {
        return x * y;
    }

    public static long mult(long x, long y) {
        return x * y;
    }

    public static byte mult(byte x, byte y) {
        return (byte) (x * y);
    }

    public static short mult(short x, short y) {
        return (short) (x * y);
    }

    public static float mult(float x, float y) {
        return x * y;
    }

    public static double mult(double x, double y) {
        return x * y;
    }

    public static int div(int x, int y) {
        return x / y;
    }

    public static long div(long x, long y) {
        return x / y;
    }

    public static byte div(byte x, byte y) {
        return (byte) (x / y);
    }

    public static short div(short x, short y) {
        return (short) (x / y);
    }

    public static float div(float x, float y) {
        return x / y;
    }

    public static double div(double x, double y) {
        return x / y;
    }
    
    public static <T> void println(T arg) {
        System.out.println(arg);
    }
    
    public static boolean equivalent(String e1, String e2) {
        return e1.equals(e2);
    }

    public static boolean equivalent(byte e1, byte e2) {
        return e1 == e2;
    }

    public static boolean equivalent(short e1, short e2) {
        return e1 == e2;
    }

    public static boolean equivalent(int e1, int e2) {
        return e1 == e2;
    }

    public static boolean equivalent(long e1, long e2) {
        return e1 == e2;
    }

    public static boolean equivalent(float e1, float e2) {
        return e1 == e2;
    }

    public static boolean equivalent(double e1, double e2) {
        return e1 == e2;
    }

    public static boolean equivalent(char e1, char e2) {
        return e1 == e2;
    }

    public static boolean equivalent(boolean e1, boolean e2) {
        return e1 == e2;
    }
    
    public static boolean not(boolean b) {
        return !b;
    }
    
    public static boolean vectorNotEmpty(List<?> vec) {
        return vec.size() != 0;
    }
}
