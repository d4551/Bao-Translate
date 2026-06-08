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

/**
 * JUnit4 [Category] marker: tests that are part of the strict, no-skimping pyramid.
 *
 * Mark a test class with `@Category(Strict::class)` to enroll it in the
 * `:app:testDebugUnitTestStrict` Gradle gate (see app/build.gradle.kts). The strict
 * subset runs on every commit; the soft baseline (all other unit tests) stays for
 * backward compatibility but is not gating.
 *
 * Promotion rule: every test added during the brutalisation cycle must be `@Category(Strict::class)`.
 */
@org.junit.experimental.categories.CategoryMarker
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Strict
