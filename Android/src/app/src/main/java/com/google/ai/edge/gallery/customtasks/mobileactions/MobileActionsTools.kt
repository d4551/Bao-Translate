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
package com.google.ai.edge.gallery.customtasks.mobileactions

import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AGMATools"

internal fun maskEmail(email: String): String {
  val at = email.indexOf('@')
  if (at <= 0) return "***"
  val local = email.substring(0, at)
  val domain = email.substring(at)
  val masked = if (local.length <= 1) "***" else "${local[0]}***"
  return "$masked$domain"
}

internal fun maskPhone(phone: String): String {
  val digits = phone.filter { it.isDigit() }
  if (digits.length < 4) return "***"
  return "${digits.take(2)}***${digits.takeLast(2)}"
}

class MobileActionsTools(val onFunctionCalled: (Action) -> Unit) : ToolSet {
  /** Turns on flashlight. */
  @Tool(description = "Turns the flashlight on")
  fun turnOnFlashlight(): Map<String, String> {
    BaoLog.d(TAG, "turn on flashlight")

    onFunctionCalled(FlashlightOnAction())

    return mapOf("result" to "success")
  }

  /** Turns off flashlight. */
  @Tool(description = "Turns the flashlight off")
  fun turnOffFlashlight(): Map<String, String> {
    BaoLog.d(TAG, "turn off flashlight")

    onFunctionCalled(FlashlightOffAction())

    return mapOf("result" to "success")
  }

  /** Creates contact. */
  @Tool(description = "Creates a contact in the phone's contact list.")
  fun createContact(
    @ToolParam(description = "The first name of the contact.") firstName: String,
    @ToolParam(description = "The last name of the contact.") lastName: String,
    @ToolParam(description = "The phone number of the contact.") phoneNumber: String,
    @ToolParam(description = "The email address of the contact.") email: String,
  ): Map<String, String> {
    BaoLog.d(
      TAG,
      "create contact. phone='${maskPhone(phoneNumber)}', email='${maskEmail(email)}'",
    )

    onFunctionCalled(
      CreateContactAction(
        firstName = firstName,
        lastName = lastName,
        phoneNumber = phoneNumber,
        email = email,
      )
    )

    return mapOf(
      "result" to "success",
      "first_name" to firstName,
      "last_name" to lastName,
      "phone_number" to phoneNumber,
      "email" to email,
    )
  }

  /** Sends email. */
  @Tool(description = "Sends an email.")
  fun sendEmail(
    @ToolParam(description = "The email address of the recipient.") to: String,
    @ToolParam(description = "The subject of the email.") subject: String,
    @ToolParam(description = "The body of the email.") body: String,
  ): Map<String, String> {
    BaoLog.d(TAG, "send email. to='${maskEmail(to)}', subject=[${subject.length} chars], body=[${body.length} chars]")

    onFunctionCalled(SendEmailAction(to = to, subject = subject, body = body))

    return mapOf("result" to "success", "to" to to, "subject" to subject, "body" to body)
  }

  /** Shows location on map. */
  @Tool(description = "Shows a location on the map.")
  fun showLocationOnMap(
    @ToolParam(
      description =
        "The location to search for. May be the name of a place, a business, or an address."
    )
    location: String
  ): Map<String, String> {
    BaoLog.d(TAG, "Show location on map. location=[${location.length} chars]")

    onFunctionCalled(ShowLocationOnMap(location = location))

    return mapOf("result" to "success", "location" to location)
  }

  /** Opens wifi settings. */
  @Tool(description = "Opens the WiFi settings.")
  fun openWifiSettings(): Map<String, String> {
    BaoLog.d(TAG, "Open wifi settings")

    onFunctionCalled(OpenWifiSettingsAction())

    return mapOf("result" to "success")
  }

  /** Creates calendar events. */
  @Tool(description = "Creates a new calendar event.")
  fun createCalendarEvent(
    @ToolParam(description = "The date and time of the event in the format YYYY-MM-DDTHH:MM:SS.")
    datetime: String,
    @ToolParam(description = "The title of the event.") title: String,
  ): Map<String, String> {
    BaoLog.d(TAG, "Create calendar event. datetime=[${datetime.length} chars], title=[${title.length} chars]")

    onFunctionCalled(CreateCalendarEventAction(datetime = datetime, title = title))

    return mapOf("result" to "success", "datetime" to datetime, "title" to title)
  }

  /**
   * Captures a high-resolution photo using the device's stock camera app. On Samsung Galaxy S
   * Ultra devices the user can select 12MP / 50MP / 200MP (and 24MP on S26 Ultra) modes directly
   * in the camera UI; third-party Camera2 access is capped at the binned default so the stock
   * camera is the only path to full sensor resolution.
   */
  @Tool(description = "Captures a high-resolution photo via the stock camera app.")
  fun capturePhoto(
    @ToolParam(
      description =
        "Desired resolution hint: \"12mp\", \"50mp\", \"200mp\", or \"auto\". The stock camera decides the actual output; on Samsung S Ultra the user picks the mode in-camera."
    )
    resolution: String,
    @ToolParam(
      description = "Lens/mode hint: \"main\", \"ultrawide\", \"tele\", or \"auto\"."
    )
    mode: String,
  ): Map<String, String> {
    BaoLog.d(
      TAG,
      "Capture photo. resolution='$resolution', mode='$mode'",
    )

    onFunctionCalled(
      CapturePhotoAction(resolutionHint = resolution, mode = mode)
    )

    return mapOf(
      "result" to "success",
      "resolution" to resolution,
      "mode" to mode,
    )
  }
}
