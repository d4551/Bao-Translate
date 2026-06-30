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

package com.google.ai.edge.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.google.ai.edge.gallery.common.BaoLog
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.URI

/** Handles Firebase Cloud Messaging payloads when Firebase is configured for the build. */
class GalleryFcmMessagingService : FirebaseMessagingService() {
  /** Records token refresh metadata without exposing the token in release logs. */
  @Deprecated("Required by FirebaseMessagingService token refresh dispatch.")
  override fun onNewToken(token: String) {
    BaoLog.d(TAG, "FCM registration token refreshed")
  }

  /** Converts supported FCM payloads into local notifications. */
  override fun onMessageReceived(remoteMessage: RemoteMessage) {
    // Do not log the full RemoteMessage or payload values — they reach release logcat. Metadata only.
    BaoLog.d(TAG, "Message received from: ${remoteMessage.from}, dataKeys=${remoteMessage.data.keys}")

    val data = remoteMessage.data
    val notification = remoteMessage.notification

    val deeplink = data["deeplink"]
    val imageUrlStr = data["image_url"]
    val imageUrl = imageUrlStr?.let { it.toUri() }

    val title = data["title"] ?: notification?.title
    val body = data["body"] ?: notification?.body

    BaoLog.d(
      TAG,
      "Extracted FCM Data -> hasTitle=${title != null}, hasBody=${body != null}, hasDeeplink=${deeplink != null}",
    )

    if (title != null && body != null) {
      sendNotification(title, body, imageUrl, deeplink)
    } else if (data.isNotEmpty()) {
      handleNow()
    }
  }

  /** Records receipt of a data-only message without exposing payload values. */
  private fun handleNow() {
    BaoLog.d(TAG, "Short lived task is done.")
  }

  /** Shows a high-priority notification for a validated FCM payload. */
  private fun sendNotification(
    title: String?,
    messageBody: String,
    imageUrl: android.net.Uri?,
    deeplink: String? = null,
  ) {
    val intent =
      if (!deeplink.isNullOrEmpty()) {
        Intent(Intent.ACTION_VIEW, deeplink.toUri()).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
      } else {
        Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) }
      }
    val requestCode = 0
    val pendingIntent =
      PendingIntent.getActivity(
        this,
        requestCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val channelId = "gallery_high_priority_push_channel"
    val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    val notificationBuilder =
      NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title ?: getString(R.string.gallery_news_notification_title))
        .setContentText(messageBody)
        .setAutoCancel(true)
        .setSound(defaultSoundUri)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    if (imageUrl != null) {
      runCatching {
        val url = URI(imageUrl.toString()).toURL()
        val connection = com.google.ai.edge.gallery.common.network.HttpClient.openConnection(
          url = url,
          connectTimeout = 5000,
          readTimeout = 5000,
        )
        val bitmap = connection.getInputStream().use { inputStream -> BitmapFactory.decodeStream(inputStream) }
        if (bitmap != null) {
          notificationBuilder.setLargeIcon(bitmap)
          notificationBuilder.setStyle(
            NotificationCompat.BigPictureStyle()
              .bigPicture(bitmap),
          )
        }
      }.onFailure { error ->
        BaoLog.w(TAG, "Failed to download image", error)
      }
    }

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val channel =
      NotificationChannel(
        channelId,
        getString(R.string.gallery_news_notification_title),
        NotificationManager.IMPORTANCE_HIGH,
      )
    notificationManager.createNotificationChannel(channel)

    val notificationId = 0
    notificationManager.notify(notificationId, notificationBuilder.build())
  }

  companion object {
    private const val TAG = "AGFcmMessagingService"
  }
}
