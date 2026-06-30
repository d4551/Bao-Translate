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

import androidx.core.net.toUri
import com.google.ai.edge.gallery.BuildConfig
import net.openid.appauth.AuthorizationServiceConfiguration

/** Central project configuration sourced from Gradle BuildConfig fields and runtime properties. */
object ProjectConfig {
  val clientId: String = BuildConfig.HUGGING_FACE_CLIENT_ID

  val redirectUri: String = BuildConfig.HUGGING_FACE_REDIRECT_URI

  private val authEndpoint: String = BuildConfig.HUGGING_FACE_AUTH_ENDPOINT

  private val tokenEndpoint: String = BuildConfig.HUGGING_FACE_TOKEN_ENDPOINT

  val isHuggingFaceOAuthConfigured: Boolean
    get() =
      clientId.isNotBlank() &&
        redirectUri.isNotBlank() &&
        authEndpoint.isNotBlank() &&
        tokenEndpoint.isNotBlank()

  val authServiceConfig: AuthorizationServiceConfiguration?
    get() =
      if (isHuggingFaceOAuthConfigured) {
        AuthorizationServiceConfiguration(authEndpoint.toUri(), tokenEndpoint.toUri())
      } else {
        null
      }

  /** Application version name from build config. Single source of truth for version display. */
  val versionName: String = BuildConfig.VERSION_NAME

  /** Application version code from build config. */
  val versionCode: Int = BuildConfig.VERSION_CODE

  /** True when running on Android runtime (vs plain JVM unit tests). */
  val isAndroidRuntime: Boolean =
    (System.getProperty("java.runtime.name", "") ?: "").contains("Android", ignoreCase = true)
}
