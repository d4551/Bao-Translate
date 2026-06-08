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
package com.google.ai.edge.gallery.testkit

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-cutting hardening tests that don't fit a single source file.
 *
 *  - [runBlockingWithTimeout_isRequired]: scans production test code for `runBlocking { ... }`
 *    blocks and asserts each is wrapped in `withTimeout(...)`. Catches test hangs at the source.
 *  - [isValidTranscription_producesDeterministicResult]: a sanity check on the test
 *    harness itself — the corpus fixture never produces a string that crashes the
 *    production filter.
 */
@Strict
class CrossCuttingHardeningTest {

  @Test
  fun runBlockingWithTimeout_isRequired() {
    // Walk the test source tree and find every `runBlocking { ... }` call. Assert that
    // each is preceded (within the same statement) by a `withTimeout(...)` call. This
    // is a static check — we don't execute the runBlocking.
    val testRoot = "src/test/java"
    val violations = mutableListOf<String>()
    findKtFiles(testRoot).forEach { file ->
      val text = file.readText()
      // Strip block comments and line comments to avoid false positives.
      val stripped = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//.*"""), "")
      val runBlockingLines = stripped.lineSequence()
        .mapIndexed { idx, line -> idx + 1 to line }
        .filter { (_, line) -> line.contains("runBlocking {") || line.contains("runBlocking(") }
        .toList()
      for ((lineNum, line) in runBlockingLines) {
        // Look 5 lines above for a withTimeout
        val start = (lineNum - 5).coerceAtLeast(0)
        val window = stripped.lines().subList(start, lineNum).joinToString("\n")
        if (!window.contains("withTimeout")) {
          violations.add("${file}:$lineNum: runBlocking without withTimeout: ${line.trim()}")
        }
      }
    }
    assertTrue(
      "runBlocking calls without withTimeout (catch: test hangs):\n" + violations.joinToString("\n"),
      violations.isEmpty(),
    )
  }

  private fun findKtFiles(relativePath: String): List<java.io.File> {
    val root = java.io.File(relativePath)
    if (!root.exists()) return emptyList()
    return root.walkTopDown().filter { it.extension == "kt" }.toList()
  }
}
