/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.common

/**
 * Typed error envelope. Lets call-sites replace broad `try { } catch (e: Exception) { }` blocks
 * and `!!` force-unwraps with explicit, exhaustive `when` branches over a sealed type.
 *
 * Use [Outcome.Success] for the happy path and [Outcome.Failure] for the typed error. The failure
 * type [E] must be a sealed/interface hierarchy so consumers can `when`-exhaust.
 *
 * Prefer this over [kotlin.Result] when the error is not a [Throwable] (e.g. domain enums like
 * `I/O`, `NotFound`, `Unauthorized`). For paths that only throw [Throwable], use
 * [Throwable.toOutcome] to wrap a stdlib `runCatching` block.
 */
sealed interface Outcome<out E, out T> {
  data class Success<T>(val value: T) : Outcome<Nothing, T>

  data class Failure<E>(val error: E) : Outcome<E, Nothing>

  companion object {
    /**
     * Run [block] and adapt the result into [Outcome], converting any [Throwable] into [Failure]
     * via [mapError]. Use this to replace a `runCatching { ... }.onFailure { ... }` chain with a
     * typed envelope.
     */
    inline fun <E, T> catching(mapError: (Throwable) -> E, block: () -> T): Outcome<E, T> =
      runCatching(block).fold(
        onSuccess = { Success(it) },
        onFailure = { Failure(mapError(it)) },
      )
  }
}

/** Returns the success value or null. */
fun <E, T> Outcome<E, T>.getOrNull(): T? =
  when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> null
  }

/** Returns the success value or the result of [onFailure]. */
inline fun <E, T> Outcome<E, T>.getOrElse(onFailure: (E) -> T): T =
  when (this) {
    is Outcome.Success<T> -> value
    is Outcome.Failure<E> -> onFailure(error)
  }

/** Map success value. */
inline fun <E, T, R> Outcome<E, T>.map(transform: (T) -> R): Outcome<E, R> = when (this) {
  is Outcome.Success -> Outcome.Success(transform(value))
  is Outcome.Failure -> this
}

/** Map failure value. */
inline fun <E, T, F> Outcome<E, T>.mapError(transform: (E) -> F): Outcome<F, T> = when (this) {
  is Outcome.Success -> this
  is Outcome.Failure -> Outcome.Failure(transform(error))
}

/** Bind on success, short-circuit on failure. */
inline fun <E, T, R> Outcome<E, T>.flatMap(transform: (T) -> Outcome<E, R>): Outcome<E, R> =
  when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
  }

/** Adapt any [Throwable] into an [Outcome.Failure] carrying the throwable. */
fun <T> Throwable.toOutcome(): Outcome<Throwable, T> = Outcome.Failure(this)

/**
 * Wrap a stdlib [kotlin.Result] (Throwable-typed) into an [Outcome] with the same error type. Use
 * when the original call site is `runCatching { ... }` and you want a uniform envelope.
 */
fun <T> kotlin.Result<T>.toOutcome(): Outcome<Throwable, T> =
  fold(
    onSuccess = { Outcome.Success(it) },
    onFailure = { Outcome.Failure(it) },
  )
