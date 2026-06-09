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

  internal fun normalize(tag: String): String {
    // Android 7+ silently drops log entries whose tags contain control characters.
    val sanitized = buildString(tag.length) {
      for (ch in tag) {
        append(if (ch.code in 0..0x1F || ch.code == 0x7F) '_' else ch)
      }
    }
    if (sanitized.length <= TAG_MAX) return sanitized
    // Codepoint-aware truncation: never split a surrogate pair.
    val sb = StringBuilder()
    var codepoints = 0
    var i = 0
    while (i < sanitized.length && codepoints < TAG_MAX) {
      val c = sanitized[i]
      if (c.isHighSurrogate() && i + 1 < sanitized.length && sanitized[i + 1].isLowSurrogate()) {
        sb.append(c)
        sb.append(sanitized[i + 1])
        i += 2
      } else {
        sb.append(c)
        i += 1
      }
      codepoints++
    }
    return sb.toString()
  }

}
