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
 * Typed domain error hierarchy. Use this with [Outcome] to replace broad `try { } catch (...) { }`
 * blocks. Each variant carries the underlying [Throwable] when relevant so call-sites can log or
 * surface details without losing fidelity.
 */
sealed interface AppError {
  /** Generic I/O failure (file, network, stream). */
  data class Io(val cause: Throwable) : AppError

  /** JSON / protobuf / structured-data parse failure. */
  data class Parse(val cause: Throwable) : AppError

  /** Network / HTTP failure. */
  data class Network(val cause: Throwable) : AppError

  /** Resource not found (model, skill, MCP server, etc.). */
  data class NotFound(val what: String) : AppError

  /** Unauthenticated or unauthorized request. */
  data class Auth(val reason: String) : AppError

  /** Catch-all for unmapped [Throwable]s. */
  data class Unknown(val cause: Throwable) : AppError

  /** Render a user-facing message. */
  fun message(): String =
    when (this) {
      is Io -> cause.message ?: "I/O error"
      is Parse -> cause.message ?: "Parse error"
      is Network -> cause.message ?: "Network error"
      is NotFound -> what
      is Auth -> reason
      is Unknown -> cause.message ?: "Unknown error"
    }
}

/** Adapt any [Throwable] into the most specific [AppError] subtype. */
fun Throwable.toAppError(): AppError =
  when (this) {
    is java.io.IOException -> AppError.Io(this)
    is kotlinx.serialization.SerializationException,
    is com.google.protobuf.InvalidProtocolBufferException ->
      AppError.Parse(this)
    is java.net.UnknownHostException,
    is java.net.SocketTimeoutException,
    is java.net.ConnectException ->
      AppError.Network(this)
    else -> AppError.Unknown(this)
  }
