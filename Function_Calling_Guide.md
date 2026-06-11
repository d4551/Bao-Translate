# Function Calling Guide

Bao Translate includes the Mobile Actions foundation from AI Edge Gallery. This guide explains how to add a custom action that an on-device model can call from the app.

Use this guide when you want to connect model output to a specific Android capability, such as opening a system screen, creating a calendar event, or triggering a custom in-app workflow.

## Overview

A function-calling action has four parts:

1. An `ActionType` enum value.
2. An `Action` implementation that carries the parameters.
3. A tool method exposed to the model.
4. Android-side logic that performs the requested action.

The model sees the tool description and parameters. The app receives the structured action and decides how to execute it.

## 1. Clone The Repository

```bash
git clone git@github.com:d4551/bao-translate.git
cd bao-translate
```

## 2. Define The Action Type

In [Actions.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/Actions.kt), add a new `ActionType` value and an `Action` subclass.

```kotlin
enum class ActionType {
  // Existing types...
  ACTION_NEW_CUSTOM_FUNCTION,
}

class NewCustomAction(val param: String) : Action(
  type = ActionType.ACTION_NEW_CUSTOM_FUNCTION,
  icon = Icons.Outlined.Favorite,
  functionCallDetails = FunctionCallDetails(
    functionName = "newCustomFunction",
    parameters = listOf(Pair("param", param)),
  ),
)
```

Choose a function name that clearly describes the action. Keep parameter names stable because prompts, tests, and UI traces may depend on them.

## 3. Add The Tool Definition

In [MobileActionsTools.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsTools.kt), create a function annotated with `@Tool` and `@ToolParam`. The method should translate model-provided parameters into your action object.

```kotlin
class MobileActionsTools(val onFunctionCalled: (Action) -> Unit) : Toolset {
  // Existing tools...

  @Tool(description = "Perform the custom action with the provided parameter.")
  fun newCustomFunction(
    @ToolParam(description = "Parameter used by the custom action.") param: String,
  ): Map<String, String> {
    onFunctionCalled(NewCustomAction(param = param))
    return mapOf("result" to "success")
  }
}
```

Tool descriptions should be concise and written for the model. Include enough context for the model to know when to call the tool, but avoid implementation details the model cannot use.

## 4. Implement The Android Action

Update `performAction` in [MobileActionsViewModel.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsViewModel.kt) to handle the new action type.

```kotlin
fun performAction(action: Action, context: Context): String {
  return when (action) {
    // Existing actions...
    is NewCustomAction -> handleNewCustomAction(context, action.param)
    else -> ""
  }
}

private fun handleNewCustomAction(context: Context, param: String): String {
  // Implement the Android-side behavior here.
  return ""
}
```

Keep side effects explicit. If the action opens another app, starts an activity, reads device state, or uses a sensitive permission, make that behavior clear in the code and UI.

## 5. Update The System Prompt When Needed

If the function requires context such as device state, supported values, or safety constraints, update `getSystemPrompt()` in [MobileActionsTask.kt](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsTask.kt).

Prompt additions should be short, testable, and specific. Prefer structured examples over long prose.

## 6. Build And Install

```bash
cd Android/src
./gradlew installDebug
```

Gradle downloads dependencies, compiles the app, and installs the debug APK on the connected device.

## Verification Checklist

- The new tool appears in the model's available tool list.
- The model can call the tool with valid JSON parameters.
- Invalid or missing parameters fail gracefully.
- The Android action executes only the intended behavior.
- User-visible strings are defined in Android resources.
- Logs do not include secrets, private prompts, raw voice data, or sensitive device details.
