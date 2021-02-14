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
package novah.data

import java.lang.RuntimeException

sealed class Result<out T, out E> {

    /**
     * Returns the ok value or throws
     * if this result is an error.
     */
    fun unwrap(): T = when (this) {
        is Ok -> value
        is Err -> throw RuntimeException("failed to unwrap error.")
    }
}

class Ok<out T>(val value: T) : Result<T, Nothing>()

class Err<out E>(val err: E) : Result<Nothing, E>()

/**
 * Return this ok value or
 * runs the handler with the error.
 */
inline fun <T, E> Result<T, E>.unwrapOrElse(handler: (E) -> T): T = when (this) {
    is Ok -> value
    is Err -> handler(err)
}

inline fun <T, E, R> Result<T, E>.map(f: (T) -> R): Result<R, E> = when (this) {
    is Ok -> Ok(f(value))
    is Err -> Err(err)
}

/**
 * Maps either the success or the failure.
 */
inline fun <T, E, R> Result<T, E>.mapBoth(success: (T) -> R, failure: (E) -> R): R = when (this) {
    is Ok -> success(value)
    is Err -> failure(err)
}