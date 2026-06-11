package com.google.ai.edge.gallery.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/**
 * A single reduced-motion-aware infinite float "pulse" — the one place this app gates a looping
 * animation behind the accessibility preference.
 *
 * When animations are off ([isReducedMotion]: the user's reduce-motion setting OR an instrumented test
 * with `animator_duration_scale = 0`) it returns a static [restValue] and registers NO animation, so
 * the Compose frame clock can reach idle (an `infiniteRepeatable` never idles and would hang
 * `waitForIdle()`-based tests). Otherwise it loops [initialValue] → [targetValue] via `infiniteRepeatable`.
 *
 * This replaces six hand-rolled copies of the same `if (reduceMotion) … else infiniteTransition.animateFloat(…)`
 * block (DRY) and uses `mutableFloatStateOf` to avoid autoboxing the primitive.
 */
@Composable
fun rememberPulseFloat(
  initialValue: Float,
  targetValue: Float,
  durationMillis: Int,
  restValue: Float = targetValue,
  repeatMode: RepeatMode = RepeatMode.Reverse,
  easing: Easing = LinearEasing,
  label: String = "pulse",
): State<Float> {
  // rememberInfiniteTransition is called unconditionally (stable composition structure); the animation
  // only produces frames when animateFloat is actually registered, i.e. only in the not-reduced branch.
  val transition = rememberInfiniteTransition(label = label)
  return if (isReducedMotion) {
    remember(restValue) { mutableFloatStateOf(restValue) }
  } else {
    transition.animateFloat(
      initialValue = initialValue,
      targetValue = targetValue,
      animationSpec = infiniteRepeatable(tween(durationMillis, easing = easing), repeatMode),
      label = "${label}_value",
    )
  }
}
