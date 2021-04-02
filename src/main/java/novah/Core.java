/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah;

import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Set;

import java.util.function.Function;

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

    public static float div(float x, float y) {
        return x / y;
    }

    public static double div(double x, double y) {
        return x / y;
    }
    
    public static <T> void println(T arg) {
        System.out.println(arg);
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
    
    public static boolean equivalentObject(Object o1, Object o2) {
        return o1 == o2;
    }

    public static byte[] mkByteArray(int size) {
        return new byte[size];
    }

    public static short[] mkInt16Array(int size) {
        return new short[size];
    }

    public static int[] mkInt32Array(int size) {
        return new int[size];
    }

    public static long[] mkInt64Array(int size) {
        return new long[size];
    }

    public static float[] mkFloat32Array(int size) {
        return new float[size];
    }

    public static double[] mkFloat64Array(int size) {
        return new double[size];
    }

    public static char[] mkCharArray(int size) {
        return new char[size];
    }

    public static boolean[] mkBooleanArray(int size) {
        return new boolean[size];
    }
    
    public static Object[] mkObjectArray(int size) {
        return new Object[size];
    }
    
    public static Object getArray(int index, Object[] arr) {
        return arr[index];
    }
    
    public static void setArray(int index, Object val, Object[] arr) {
        arr[index] = val;
    }
    
    public static int getArrayLength(Object[] arr) {
        return arr.length;
    }
    
    public static boolean not(boolean b) {
        return !b;
    }
    
    public static boolean vectorNotEmpty(List<?> vec) {
        return vec.size() != 0;
    }
    
    public static List<Object> mapVector(Function<Object, Object> f, List<Object> vec) {
        var res = List.empty().linear();
        vec.stream().forEach((elem) -> res.addLast(f.apply(elem)));
        return res.forked();
    }
    
    public static Object[] mapArray(Function<Object, Object> f, Object[] arr) {
        var res = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) {
            res[i] = f.apply(arr[i]);
        }
        return res;
    }
    
    public static <T> boolean equalsVector(List<T> v1, List<T> v2, Function<T, Function<T, Boolean>> comp) {
        if (v1.size() != v2.size()) return false;
        
        long total = v1.size();
        for (long i = 0; i < total; i++) {
            if (!comp.apply(v1.nth(i)).apply(v2.nth(i))) return false;
        }
        return true;
    }

    public static <T> boolean equalsSet(Set<T> v1, Set<T> v2, Function<T, Function<T, Boolean>> comp) {
        if (v1.size() != v2.size()) return false;

        long total = v1.size();
        for (long i = 0; i < total; i++) {
            if (!comp.apply(v1.nth(i)).apply(v2.nth(i))) return false;
        }
        return true;
    }
    
    @SuppressWarnings("unchecked")
    public static <T, T2> T unsafeCoerce(T2 o) {
        return (T) o;
    }
}
