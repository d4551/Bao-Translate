package com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the remote-DoS fix: a connected BLE peer (or link corruption) can send bytes that pass the
 * substring validators but are not parseable JSON. Decoding must return null, never throw — a throw
 * would propagate out of the library's main-thread callback and crash the process.
 */
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
  }
}
