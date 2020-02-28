package novah.frontend

sealed class Either<out L, out R> {
    data class Left<L>(val v: L) : Either<L, Nothing>()
    data class Right<R>(val v: R) : Either<Nothing, R>()
}