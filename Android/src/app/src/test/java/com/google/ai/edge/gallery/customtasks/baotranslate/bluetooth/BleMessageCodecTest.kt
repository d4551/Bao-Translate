package com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth

import com.google.ai.edge.gallery.customtasks.baotranslate.tts.OpenVoiceVoiceConverter
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the remote-DoS fix: a connected BLE peer (or link corruption) can send bytes that pass the
 * substring validators but are not parseable JSON. Decoding must return null, never throw — a throw
 * would propagate out of the library's main-thread callback and crash the process.
 */
@Category(Strict::class)
class BleMessageCodecTest {

  @Test
  fun decodeTranscript_returnsNull_onMalformedPayloadThatPassesSubstringGate() {
    // Missing colons/values: passes every isValidTranscriptJson contains() check, invalid JSON.
    val malformed = "{\"text\" \"senderId\" \"senderName\" \"sourceLanguage\" \"targetLanguage\"}"
    assertTrue("precondition: substring gate passes", BleMessageCodec.isValidTranscriptJson(malformed))
    assertNull("must not throw; must drop", BleMessageCodec.decodeTranscript(malformed))
  }

  @Test
  fun decodeTranscript_returnsNull_onTrailingComma() {
    val malformed =
      "{\"text\":\"hi\",\"senderId\":\"a\",\"senderName\":\"b\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"es\",}"
    assertTrue(BleMessageCodec.isValidTranscriptJson(malformed))
    assertNull(BleMessageCodec.decodeTranscript(malformed))
  }

  @Test
  fun decodeTranscript_roundTripsValidMessage() {
    val msg = BleTranscriptMessage("hola", "s1", "Alice", "es", "en", 123L)
    val payload = BleMessageCodec.encodeTranscript(msg).drop(1) // strip the "T" header byte
    val decoded = BleMessageCodec.decodeTranscript(payload)
    assertNotNull(decoded)
    assertEquals("hola", decoded!!.text)
    assertEquals("Alice", decoded.senderName)
    assertEquals("es", decoded.sourceLanguage)
    assertEquals(123L, decoded.timestamp)
  }

  @Test
  fun decodeMetadata_returnsNull_onMalformedPayload() {
    // Empty value after participantId: passes the substring gate, invalid JSON.
    val malformed =
      "{\"participantId\":,\"participantName\":\"x\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"es\",\"hasVoiceProfile\":true}"
    assertTrue(BleMessageCodec.isValidMetadataJson(malformed))
    assertNull(BleMessageCodec.decodeMetadata(malformed))
  }

  @Test
  fun decodeMetadata_roundTripsValidMessage() {
    val msg = BleMetadataMessage("p1", "Bob", "fr", "en", true)
    val payload = BleMessageCodec.encodeMetadata(msg).drop(1)
    val decoded = BleMessageCodec.decodeMetadata(payload)
    assertNotNull(decoded)
    assertEquals("p1", decoded!!.participantId)
    assertTrue(decoded.hasVoiceProfile)
    assertNull("no embedding shared -> null, not empty", decoded.voiceEmbedding)
  }

  @Test
  fun decodeMetadata_roundTripsVoiceEmbedding() {
    // Multi-speaker cloning: a 256-d timbre must survive encode->wire->decode so a peer can be
    // spoken in their own voice.
    val se = List(256) { it * 0.0013f - 0.1f }
    val msg = BleMetadataMessage("p1", "Bob", "fr", "en", true, voiceEmbedding = se)
    val payload = BleMessageCodec.encodeMetadata(msg).drop(1)
    val decoded = BleMessageCodec.decodeMetadata(payload)
    assertNotNull(decoded)
    assertEquals(256, decoded!!.voiceEmbedding?.size)
    assertEquals(se[42], decoded.voiceEmbedding!![42], 1e-4f)
    assertEquals(se[255], decoded.voiceEmbedding[255], 1e-4f)
  }

  @Test
  fun encodeMetadata_fullWireRoundTrip_withVoiceEmbedding() {
    val se = List(OpenVoiceVoiceConverter.SE_DIM) { it * 0.0013f - 0.1f }
    val msg = BleMetadataMessage("p1", "Bob", "fr", "en", true, voiceEmbedding = se)
    val payload = BleMessageCodec.encodeMetadata(msg).drop(1) // strip the "M" header byte
    val decoded = BleMessageCodec.decodeMetadata(payload)
    assertNotNull(decoded)
    assertEquals(OpenVoiceVoiceConverter.SE_DIM, decoded!!.voiceEmbedding?.size)
    se.forEachIndexed { index, expected ->
      assertEquals("mismatch at index $index", expected, decoded.voiceEmbedding!![index], 1e-4f)
    }
  }

  @Test
  fun encodeMetadata_fitsWithinMaxBleMessageSize() {
    val se = List(OpenVoiceVoiceConverter.SE_DIM) { it * 0.0013f - 0.1f }
    val msg = BleMetadataMessage("p1", "Bob", "fr", "en", true, voiceEmbedding = se)
    val encoded = BleMessageCodec.encodeMetadata(msg)
    assertTrue(
      "256-d metadata must fit within BLE envelope ($MAX_BLE_MESSAGE_SIZE chars)",
      encoded.length <= MAX_BLE_MESSAGE_SIZE,
    )
  }

  @Test
  fun decodeMetadata_rejectsPartialEmbedding() {
    val floats = List(100) { 0.1f }
    val json =
      """{"participantId":"p1","participantName":"Bob","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true,"voiceEmbedding":[${floats.joinToString(",")}]}"""
    assertTrue(BleMessageCodec.isValidMetadataJson(json))
    val decoded = BleMessageCodec.decodeMetadata(json)
    assertNotNull(decoded)
    assertNull("partial embedding must not be accepted", decoded!!.voiceEmbedding)
  }

  @Test
  fun decodeMetadata_rejectsOversizedEmbedding() {
    val floats = List(300) { 0.1f }
    val json =
      """{"participantId":"p1","participantName":"Bob","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true,"voiceEmbedding":[${floats.joinToString(",")}]}"""
    assertTrue(BleMessageCodec.isValidMetadataJson(json))
    val decoded = BleMessageCodec.decodeMetadata(json)
    assertNotNull(decoded)
    assertNull("oversized embedding must not be accepted", decoded!!.voiceEmbedding)
  }

  // ----- BRUTALISATION -----

  // ----- Empty payload variants.
  @Test
  fun decodeTranscript_emptyPayload_returnsNull() {
    assertNull(BleMessageCodec.decodeTranscript(""))
    assertNull(BleMessageCodec.decodeTranscript("{}"))
    assertNull(BleMessageCodec.decodeTranscript("null"))
  }

  @Test
  fun decodeMetadata_emptyPayload_returnsNull() {
    assertNull(BleMessageCodec.decodeMetadata(""))
    assertNull(BleMessageCodec.decodeMetadata("{}"))
    assertNull(BleMessageCodec.decodeMetadata("null"))
  }

  // ----- Oversized payload.
  @Test
  fun decodeTranscript_oversizedPayload_handledGracefully() {
    // 9 KB > MAX_BLE_MESSAGE_SIZE (8192). The decode is called by the BLE receive path
    // AFTER the size check, so the codec itself can return null for any malformed input.
    val oversized = "{\"text\":\"${"x".repeat(9000)}\",\"senderId\":\"a\",\"senderName\":\"b\",\"sourceLanguage\":\"en\",\"targetLanguage\":\"es\"}"
    val result = BleMessageCodec.decodeTranscript(oversized)
    // Whether the codec handles 9KB or not, the BLE receive path enforces MAX_BLE_MESSAGE_SIZE.
    // Pin that the codec doesn't crash.
    // (It will likely succeed in decoding; the size check is upstream.)
    if (result != null) {
      assertEquals(9000, result.text.length)
    }
  }

  // ----- Control characters in sender name: real BLE noise. UTF-8 JSON allows it.
  @Test
  fun decodeTranscript_senderNameWithControlChars_accepted() {
    val msg = BleTranscriptMessage("hi", "s1", "Al\tice\n", "en", "es", 0L)
    val payload = BleMessageCodec.encodeTranscript(msg).drop(1)
    val decoded = BleMessageCodec.decodeTranscript(payload)
    assertNotNull(decoded)
    assertEquals("Al\tice\n", decoded!!.senderName)
  }

  // ----- UTF-8 emoji roundtrip: real multi-byte chars in sender name.
  @Test
  fun decodeTranscript_senderNameWithEmoji_accepted() {
    val msg = BleTranscriptMessage("hi", "s1", "Alice 🚀", "en", "es", 0L)
    val payload = BleMessageCodec.encodeTranscript(msg).drop(1)
    val decoded = BleMessageCodec.decodeTranscript(payload)
    assertNotNull(decoded)
    assertEquals("Alice 🚀", decoded!!.senderName)
  }

  // ----- Numeric text is preserved as a string, not coerced.
  @Test
  fun decodeTranscript_numericText_preservedAsString() {
    val msg = BleTranscriptMessage("42", "s1", "Alice", "en", "es", 0L)
    val payload = BleMessageCodec.encodeTranscript(msg).drop(1)
    val decoded = BleMessageCodec.decodeTranscript(payload)
    assertNotNull(decoded)
    assertEquals("42", decoded!!.text)
  }

  // ----- NaN floats in the embedding. JsonElement.floatOrNull returns null for NaN
  // because the kotlinx parser doesn't preserve "NaN" as a valid float literal in strict mode.
  // Verify the behavior.
  @Test
  fun decodeMetadata_voiceEmbeddingWithNaN_documentedBehavior() {
    val floats = List(256) { "NaN" }
    val json =
      """{"participantId":"p1","participantName":"Bob","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true,"voiceEmbedding":[${floats.joinToString(",")}]}"""
    val decoded = BleMessageCodec.decodeMetadata(json)
    assertNotNull(decoded)
    // Currently: the parser doesn't recognize "NaN" as a valid JSON number, so the
    // mapNotNull + floatOrNull filters it out. The resulting list size != 256, so
    // the size check rejects the whole embedding.
    assertNull("NaN entries are filtered out -> size mismatch -> rejected", decoded!!.voiceEmbedding)
  }

  // ----- Infinity floats.
  @Test
  fun decodeMetadata_voiceEmbeddingWithInf_documentedBehavior() {
    val floats = List(256) { "Infinity" }
    val json =
      """{"participantId":"p1","participantName":"Bob","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true,"voiceEmbedding":[${floats.joinToString(",")}]}"""
    val decoded = BleMessageCodec.decodeMetadata(json)
    assertNotNull(decoded)
    assertNull("Infinity entries filtered out -> rejected", decoded!!.voiceEmbedding)
  }

  // ----- Mixed-type array (string in the middle of floats).
  @Test
  fun decodeMetadata_voiceEmbeddingWithStringMixed_rejected() {
    val mixed = (0 until 255).map { "0.${it}" } + listOf("\"bad\"")
    val json =
      """{"participantId":"p1","participantName":"Bob","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true,"voiceEmbedding":[${mixed.joinToString(",")}]}"""
    val decoded = BleMessageCodec.decodeMetadata(json)
    assertNotNull(decoded)
    assertNull("mixed-type array -> rejected", decoded!!.voiceEmbedding)
  }

  // ----- Empty array (size 0).
  @Test
  fun decodeMetadata_voiceEmbeddingEmptyArray_rejected() {
    val json =
      """{"participantId":"p1","participantName":"Bob","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true,"voiceEmbedding":[]}"""
    val decoded = BleMessageCodec.decodeMetadata(json)
    assertNotNull(decoded)
    assertNull("empty array -> size mismatch -> rejected", decoded!!.voiceEmbedding)
  }

  // ----- Off-by-one: 255 floats.
  @Test
  fun decodeMetadata_voiceEmbeddingExactly255_rejected() {
    val floats = List(255) { 0.1f }
    val json =
      """{"participantId":"p1","participantName":"Bob","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true,"voiceEmbedding":[${floats.joinToString(",")}]}"""
    val decoded = BleMessageCodec.decodeMetadata(json)
    assertNotNull(decoded)
    assertNull("255 != 256 -> rejected", decoded!!.voiceEmbedding)
  }

  // ----- Off-by-one: 257 floats.
  @Test
  fun decodeMetadata_voiceEmbeddingExactly257_rejected() {
    val floats = List(257) { 0.1f }
    val json =
      """{"participantId":"p1","participantName":"Bob","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true,"voiceEmbedding":[${floats.joinToString(",")}]}"""
    val decoded = BleMessageCodec.decodeMetadata(json)
    assertNotNull(decoded)
    assertNull("257 != 256 -> rejected", decoded!!.voiceEmbedding)
  }

  // ----- Wire format: encoded transcript starts with "T" (transcript header byte).
  @Test
  fun encodeTranscript_wireFormat_startsWithT() {
    val msg = BleTranscriptMessage("hi", "s1", "A", "en", "es", 0L)
    val encoded = BleMessageCodec.encodeTranscript(msg)
    assertEquals("wire format: 'T' + JSON", 'T', encoded[0])
  }

  // ----- Wire format: encoded metadata starts with "M".
  @Test
  fun encodeMetadata_wireFormat_startsWithM() {
    val msg = BleMetadataMessage("p1", "B", "en", "es", false)
    val encoded = BleMessageCodec.encodeMetadata(msg)
    assertEquals("wire format: 'M' + JSON", 'M', encoded[0])
  }

  // ----- isValidTranscriptJson: each required key is independently checked.
  @Test
  fun isValidTranscriptJson_containsAllRequiredKeys_omittingOneFails() {
    val full =
      """{"text":"hi","senderId":"a","senderName":"b","sourceLanguage":"en","targetLanguage":"es"}"""
    assertTrue("full payload is valid", BleMessageCodec.isValidTranscriptJson(full))

    // Omit each key in turn.
    val noText = """{"senderId":"a","senderName":"b","sourceLanguage":"en","targetLanguage":"es"}"""
    assertFalse("omitting 'text' must fail", BleMessageCodec.isValidTranscriptJson(noText))

    val noSenderId = """{"text":"hi","senderName":"b","sourceLanguage":"en","targetLanguage":"es"}"""
    assertFalse("omitting 'senderId' must fail", BleMessageCodec.isValidTranscriptJson(noSenderId))

    val noSenderName = """{"text":"hi","senderId":"a","sourceLanguage":"en","targetLanguage":"es"}"""
    assertFalse("omitting 'senderName' must fail", BleMessageCodec.isValidTranscriptJson(noSenderName))

    val noSource = """{"text":"hi","senderId":"a","senderName":"b","targetLanguage":"es"}"""
    assertFalse("omitting 'sourceLanguage' must fail", BleMessageCodec.isValidTranscriptJson(noSource))

    val noTarget = """{"text":"hi","senderId":"a","senderName":"b","sourceLanguage":"en"}"""
    assertFalse("omitting 'targetLanguage' must fail", BleMessageCodec.isValidTranscriptJson(noTarget))
  }

  // ----- Unclosed brace fails the substring gate (no closing brace).
  @Test
  fun isValidTranscriptJson_unclosedBrace_returnsFalse() {
    val unclosed = """{"text":"hi","senderId":"a","senderName":"b","sourceLanguage":"en","targetLanguage":"es""""
    assertFalse("no closing brace -> false", BleMessageCodec.isValidTranscriptJson(unclosed))
  }

  // ----- Round-trip preserves an explicit timestamp byte-for-byte.
  @Test
  fun encode_decode_roundTrip_preservesTimestamp() {
    val explicit = 1234567890L
    val msg = BleTranscriptMessage("hi", "s1", "A", "en", "es", explicit)
    val payload = BleMessageCodec.encodeTranscript(msg).drop(1)
    val decoded = BleMessageCodec.decodeTranscript(payload)
    assertNotNull(decoded)
    assertEquals(explicit, decoded!!.timestamp)
  }

  // ----- Negative timestamp is preserved (some BLE peers use signed time).
  @Test
  fun decodeTranscript_negativeTimestamp_documentedBehavior() {
    val msg = BleTranscriptMessage("hi", "s1", "A", "en", "es", -1L)
    val payload = BleMessageCodec.encodeTranscript(msg).drop(1)
    val decoded = BleMessageCodec.decodeTranscript(payload)
    assertNotNull(decoded)
    // Production uses longOrNull which accepts negative. Pin.
    assertEquals(-1L, decoded!!.timestamp)
  }

  // ----- isValidMetadataJson: omit each required key in turn.
  @Test
  fun isValidMetadataJson_containsAllRequiredKeys_omittingOneFails() {
    val full =
      """{"participantId":"p1","participantName":"B","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true}"""
    assertTrue("full payload is valid", BleMessageCodec.isValidMetadataJson(full))

    val noParticipantId =
      """{"participantName":"B","sourceLanguage":"fr","targetLanguage":"en","hasVoiceProfile":true}"""
    assertFalse("omitting 'participantId' must fail", BleMessageCodec.isValidMetadataJson(noParticipantId))

    val noProfile =
      """{"participantId":"p1","participantName":"B","sourceLanguage":"fr","targetLanguage":"en"}"""
    assertFalse("omitting 'hasVoiceProfile' must fail", BleMessageCodec.isValidMetadataJson(noProfile))
  }

  // ----- encode is pure: same input -> same output.
  @Test
  fun encode_pure_deterministic() {
    val msg = BleTranscriptMessage("hi", "s1", "A", "en", "es", 100L)
    val e1 = BleMessageCodec.encodeTranscript(msg)
    val e2 = BleMessageCodec.encodeTranscript(msg)
    assertEquals(e1, e2)
  }
}
