/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop

import android.content.Context
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.CancelCause
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundConnectionState
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundResult
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.TransferProgress
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.sharing.ConnectionResponseStatus
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Category(Strict::class)
class LibreDropSenderViewModelMappingTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = mock()
    whenever(context.getString(eq(R.string.libre_drop_error_rejected), any())).thenAnswer { inv ->
      "Rejected: ${inv.arguments[1]}"
    }
    whenever(context.getString(eq(R.string.libre_drop_error_cancelled), any())).thenAnswer { inv ->
      "Cancelled: ${inv.arguments[1]}"
    }
  }

  @Test
  fun `mapConnectionStateToUi maps Idle to CONNECTING`() {
    assertEquals(TransferState.CONNECTING, mapConnectionStateToUi(OutboundConnectionState.Idle))
  }

  @Test
  fun `mapConnectionStateToUi maps Connecting to CONNECTING`() {
    assertEquals(TransferState.CONNECTING, mapConnectionStateToUi(OutboundConnectionState.Connecting))
  }

  @Test
  fun `mapConnectionStateToUi maps Handshaking to CONNECTING`() {
    assertEquals(TransferState.CONNECTING, mapConnectionStateToUi(OutboundConnectionState.Handshaking))
  }

  @Test
  fun `mapConnectionStateToUi maps AwaitingRemoteAcceptance to CONNECTING`() {
    val state = OutboundConnectionState.AwaitingRemoteAcceptance(pin = "1234")
    assertEquals(TransferState.CONNECTING, mapConnectionStateToUi(state))
  }

  @Test
  fun `mapConnectionStateToUi maps Sending to TRANSFERRING`() {
    val progress = TransferProgress(bytesTransferred = 500L, totalSize = 1000L, bytesPerSecond = 100L, etaSeconds = 5L)
    val state = OutboundConnectionState.Sending(pin = "1234", progress = progress, currentItemPayloadId = 1L)
    assertEquals(TransferState.TRANSFERRING, mapConnectionStateToUi(state))
  }

  @Test
  fun `mapConnectionStateToUi maps Completed to COMPLETE`() {
    assertEquals(TransferState.COMPLETE, mapConnectionStateToUi(OutboundConnectionState.Completed))
  }

  @Test
  fun `mapConnectionStateToUi maps Rejected to FAILED`() {
    val state = OutboundConnectionState.Rejected(status = ConnectionResponseStatus.REJECT)
    assertEquals(TransferState.FAILED, mapConnectionStateToUi(state))
  }

  @Test
  fun `mapConnectionStateToUi maps Failed to FAILED`() {
    val state = OutboundConnectionState.Failed(reason = "Network error")
    assertEquals(TransferState.FAILED, mapConnectionStateToUi(state))
  }

  @Test
  fun `mapConnectionStateToUi maps Cancelled to FAILED`() {
    val state = OutboundConnectionState.Cancelled(cause = CancelCause.LOCAL)
    assertEquals(TransferState.FAILED, mapConnectionStateToUi(state))
  }

  @Test
  fun `mapConnectionStateToProgress returns zero for Idle`() {
    assertEquals(0f, mapConnectionStateToProgress(OutboundConnectionState.Idle))
  }

  @Test
  fun `mapConnectionStateToProgress returns fraction for Sending`() {
    val progress = TransferProgress(bytesTransferred = 250L, totalSize = 1000L, bytesPerSecond = 0L, etaSeconds = null)
    val state = OutboundConnectionState.Sending(pin = "1234", progress = progress, currentItemPayloadId = 1L)
    assertEquals(0.25f, mapConnectionStateToProgress(state), 0.001f)
  }

  @Test
  fun `mapConnectionStateToProgress returns one for Completed`() {
    assertEquals(1f, mapConnectionStateToProgress(OutboundConnectionState.Completed))
  }

  @Test
  fun `mapConnectionStateToProgress returns zero for Failed`() {
    val state = OutboundConnectionState.Failed(reason = "error")
    assertEquals(0f, mapConnectionStateToProgress(state))
  }

  @Test
  fun `mapResultToTerminalState maps Completed to COMPLETE`() {
    assertEquals(TransferState.COMPLETE, mapResultToTerminalState(OutboundResult.Completed))
  }

  @Test
  fun `mapResultToTerminalState maps Rejected to FAILED`() {
    val result = OutboundResult.Rejected(status = ConnectionResponseStatus.REJECT)
    assertEquals(TransferState.FAILED, mapResultToTerminalState(result))
  }

  @Test
  fun `mapResultToTerminalState maps Failed to FAILED`() {
    val result = OutboundResult.Failed(reason = "timeout")
    assertEquals(TransferState.FAILED, mapResultToTerminalState(result))
  }

  @Test
  fun `mapResultToTerminalState maps Cancelled to FAILED`() {
    val result = OutboundResult.Cancelled(cause = CancelCause.PEER)
    assertEquals(TransferState.FAILED, mapResultToTerminalState(result))
  }

  @Test
  fun `mapResultToTerminalProgress returns one for Completed`() {
    assertEquals(1f, mapResultToTerminalProgress(OutboundResult.Completed))
  }

  @Test
  fun `mapResultToTerminalProgress returns zero for Failed`() {
    val result = OutboundResult.Failed(reason = "error")
    assertEquals(0f, mapResultToTerminalProgress(result))
  }

  @Test
  fun `mapResultToTerminalProgress returns zero for Rejected`() {
    val result = OutboundResult.Rejected(status = ConnectionResponseStatus.REJECT)
    assertEquals(0f, mapResultToTerminalProgress(result))
  }

  @Test
  fun `mapResultToTerminalProgress returns zero for Cancelled`() {
    val result = OutboundResult.Cancelled(cause = CancelCause.LOCAL)
    assertEquals(0f, mapResultToTerminalProgress(result))
  }

  @Test
  fun `failureReasonFromState returns reason for Failed`() {
    val state = OutboundConnectionState.Failed(reason = "UKEY2 error")
    assertEquals("UKEY2 error", failureReasonFromState(context, state))
  }

  @Test
  fun `failureReasonFromState returns formatted string for Rejected`() {
    val state = OutboundConnectionState.Rejected(status = ConnectionResponseStatus.NOT_ENOUGH_SPACE)
    val reason = failureReasonFromState(context, state)
    assertEquals("Rejected: NOT_ENOUGH_SPACE", reason)
  }

  @Test
  fun `failureReasonFromState returns formatted string for Cancelled`() {
    val state = OutboundConnectionState.Cancelled(cause = CancelCause.PEER)
    val reason = failureReasonFromState(context, state)
    assertEquals("Cancelled: PEER", reason)
  }

  @Test
  fun `failureReasonFromState returns null for non-terminal states`() {
    assertNull(failureReasonFromState(context, OutboundConnectionState.Idle))
    assertNull(failureReasonFromState(context, OutboundConnectionState.Connecting))
    assertNull(failureReasonFromState(context, OutboundConnectionState.Handshaking))
    assertNull(failureReasonFromState(context, OutboundConnectionState.Completed))
  }

  @Test
  fun `failureReasonFromResult returns reason for Failed`() {
    val result = OutboundResult.Failed(reason = "Connection reset")
    assertEquals("Connection reset", failureReasonFromResult(context, result))
  }

  @Test
  fun `failureReasonFromResult returns formatted string for Rejected`() {
    val result = OutboundResult.Rejected(status = ConnectionResponseStatus.TIMED_OUT)
    assertEquals("Rejected: TIMED_OUT", failureReasonFromResult(context, result))
  }

  @Test
  fun `failureReasonFromResult returns formatted string for Cancelled`() {
    val result = OutboundResult.Cancelled(cause = CancelCause.LOCAL)
    assertEquals("Cancelled: LOCAL", failureReasonFromResult(context, result))
  }

  @Test
  fun `failureReasonFromResult returns null for Completed`() {
    assertNull(failureReasonFromResult(context, OutboundResult.Completed))
  }
}
