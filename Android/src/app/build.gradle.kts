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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

// com.google.protobuf:protobuf-gradle-plugin:0.9.5 transitively pulls com.android.tools.build:gradle
// 7.1.0 onto this module's plugin classpath. Its pre-8.0 CommonExtension shadows AGP 8.8.2's and
// breaks the Kotlin-DSL `android { packaging { ... } }` accessor. Force the build tools to this
// project's AGP so a single, correct CommonExtension is on the classpath.
buildscript {
  configurations.all {
    resolutionStrategy.eachDependency {
      if (requested.group == "com.android.tools.build" &&
        (requested.name == "gradle" || requested.name == "gradle-api")
      ) {
        useVersion(libs.versions.agp.get())
      }
    }
  }
}

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
}

kotlin {
  // Modern KGP DSL (the legacy `android { kotlinOptions { ... } }` was removed in Kotlin 2.4).
  compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.bao.translate"
    minSdk = 31
    targetSdk = 35
    versionCode = 33
    versionName = "1.0.15"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
    manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    jniLibs {
      // sherpa-onnx and onnxruntime-android both ship libonnxruntime.so (both the official ORT
      // 1.24.3 build — byte-identical), so either copy satisfies both consumers. Keep one.
      pickFirsts += "**/libonnxruntime.so"
    }
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material3.adaptive)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  ksp(libs.hilt.android.compiler)
  // Give Hilt's KSP processor a Kotlin-2.4.0-aware metadata reader (unshaded since Dagger 2.57).
  ksp(libs.kotlin.metadata.jvm)
  testImplementation(libs.junit)
  testImplementation(libs.mockito.kotlin)
  testImplementation(libs.kotest.runner.junit4)
  testImplementation(libs.kotest.property)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  implementation(files("libs/sherpa-onnx-v1.13.2.aar"))
  // ONNX Runtime (Microsoft) — runs the OpenVoice converter + ref_enc ONNX graphs at EXACT
  // utterance length (dynamic time dim), which litert-torch cannot export. Validated 99 dB vs
  // PyTorch; exact length keeps the dilated WaveNet output crisp (fixed-length TFLite smeared it).
  // Pinned to 1.24.3: sherpa's JNI imports the ELF-versioned symbol OrtGetApiBase@VERS_1.24.3, and
  // 1.24.3 is the exact (byte-identical) ORT build sherpa-onnx 1.13.2 bundles — so the packaging
  // pickFirst on libonnxruntime.so (see android{}) leaves one runtime that satisfies both.
  implementation(libs.onnxruntime.android)
  // Google Nearby Connections: maintained, multi-medium (BLE + Bluetooth Classic + Wi-Fi) P2P
  // transport for the multi-device conversation mesh (see BleConversationManager).
  implementation(libs.play.services.nearby)
  implementation(libs.commons.compress)
}

// CI verification gate. `:app:verifyReleaseReady` runs every guard the release
// pipeline needs in a single invocation: typecheck, unit tests, Android Lint,
// and the full debug APK assembly. Wired here per OSF-008 so a build rot
// regression (cycles 1-5) cannot recur without a red CI run.
tasks.register("verifyReleaseReady") {
  group = "verification"
  description = "Run typecheck + tests + lint + debug APK assembly in one gate."
  dependsOn(
    "compileDebugKotlin",
    "compileDebugAndroidTestKotlin",
    "testDebugUnitTest",
    "lintDebug",
    "assembleDebug",
    "assembleDebugAndroidTest",
  )
}

// Strict subset: runs only tests marked @Category(Strict.class). Promoted to the release
// gate so every new brutalised test exercises production paths with no skipping, no
// wishful-thinking markers, no time-padding Thread.sleeps. Filters via the JUnit 4
// categories mechanism — the @Category marker on each test class is what gates inclusion.
tasks.register<Test>("testDebugUnitTestStrict") {
  group = "verification"
  description = "Run the @Category(Strict) subset of unit tests. Gating for release."
  // Reuse the EXACT compiled classes + runtime classpath of the full debug unit-test task, so this
  // runs the same bytecode — just filtered. Lazy files{} resolve at execution (order-independent);
  // dependsOn guarantees the test classes are compiled first.
  val full = tasks.named<Test>("testDebugUnitTest")
  // Lazy files{} carry the producing-task dependencies (compile + resources), so this builds the
  // test classes WITHOUT running the full suite — the strict gate must stand on its own.
  testClassesDirs = files({ full.get().testClassesDirs })
  classpath = files({ full.get().classpath })
  dependsOn("compileDebugUnitTestKotlin", "processDebugUnitTestJavaRes")
  // Real JUnit 4 category filtering: only classes/methods tagged @Category(Strict::class) run.
  useJUnit { includeCategories("com.google.ai.edge.gallery.testkit.Strict") }
}

tasks.named("verifyReleaseReady") {
  // Strict subset is gating; runs as part of the release gate alongside the full unit suite.
  dependsOn("testDebugUnitTestStrict")
}

tasks.register("smokeE2e") {
  group = "verification"
  description = "Install and run the debug instrumentation smoke test on connected devices."
  dependsOn("connectedDebugAndroidTest")
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
