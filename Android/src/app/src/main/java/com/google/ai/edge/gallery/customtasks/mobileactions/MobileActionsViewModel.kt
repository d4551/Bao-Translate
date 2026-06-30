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

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGMAViewModel"

/** The UI state of the MobileActionsViewModel. */
data class MobileActionsUiState(
  val showWelcomeMessage: Boolean = true,
  val processing: Boolean = false,
  val userPrompt: String = "",
  val modelResponse: String = "",
  val functionCallDetails: List<String> = listOf(),
  val noFunctionRecognized: Boolean = false,
)

@HiltViewModel
class MobileActionsViewModel
@Inject
constructor(@ApplicationContext private val appContext: Context) : ViewModel() {
  protected val _uiState = MutableStateFlow(MobileActionsUiState())
  val uiState = _uiState.asStateFlow()

  private val _isResettingConversation = MutableStateFlow(false)
  private val isResettingConversation = _isResettingConversation.asStateFlow()

  /** Owns the stock-camera capture lifecycle. See [CaptureCoordinator]. */
  val captureCoordinator = CaptureCoordinator(appContext)

  fun reset() {
    setFlashlight(context = appContext, isEnabled = false)
    setShowWelcomeMessage(showWelcomeMessage = true)
    setUserPrompt(prompt = "'")
    setModelResponse(response = "")
    setNoFunctionRecognized(value = false)
    clearFunctionCallDetails()
    captureCoordinator.reset()
  }

  fun cleanUp() {
    setFlashlight(context = appContext, isEnabled = false)
  }

  fun setShowWelcomeMessage(showWelcomeMessage: Boolean) {
    _uiState.update { it.copy(showWelcomeMessage = showWelcomeMessage) }
  }

  fun setProcessing(processing: Boolean) {
    _uiState.update { it.copy(processing = processing) }
  }

  fun setUserPrompt(prompt: String) {
    _uiState.update { it.copy(userPrompt = prompt) }
  }

  fun setModelResponse(response: String) {
    _uiState.update { it.copy(modelResponse = response) }
  }

  fun appendModelResponse(partialResponse: String) {
    _uiState.update { it.copy(modelResponse = it.modelResponse + partialResponse) }
  }

  fun addFunctionCallDetails(details: String) {
    _uiState.update { it.copy(functionCallDetails = it.functionCallDetails + details) }
  }

  fun clearFunctionCallDetails() {
    _uiState.update { it.copy(functionCallDetails = listOf()) }
  }

  fun setNoFunctionRecognized(value: Boolean) {
    _uiState.update { it.copy(noFunctionRecognized = value) }
  }

  fun processUserPrompt(
    model: Model,
    userPrompt: String,
    tools: List<ToolProvider>,
    onProcessDone: () -> Unit,
    onError: (error: String) -> Unit,
  ) {
    if (model.instance == null) {
      setProcessing(processing = false)
      return
    }

    viewModelScope.launch(Dispatchers.Default) {
      BaoLog.d(TAG, "Start processing user prompt: $userPrompt")
      setProcessing(processing = true)
      setShowWelcomeMessage(showWelcomeMessage = false)

      setModelResponse(response = "")
      setNoFunctionRecognized(value = false)
      clearFunctionCallDetails()

      setUserPrompt(prompt = userPrompt)

      BaoLog.d(TAG, "Waiting for active conversation reset to be done...")
      isResettingConversation.first { !it }
      BaoLog.d(TAG, "Done waiting. Start inference.")

      val instance = model.instance as? LlmModelInstance
      if (instance == null) {
        BaoLog.w(TAG, "No LlmModelInstance available; aborting inference.")
        setProcessing(processing = false)
        onError(appContext.getString(R.string.unknown_error))
        onProcessDone()
        return@launch
      }
      val conversation = instance.conversation
      val contents = mutableListOf<Content>()
      if (userPrompt.trim().isNotEmpty()) {
        contents.add(Content.Text(userPrompt))
      }

      conversation
        .sendMessageAsync(Contents.of(contents))
        .catch {
          BaoLog.e(TAG, "Failed to run inference", it)
          onError(it.message ?: appContext.getString(R.string.unknown_error))
        }
        .onCompletion {
          setProcessing(processing = false)
          onProcessDone()
          resetConversation(model = model, tools = tools)
        }
        .collect {
          setProcessing(processing = false)
          appendModelResponse(partialResponse = it.toString())
        }
    }
  }

  fun resetConversation(model: Model, tools: List<ToolProvider>) {
    _isResettingConversation.value = true
    LlmChatModelHelper.resetConversation(
      model = model,
      supportImage = false,
      supportAudio = false,
      systemInstruction = getSystemPrompt(appContext),
      tools = tools,
    )
    _isResettingConversation.value = false
  }

  fun resetEngine(
    context: Context,
    model: Model,
    tools: List<ToolProvider>,
    modelManagerViewModel: ModelManagerViewModel,
    onError: (error: String) -> Unit,
  ) {
    reset()

    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.setInitializationStatus(
        model = model,
        status = ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED),
      )
      LlmChatModelHelper.cleanUp(
        model = model,
        onDone = {
          LlmChatModelHelper.initialize(
            context = context,
            model = model,
            taskId = BuiltInTaskId.LLM_MOBILE_ACTIONS,
            supportImage = false,
            supportAudio = false,
            onDone = { error ->
              modelManagerViewModel.setInitializationStatus(
                model = model,
                status =
                  ModelInitializationStatus(status = ModelInitializationStatusType.INITIALIZED),
              )
              if (error.isNotEmpty()) {
                onError(error)
              }
            },
            systemInstruction = getSystemPrompt(appContext),
            tools = tools,
          )
        },
      )
    }
  }

  fun performAction(action: Action, context: Context): String {
    return when (action) {
      is FlashlightOnAction -> setFlashlight(context = context, isEnabled = true)

      is FlashlightOffAction -> setFlashlight(context = context, isEnabled = false)

      is CreateContactAction ->
        createContact(
          context = context,
          firstName = action.firstName,
          lastName = action.lastName,
          phoneNumber = action.phoneNumber,
          email = action.email,
        )

      is SendEmailAction ->
        sendEmail(context = context, to = action.to, subject = action.subject, body = action.body)

      is ShowLocationOnMap -> showLocationOnMap(context = context, location = action.location)

      is OpenWifiSettingsAction -> openWifiSettings(context = context)

      is CreateCalendarEventAction ->
        createCalendarEvent(context = context, datetime = action.datetime, title = action.title)

      is CapturePhotoAction -> captureCoordinator.arm(action = action)

      else -> {
        BaoLog.w(TAG, "Unknown action type: ${action::class.simpleName}")
        "Unknown action type: ${action::class.simpleName}"
      }
    }
  }

  private fun setFlashlight(context: Context, isEnabled: Boolean): String {
    val cameraManager: CameraManager =
      context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val cameraIds = cameraManager.cameraIdList
    if (cameraIds.isEmpty()) {
      BaoLog.e(TAG, "No cameras available for flashlight")
      return context.getString(R.string.unknown_error)
    }

    val cameraId: String? = runCatching {
      cameraIds.firstOrNull { id ->
        val characteristics = cameraManager.getCameraCharacteristics(id)
        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
      }
    }.onFailure { e ->
      BaoLog.e(TAG, "Failed to enumerate cameras", e)
      return context.getString(R.string.mobile_actions_error_no_camera)
    }.getOrNull()

    if (cameraId == null) {
      BaoLog.w(TAG, "No camera with flash found")
      return context.getString(R.string.mobile_actions_error_no_flash)
    }

    val result = runCatching { cameraManager.setTorchMode(cameraId, isEnabled) }
    result.onFailure { e ->
      BaoLog.e(TAG, "Failed to set flashlight", e)
      return context.getString(R.string.mobile_actions_error_flashlight)
    }

    return ""
  }

  private fun createContact(
    context: Context,
    firstName: String,
    lastName: String,
    phoneNumber: String,
    email: String,
  ): String {
    val intent =
      Intent(ContactsContract.Intents.Insert.ACTION)
        .apply { type = ContactsContract.RawContacts.CONTENT_TYPE }
        .apply {
          putExtra(ContactsContract.Intents.Insert.NAME, "$firstName $lastName")
          putExtra(ContactsContract.Intents.Insert.EMAIL, email)
          putExtra(
            ContactsContract.Intents.Insert.EMAIL_TYPE,
            ContactsContract.CommonDataKinds.Email.TYPE_WORK,
          )
          putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
          putExtra(
            ContactsContract.Intents.Insert.PHONE_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
          )
        }

    return launchIntentOrError(context, intent, "handle contact creation")
  }

  private fun sendEmail(context: Context, to: String, subject: String, body: String): String {
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        setDataAndType("mailto:".toUri(), "text/plain")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
      }

    return launchIntentOrError(context, intent, "handle email sending")
  }

  private fun showLocationOnMap(context: Context, location: String): String {
    val encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8.toString())
    val intent = Intent(Intent.ACTION_VIEW).apply { data = "geo:0,0?q=$encodedLocation".toUri() }

    return launchIntentOrError(context, intent, "handle map display")
  }

  private fun openWifiSettings(context: Context): String {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
    return launchIntentOrError(context, intent, "open wifi settings")
  }

  private fun createCalendarEvent(context: Context, datetime: String, title: String): String {
    val parsed = parseDateTime(datetime)
      ?: return context.getString(R.string.mobile_actions_error_invalid_datetime)
    val systemDefaultZone = ZoneId.systemDefault()
    val ms = parsed.atZone(systemDefaultZone).toInstant().toEpochMilli()

    val intent =
      Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, title)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + DEFAULT_EVENT_DURATION_MS)
      }
    return launchIntentOrError(context, intent, "create calendar event")
  }

  companion object {
    internal const val DEFAULT_EVENT_DURATION_MS = 3_600_000L
  }
}

internal fun launchIntentOrError(context: Context, intent: Intent, actionName: String): String {
  return launchIntentOrError(
    isResolvable = intent.resolveActivity(context.packageManager) != null,
    startActivity = { context.startActivity(intent) },
    noActivityMessage = { context.getString(R.string.mobile_actions_error_no_activity) },
    actionName = actionName,
  )
}

internal fun launchIntentOrError(
  isResolvable: Boolean,
  startActivity: () -> Unit,
  noActivityMessage: () -> String,
  actionName: String,
): String {
  if (!isResolvable) {
    BaoLog.e(TAG, "No activity to $actionName")
    return noActivityMessage()
  }
  startActivity()
  return ""
}

private val ISO_DATETIME_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?")

internal fun parseDateTime(datetime: String): LocalDateTime? {
  if (!ISO_DATETIME_PATTERN.matches(datetime)) {
    BaoLog.w(TAG, "Invalid date time format: '$datetime'")
    return null
  }
  val parts = datetime.split("T")
  val dateParts = parts[0].split("-")
  val month = dateParts[1].toIntOrNull()
  val day = dateParts[2].toIntOrNull()
  if (month == null || month !in 1..12 || day == null || day !in 1..31) {
    BaoLog.w(TAG, "Date component out of range: '$datetime'")
    return null
  }
  val timeParts = parts[1].split(":")
  val hour = timeParts[0].toIntOrNull()
  val minute = timeParts[1].toIntOrNull()
  val second = if (timeParts.size > 2) timeParts[2].toIntOrNull() else 0
  if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
    BaoLog.w(TAG, "Time component out of range: '$datetime'")
    return null
  }
  return runCatching { LocalDateTime.parse(datetime, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
    .onFailure { BaoLog.w(TAG, "Invalid calendar date: '$datetime'") }
    .getOrNull()
}
