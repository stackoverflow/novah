/**
 * Copyright 2022 Islon Scherer
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

/**
 * Represents a function in Novah.
 */
@FunctionalInterface
public interface Function<T, R> extends java.util.function.Function<T, R> {

    default R applyI(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default R applyB(byte arg) {
        throw new RuntimeException("Not implemented");
    }

    default R applyS(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default R applyJ(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default R applyF(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default R applyD(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default R applyC(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default R applyZ(boolean arg) {
        throw new RuntimeException("Not implemented");
    }

    default int applyInt(T arg) {
        throw new RuntimeException("Not implemented");
    }

    default int applyInt(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default int applyInt(byte arg) {
        throw new RuntimeException("Not implemented");
    }
    
    default int applyInt(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default int applyInt(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default int applyInt(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default int applyInt(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default int applyInt(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default int applyInt(boolean arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(T arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(byte arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default byte applyByte(boolean arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(T arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(byte arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default short applyShort(boolean arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(T arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(byte arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default long applyLong(boolean arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(T arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(byte arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default float applyFloat(boolean arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(T arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(byte arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default double applyDouble(boolean arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(T arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(byte arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default char applyChar(boolean arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(T arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(int arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(byte arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(short arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(long arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(float arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(double arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(char arg) {
        throw new RuntimeException("Not implemented");
    }

    default boolean applyBoolean(boolean arg) {
        throw new RuntimeException("Not implemented");
    }
}
