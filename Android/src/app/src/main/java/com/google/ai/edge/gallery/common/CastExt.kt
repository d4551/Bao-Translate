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
 * Safe unchecked-cast replacement. Returns the receiver cast to [T] when the runtime type matches,
 * or `null` otherwise. Use this in place of `value as T` when the value comes from an `Any?` source
 * (bundle extras, DataStore JSON, config map) where the compiler cannot guarantee the type.
 *
 * Example:
 * ```
 * val n: Int? = bundle.getString("count").safeAs()  // null if not an Int
 * val n: Int  = bundle.getString("count").safeAs(default = 0)
 * ```
 */
inline fun <reified T> Any?.safeAs(): T? = this as? T

/**
 * Safe cast with a typed fallback when the runtime type does not match.
 */
inline fun <reified T> Any?.safeAs(default: T): T = (this as? T) ?: default
