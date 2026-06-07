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

// Top-level build file where you can add configuration options common to all sub-projects/modules.

// com.google.protobuf:protobuf-gradle-plugin:0.9.5 transitively pulls com.android.tools.build:gradle
// 7.1.0 onto the buildscript classpath. Its pre-8.0 CommonExtension shadows AGP 8.8.2's and breaks
// the Kotlin-DSL `android { packaging { ... } }` accessor (unresolved reference / cross-classloader
// cast). Pin the build tools to this project's AGP so a single, correct CommonExtension is used.
buildscript {
  configurations.classpath {
    resolutionStrategy.eachDependency {
      if (requested.group == "com.android.tools.build" &&
        (requested.name == "gradle" || requested.name == "gradle-api")
      ) {
        useVersion(libs.versions.agp.get())
        because("protobuf-gradle-plugin drags AGP 7.1.0; force this project's AGP for a single DSL")
      }
    }
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.hilt.application) apply false
  alias(libs.plugins.ksp) apply false
}
