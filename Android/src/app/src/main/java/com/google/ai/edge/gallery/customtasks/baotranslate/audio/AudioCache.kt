package com.google.ai.edge.gallery.customtasks.baotranslate.audio

import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.SynthesizedAudio
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * LRU in-memory cache for synthesized audio keyed by (text, language, voiceId, speed), with an
 * optional encrypted on-disk warm tier for instant replay across sessions.
 */
object AudioCache {
  private const val TAG = "AudioCache"
  private const val MAX_TOTAL_SAMPLES = 12_500_000

  private data class CacheKey(
    val text: String,
    val language: String,
    val voiceId: String,
    val speed: Float,
  )

  private val cache = LinkedHashMap<CacheKey, SynthesizedAudio>(16, 0.75f, true)
  private var totalSamples = 0
  @Volatile private var diskStore: EncryptedBlobStore? = null

  fun attachDiskStore(store: EncryptedBlobStore) {
    diskStore = store
  }

  fun get(text: String, language: String, voiceId: String = "default", speed: Float = 1.0f): SynthesizedAudio? = synchronized(this) {
    val key = CacheKey(text, language, voiceId, speed)
    cache[key]?.let {
      BaoLog.d(TAG, "Cache HIT (memory) text=${text.length}ch lang=$language")
      return it
    }
    val disk = diskStore ?: return null
    val path = keyHash(key)
    val bytes = disk.read(path) ?: return null
    val audio = deserialize(bytes) ?: return null
    putInMemory(key, audio)
    BaoLog.d(TAG, "Cache HIT (disk) text=${text.length}ch lang=$language")
    audio
  }

  fun put(text: String, language: String, voiceId: String = "default", speed: Float = 1.0f, audio: SynthesizedAudio) = synchronized(this) {
    val key = CacheKey(text, language, voiceId, speed)
    putInMemory(key, audio)
    diskStore?.write(keyHash(key), serialize(audio))
    BaoLog.d(TAG, "Cache PUT entries=${cache.size} totalSamples=$totalSamples text=${text.length}ch lang=$language")
  }

  fun invalidate() = synchronized(this) {
    val count = cache.size
    cache.clear()
    totalSamples = 0
    diskStore?.clearAll()
    BaoLog.i(TAG, "Cache INVALIDATED — cleared $count memory entries")
  }

  fun stats(): CacheStats = synchronized(this) {
    CacheStats(
      entryCount = cache.size,
      totalSamples = totalSamples,
      estimatedBytes = totalSamples * 4L,
    )
  }

  private fun putInMemory(key: CacheKey, audio: SynthesizedAudio) {
    cache.remove(key)?.let { old -> totalSamples -= old.samples.size }
    while (totalSamples + audio.samples.size > MAX_TOTAL_SAMPLES && cache.isNotEmpty()) {
      val eldest = cache.entries.first()
      cache.remove(eldest.key)
      totalSamples -= eldest.value.samples.size
      BaoLog.d(TAG, "Cache EVICT text=${eldest.key.text.length}ch lang=${eldest.key.language}")
    }
    cache[key] = audio
    totalSamples += audio.samples.size
  }

  private fun keyHash(key: CacheKey): String {
    val raw = "${key.text}|${key.language}|${key.voiceId}|${key.speed}"
    return MessageDigest.getInstance("SHA-256")
      .digest(raw.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
  }

  private fun serialize(audio: SynthesizedAudio): ByteArray {
    val buf = ByteBuffer.allocate(4 + audio.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(audio.sampleRate)
    audio.samples.forEach { buf.putFloat(it) }
    return buf.array()
  }

  private fun deserialize(bytes: ByteArray): SynthesizedAudio? {
    if (bytes.size < 4) return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val rate = buf.getInt()
    val sampleCount = (bytes.size - 4) / 4
    if (sampleCount <= 0) return null
    val samples = FloatArray(sampleCount) { buf.getFloat() }
    return SynthesizedAudio(samples = samples, sampleRate = rate)
  }

  data class CacheStats(
    val entryCount: Int,
    val totalSamples: Int,
    val estimatedBytes: Long,
  )
}