/*
 * Copyright 2025 Google LLC
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
 * Project-wide log facade. Zero-dep wrapper that enforces:
 *
 *  - **Tag discipline**: tags longer than [TAG_MAX] characters are truncated to avoid the
 *    Android 23-char tag limit (API 24) which silently drops the entry otherwise.
 *  - **Throwable handling**: every level overload accepts an optional [Throwable].
 *  - **Uniform API**: `BaoLog.d(tag, msg)` matches the SLF4J/Android idiom.
 *  - **JVM test safety**: uses reflection to dispatch to `android.util.Log`, so this class
 *    loads cleanly in plain JVM unit tests where `android.util.Log` is absent.
 */
object BaoLog {

  const val TAG_MAX: Int = 23

  private val logClass: Class<*>? by lazy {
    val runtimeName = System.getProperty("java.runtime.name") ?: ""
    if (!runtimeName.contains("Android", ignoreCase = true)) return@lazy null
    runCatching { Class.forName("android.util.Log") }.getOrNull()
  }

  private fun log(level: String, tag: String, message: String) {
    val cls = logClass ?: return
    val method = cls.getMethod(level, String::class.java, String::class.java)
    method.invoke(null, normalize(tag), message)
  }

  private fun log(level: String, tag: String, message: String, throwable: Throwable) {
    val cls = logClass ?: return
    val method = cls.getMethod(level, String::class.java, String::class.java, Throwable::class.java)
    method.invoke(null, normalize(tag), message, throwable)
  }

  private fun logW(tag: String, throwable: Throwable) {
    val cls = logClass ?: return
    val method = cls.getMethod("w", String::class.java, Throwable::class.java)
    method.invoke(null, normalize(tag), throwable)
  }

  fun v(tag: String, message: String) { log("v", tag, message) }
  fun v(tag: String, message: String, throwable: Throwable) { log("v", tag, message, throwable) }
  fun d(tag: String, message: String) { log("d", tag, message) }
  fun d(tag: String, message: String, throwable: Throwable) { log("d", tag, message, throwable) }
  fun i(tag: String, message: String) { log("i", tag, message) }
  fun i(tag: String, message: String, throwable: Throwable) { log("i", tag, message, throwable) }
  fun w(tag: String, message: String) { log("w", tag, message) }
  fun w(tag: String, message: String, throwable: Throwable) { log("w", tag, message, throwable) }
  fun w(tag: String, throwable: Throwable) { logW(tag, throwable) }
  fun e(tag: String, message: String) { log("e", tag, message) }
  fun e(tag: String, message: String, throwable: Throwable) { log("e", tag, message, throwable) }

  internal fun normalize(tag: String): String =
    if (tag.length <= TAG_MAX) tag else tag.substring(0, TAG_MAX)

}
