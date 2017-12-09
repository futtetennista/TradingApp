package com.futtetennista.trading

@Suppress("unused")
sealed class Either<out A, out B>
data class Right<B>(val value: B) : Either<Nothing, B>()
data class Left<A>(val value: A) : Either<A, Nothing>()
