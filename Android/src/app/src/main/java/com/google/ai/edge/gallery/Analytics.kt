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

package com.google.ai.edge.gallery

import com.google.ai.edge.gallery.common.BaoLog
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics

private var hasLoggedAnalyticsWarning = false

val firebaseAnalytics: FirebaseAnalytics?
  get() {
    return if (FirebaseApp.getApps(android.app.Application()).isNotEmpty()) {
      Firebase.analytics
    } else {
      if (!hasLoggedAnalyticsWarning) {
        BaoLog.w("AGAnalyticsFirebase", "Firebase Analytics is not available: no FirebaseApp initialized")
        hasLoggedAnalyticsWarning = true
      }
      null
    }
  }

enum class GalleryEvent(val id: String) {
  CAPABILITY_SELECT(id = "capability_select"),
  MODEL_DOWNLOAD(id = "model_download"),
  GENERATE_ACTION(id = "generate_action"),
  BUTTON_CLICKED(id = "button_clicked"),
  SKILL_MANAGEMENT(id = "skill_management"),
  SKILL_EXECUTION(id = "skill_execution"),
  CHAT_HISTORY(id = "chat_history"),
  MCP_MANAGEMENT(id = "mcp_management"),
  MCP_EXECUTION(id = "mcp_execution"),
}
