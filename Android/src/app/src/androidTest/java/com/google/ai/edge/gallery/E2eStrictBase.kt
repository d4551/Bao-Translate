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
package com.google.ai.edge.gallery

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Shared base for androidTest E2E suites. Provides:
 *
 *  - [RetryOnFlakeRule]: re-runs a test up to [maxRetries] times if it fails with
 *    an [org.junit.AssumptionViolatedException] (a real test skip, not a flake).
 *    Real [AssertionError]s fail the test immediately. Use sparingly — flakes
 *    should be fixed at the source, not papered over.
 *  - [pollUntil]: replaces scattered `while(deadline) { ... Thread.sleep(...) }`
 *    loops with a single, well-named helper.
 *  - [assertScreenshotValid]: replaces 3+ hand-rolled shell+ls blocks across
 *    multiple E2E files.
 *
 * Subclass and apply [flakyRule] in your `@get:Rule` chain.
 */
abstract class E2eStrictBase {

  /**
   * Retries on [org.junit.AssumptionViolatedException] up to [maxRetries] times.
   * Does NOT retry on [AssertionError] — real failures must surface immediately.
   */
  protected class RetryOnFlakeRule(private val maxRetries: Int = 2) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
      object : Statement() {
        override fun evaluate() {
          var lastAssumption: Throwable? = null
          for (attempt in 0..maxRetries) {
            try {
              base.evaluate()
              return
            } catch (e: org.junit.AssumptionViolatedException) {
              lastAssumption = e
              // try again
            } catch (e: Throwable) {
              throw e
            }
          }
          // Out of retries; surface the last assumption failure.
          throw lastAssumption ?: IllegalStateException("RetryOnFlakeRule exhausted with no exception")
        }
      }
  }

  protected fun flakyRule(): TestRule = RetryOnFlakeRule()

  /**
   * Poll [block] every [intervalMs] until it returns non-null or [timeoutMs] elapses.
   * Returns the non-null result, or null if the timeout fired. Callers must check
   * the return value and fail with a clear message if null.
   */
  protected fun <T> pollUntil(timeoutMs: Long, intervalMs: Long = 250, block: () -> T?): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val r = block()
      if (r != null) return r
      try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
    }
    return null
  }

  /**
   * Assert a screencap'd file at [path] exists and is at least [minBytes] bytes.
   * Uses `ls -ln` to query size; returns the size for downstream assertions.
   */
  protected fun assertScreenshotValid(path: String, minBytes: Long = 10_000L): Long {
    val listing = runShell("ls -ln $path")
    val size = parseRemoteFileSize(listing)
    assert(listing.contains(path)) { "Screenshot $path was not created. ls output: $listing" }
    assert(size >= minBytes) { "Screenshot $path was too small: $size bytes (need >= $minBytes)" }
    return size
  }

  /**
   * Run an `adb shell` command via UiAutomation and return its stdout.
   */
  protected fun runShell(command: String): String {
    val descriptor =
      InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
      .bufferedReader()
      .use { it.readText() }
  }

  /**
   * Parse the byte size of a file from a single-line `ls -ln` output.
   * The 5th whitespace-separated column is the byte count.
   */
  protected fun parseRemoteFileSize(listing: String): Long {
    val firstLine = listing.lineSequence().firstOrNull { it.isNotBlank() } ?: return 0L
    val columns = firstLine.trim().split(Regex("\\s+"))
    return columns.getOrNull(4)?.toLongOrNull() ?: 0L
  }

  /**
   * Parse [AudioMonitor] block from `dumpsys audio` output.
   * Returns a map of recorderPortId -> uid. Empty if block not found.
   */
  protected fun parseRecorderPorts(dump: String): Map<String, Int> {
    val start = dump.indexOf("AudioMonitor status:")
    if (start < 0) return emptyMap()
    val end = dump.indexOf("Events log: ZAudio service playbck & record monitor", start)
    val block = if (end > start) dump.substring(start, end) else dump.substring(start)
    val result = mutableMapOf<String, Int>()
    val regex = Regex("""(\S+)\s+->\s+(\d+)""")
    regex.findAll(block).forEach { match ->
      val (port, uidStr) = match.destructured
      val uid = uidStr.toIntOrNull() ?: return@forEach
      result[port] = uid
    }
    return result
  }
}
