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

package com.google.ai.edge.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.KEY_MODEL_COMMIT_HASH
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_URLS
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_ZIP
import com.google.ai.edge.gallery.data.KEY_MODEL_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_START_UNZIPPING
import com.google.ai.edge.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_UNZIPPED_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_URL
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import com.google.ai.edge.gallery.common.BaoLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private const val SHARED_NOTIFICATION_ID = 1001
private var channelCreated = false
// Shared throttle timestamp across all worker instances to prevent notification spam
private var lastSharedNotificationTs = 0L
// Track which worker "owns" the foreground notification to avoid duplicate setForeground calls
private var activeForegroundWorkerId: String? = null

class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val externalFilesDir = context.getExternalFilesDir(null)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  // Use shared notification ID to consolidate multiple downloads into one notification.
  private val notificationId: Int = SHARED_NOTIFICATION_ID
  private val workerId: String = params.id.toString()

  init {
    if (!channelCreated) {
      // Create a notification channel for showing notifications for model downloading progress.
      val channel =
        NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            "Model Downloading",
            // Make it silent.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Notifications for model downloading" }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  override suspend fun doWork(): Result {
    val fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = inputData.getString(KEY_MODEL_COMMIT_HASH) ?: return Result.failure()
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR) ?: return Result.failure()
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

    return withContext(Dispatchers.IO) {
      if (fileUrl == null || fileName == null) {
        Result.failure()
      } else {
        return@withContext runCatching {
          // Set the worker as a foreground service immediately.
          setForeground(createForegroundInfo(progress = 0, modelName = modelName))

          // Collect data for all files.
          val allFiles: MutableList<UrlAndFileName> = mutableListOf()
          allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
          for (index in extraDataFileUrls.indices) {
            allFiles.add(
              UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
            )
          }
          BaoLog.d(TAG, "About to download: $allFiles")

          // Download them in sequence.
          // TODO: maybe consider downloading them in parallel.
          var downloadedBytes = 0L
          val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
          val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
          for (file in allFiles) {
            val url = URL(file.url)

            val connection = com.google.ai.edge.gallery.common.network.HttpClient.openConnection(
              url = url,
              accessToken = if (!accessToken.isNullOrEmpty()) {
                BaoLog.d(TAG, "Using access token: present")
                accessToken
              } else null,
            )

            // Prepare output file's dir.
            val outputDir =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version).joinToString(separator = File.separator),
              )
            if (!outputDir.exists()) {
              outputDir.mkdirs()
            }

            // Read the tmp file and see if it is partially downloaded.
            val outputTmpFile =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                  .joinToString(separator = File.separator),
              )
            val outputFileBytes = outputTmpFile.length()
            if (outputFileBytes > 0) {
              BaoLog.d(
                TAG,
                "File '${outputTmpFile.name}' partial size: ${outputFileBytes}. Trying to resume download",
              )
              connection.setRequestProperty("Range", "bytes=${outputFileBytes}-")
              // Force the server to send non-compressed data to make download resuming work.
              connection.setRequestProperty("Accept-Encoding", "identity")
            }
            connection.connect()
            BaoLog.d(TAG, "response code: ${connection.responseCode}")

            if (
              connection.responseCode == HttpURLConnection.HTTP_OK ||
                connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            ) {
              val contentRange = connection.getHeaderField("Content-Range")

              if (contentRange != null) {
                // Parse the Content-Range header
                val rangeParts = contentRange.substringAfter("bytes ").split("/")
                val byteRange = rangeParts[0].split("-")
                val startByte = byteRange.getOrNull(0)?.toLongOrNull() ?: 0L
                val endByte = byteRange.getOrNull(1)?.toLongOrNull() ?: 0L

                BaoLog.d(
                  TAG,
                  "Content-Range: $contentRange. Start bytes: ${startByte}, end bytes: $endByte",
                )

                downloadedBytes += startByte
              } else {
                BaoLog.d(TAG, "Download starts from beginning.")
              }
            } else {
              throw IOException("HTTP error code: ${connection.responseCode}")
            }

            // `.use{}` guarantees both streams close on every exit path (incl. a mid-download read/
            // write throw — the dominant failure mode), instead of leaking their file descriptors.
            connection.inputStream.use { inputStream ->
            FileOutputStream(outputTmpFile, true /* append */).use { outputStream ->

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            var lastSetProgressTs: Long = 0
            var deltaBytes = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              outputStream.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead
              deltaBytes += bytesRead

              // Report progress every 200 ms.
              val curTs = System.currentTimeMillis()
              if (curTs - lastSetProgressTs > 200) {
                // Calculate download rate.
                var bytesPerMs = 0f
                if (lastSetProgressTs != 0L) {
                  if (bytesReadSizeBuffer.size == 5) {
                    bytesReadSizeBuffer.removeAt(0)
                  }
                  bytesReadSizeBuffer.add(deltaBytes)
                  if (bytesReadLatencyBuffer.size == 5) {
                    bytesReadLatencyBuffer.removeAt(0)
                  }
                  bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                  deltaBytes = 0L
                  bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                }

                // Calculate remaining seconds
                var remainingMs = 0f
                if (bytesPerMs > 0f && totalBytes > 0L) {
                  remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                }

                setProgress(
                  Data.Builder()
                    .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                    .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                    .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                    .build()
                )
                // Throttle notification updates to avoid rate limiting (max 1 per second across all workers)
                if (curTs - lastSharedNotificationTs >= 1000L) {
                  setForeground(
                    createForegroundInfo(
                      progress = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else 0,
                      modelName = modelName,
                    )
                  )
                  BaoLog.d(TAG, "downloadedBytes: $downloadedBytes")
                  lastSharedNotificationTs = curTs
                }
              }
            }

            } // outputStream.use
            } // inputStream.use
            connection.disconnect()

            // Rename the tmp file to the original file name by removing the tmp file ext.
            val originalFilePath = outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", "")
            val originalFile = File(originalFilePath)
            if (originalFile.exists()) {
              originalFile.delete()
            }
            outputTmpFile.renameTo(originalFile)
            BaoLog.d(TAG, "Download done")

            // Unzip if the downloaded file is a zip.
            if (isZip && unzippedDir != null) {
              setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

              // Prepare target dir.
              val destDir =
                File(
                  externalFilesDir,
                  listOf(modelDir, version, unzippedDir).joinToString(File.separator),
                )
              if (!destDir.exists()) {
                destDir.mkdirs()
              }

              // Unzip.
              val unzipBuffer = ByteArray(4096)
              val zipFilePath =
                "${externalFilesDir}${File.separator}$modelDir${File.separator}$version${File.separator}${fileName}"
              val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
              var zipEntry: ZipEntry? = zipIn.nextEntry

              val destDirCanonical = destDir.canonicalPath
              while (zipEntry != null) {
                val filePath = destDir.absolutePath + File.separator + zipEntry.name
                val canonicalFilePath = File(filePath).canonicalPath

                // Validate path stays within destDir (Zip Slip mitigation).
                if (!canonicalFilePath.startsWith(destDirCanonical + File.separator) &&
                    canonicalFilePath != destDirCanonical) {
                  BaoLog.w(TAG, "Skipping zip entry with path traversal: ${zipEntry.name}")
                  zipIn.closeEntry()
                  zipEntry = zipIn.nextEntry
                  continue
                }

                // Extract files.
                if (!zipEntry.isDirectory) {
                  // extract file
                  val bos = FileOutputStream(filePath)
                  bos.use { curBos ->
                    var len: Int
                    while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                      curBos.write(unzipBuffer, 0, len)
                    }
                  }
                }
                // Create dir.
                else {
                  val dir = File(filePath)
                  dir.mkdirs()
                }

                zipIn.closeEntry()
                zipEntry = zipIn.nextEntry
              }
              zipIn.close()

              // Delete the original file.
              val zipFile = File(zipFilePath)
              zipFile.delete()
            }
          }
          Result.success()
        }.getOrElse { e ->
          val message = e.message ?: e.toString()
          BaoLog.e(TAG, message, e)
          Result.failure(
            Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, message).build()
          )
        }
      }
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    // Initial progress is 0
    return createForegroundInfo(0)
  }

  /**
   * Creates a [ForegroundInfo] object for the download worker's ongoing notification. This
   * notification is used to keep the worker running in the foreground, indicating to the user that
   * an active download is in progress.
   */
  private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
    // Create a notification for the foreground service
    var title = "Downloading model"
    if (modelName != null) {
      title = "Downloading \"$modelName\""
    }
    val content = "Downloading in progress: $progress%"

    val intent =
      Intent(applicationContext, Class.forName("com.google.ai.edge.gallery.MainActivity")).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true) // Makes the notification non-dismissable
        .setProgress(100, progress, false) // Show progress
        .setContentIntent(pendingIntent)
        .build()

    return ForegroundInfo(
      notificationId,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }
}
