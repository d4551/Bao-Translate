/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.libredrop.discovery.DiscoveredService
import com.google.ai.edge.gallery.customtasks.libredrop.discovery.Discovery
import com.google.ai.edge.gallery.customtasks.libredrop.discovery.DiscoveryEvent
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.FileSource
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundConnection
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundConnectionState
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundResult
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "LibreDropSender"

/**
 * Minimal sender orchestrator that wires mDNS [Discovery] to [OutboundConnection].
 *
 * This is the long-missing outbound half of LibreDrop. The receiver side is mature
 * ([ReceiverForegroundService]); until now the send button in [LibreDropScreen] was a no-op.
 * This ViewModel closes the loop:
 *
 *  1. [startDiscovery] collects [Discovery.browse] and mirrors peers into [peers].
 *  2. [send] builds an [OutboundConnection] from the peer's LAN address+port, feeds it the
 *     caller's [FileSource] list, and drives [OutboundConnection.run] to completion while
 *     mirroring [OutboundConnectionState] into [transfers] as [TransferStatus].
 *
 * ### Scope intentionally narrow
 *
 * Phase-1 sender covers the Wi-Fi LAN path (the receiver's primary medium). Wi-Fi Direct /
 * hotspot / Bluetooth bootstraps live in [Discovery] and [MediumRegistry] but are not wired
 * here — those require bandwidth-upgrade negotiation the receiver already runs; a future
 * iteration can pass a richer [OutboundConnection.mediumRegistry] once the sender-side medium
 * probe is in place.
 *
 * ### Concurrency
 *
 * One outbound connection at a time per peer. Concurrent sends to distinct peers are allowed
 * (each gets its own [OutboundConnection] + coroutine). [OutboundConnection.run] is
 * single-use by contract, so every [send] call builds a fresh instance.
 *
 * ### Why not foreground service on send
 *
 * Sending is user-initiated and short-lived (the screen is in the foreground when the user
 * taps send). The receiver needs a foreground service because it must stay reachable while
 * the app is backgrounded; the sender does not.
 */
@HiltViewModel
class LibreDropSenderViewModel
@Inject
constructor(@ApplicationContext private val appContext: Context) : ViewModel() {

    /** Discovered peers keyed for stable rendering. Emits immutable snapshots. */
    private val _peers = MutableStateFlow<List<DiscoveredService>>(emptyList())
    val peers: StateFlow<List<DiscoveredService>> = _peers.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferStatus>>(emptyList())
    val transfers: StateFlow<List<TransferStatus>> = _transfers.asStateFlow()

    private var discoveryJob: Job? = null

    private val sendExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            BaoLog.e(TAG, "Send coroutine crashed", throwable)
            val reason = appContext.getString(R.string.libre_drop_error_send_failed)
            _transfers.update { list ->
                list.map {
                    if (it.state == TransferState.CONNECTING)
                        it.copy(state = TransferState.FAILED, failureReason = reason)
                    else it
                }
            }
        }

    /** Begin mDNS browse. Idempotent — a second call while already browsing is a no-op. */
    fun startDiscovery() {
        if (discoveryJob != null && discoveryJob?.isActive == true) return
        _peers.value = emptyList()
        _isDiscovering.value = true
        discoveryJob =
            viewModelScope.launch {
                val discovery = Discovery(appContext.applicationContext)
                discovery.browse().catch { e ->
                    BaoLog.e(TAG, "Discovery stream error", e)
                    _isDiscovering.value = false
                }.collect { event ->
                    when (event) {
                        is DiscoveryEvent.Resolved -> {
                            val svc = event.service
                            _peers.update { existing ->
                                val without = existing.filterNot { it.instanceName == svc.instanceName }
                                without + svc
                            }
                        }
                        is DiscoveryEvent.Lost -> {
                            _peers.update { existing ->
                                existing.filterNot { it.instanceName == event.instanceName }
                            }
                        }
                    }
                }
            }
    }

    /** Stop mDNS browse. Clears the peer list so stale entries don't linger. */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _isDiscovering.value = false
        _peers.value = emptyList()
    }

    /**
     * Send [files] to [peer]. Appends a [TransferStatus] to [transfers] and updates it as the
     * [OutboundConnection] progresses. Each file in [files] is sent in a single connection
     * batch (the protocol supports multi-file introductions).
     *
     * Does NOT throw — connection failures are mapped to [TransferState.FAILED] with a reason
     * string carried in [TransferStatus.state].
     */
    fun send(peer: DiscoveredService, files: List<FileSource>) {
        val address =
            peer.primaryAddress()
                ?: run {
                    appendFailedTransfer(peer, files, appContext.getString(R.string.libre_drop_error_no_address))
                    return
                }
        viewModelScope.launch(sendExceptionHandler) {
            val endpointInfoBytes = peer.endpointInfo?.serialize() ?: ByteArray(0)
            val connection =
                OutboundConnection(
                    targetAddress = address,
                    port = peer.port,
                    endpointInfo = endpointInfoBytes,
                )
            val transferId = System.nanoTime()
            appendTransfer(
                TransferStatus(
                    peerName = peer.instanceName.take(8),
                    fileName = files.joinToString(", ") { it.name },
                    progress = 0f,
                    state = TransferState.CONNECTING,
                    id = transferId,
                )
            )
            val stateJob =
                viewModelScope.launch {
                    connection.state.collect { st ->
                        updateTransfer(transferId) { current ->
                            current.copy(
                                state = toUiState(st),
                                progress = toProgress(st),
                                failureReason = failureReasonFrom(st) ?: current.failureReason,
                            )
                        }
                    }
                }
            val result = connection.run(files)
            updateTransfer(transferId) { current ->
                current.copy(
                    state = toTerminalState(result),
                    progress = toTerminalProgress(result),
                    failureReason = failureReasonFrom(result) ?: current.failureReason,
                )
            }
            stateJob.cancel()
        }
    }

    override fun onCleared() {
        stopDiscovery()
    }

    // -- transfer list helpers --

    private fun appendTransfer(status: TransferStatus) {
        _transfers.update { it + status }
    }

    private fun appendFailedTransfer(peer: DiscoveredService, files: List<FileSource>, reason: String) {
        appendTransfer(
            TransferStatus(
                peerName = peer.instanceName.take(8),
                fileName = files.joinToString(", ") { it.name },
                progress = 0f,
                state = TransferState.FAILED,
                id = System.nanoTime(),
                failureReason = reason,
            )
        )
    }

    private fun updateTransfer(id: Long, transform: (TransferStatus) -> TransferStatus) {
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun toUiState(state: OutboundConnectionState): TransferState =
        mapConnectionStateToUi(state)

    private fun toProgress(state: OutboundConnectionState): Float =
        mapConnectionStateToProgress(state)

    private fun toTerminalState(result: OutboundResult): TransferState =
        mapResultToTerminalState(result)

    private fun toTerminalProgress(result: OutboundResult): Float =
        mapResultToTerminalProgress(result)

    private fun failureReasonFrom(state: OutboundConnectionState): String? =
        failureReasonFromState(appContext, state)

    private fun failureReasonFrom(result: OutboundResult): String? =
        failureReasonFromResult(appContext, result)
}

internal fun mapConnectionStateToUi(state: OutboundConnectionState): TransferState =
    when (state) {
        is OutboundConnectionState.Idle,
        is OutboundConnectionState.Connecting,
        is OutboundConnectionState.Handshaking -> TransferState.CONNECTING
        is OutboundConnectionState.AwaitingRemoteAcceptance -> TransferState.CONNECTING
        is OutboundConnectionState.Sending -> TransferState.TRANSFERRING
        is OutboundConnectionState.Completed -> TransferState.COMPLETE
        is OutboundConnectionState.Rejected,
        is OutboundConnectionState.Failed -> TransferState.FAILED
        is OutboundConnectionState.Cancelled -> TransferState.FAILED
    }

internal fun mapConnectionStateToProgress(state: OutboundConnectionState): Float =
    when (state) {
        is OutboundConnectionState.Sending -> state.progress.fraction.toFloat()
        is OutboundConnectionState.Completed -> 1f
        else -> 0f
    }

internal fun mapResultToTerminalState(result: OutboundResult): TransferState =
    when (result) {
        is OutboundResult.Completed -> TransferState.COMPLETE
        is OutboundResult.Rejected,
        is OutboundResult.Failed,
        is OutboundResult.Cancelled -> TransferState.FAILED
    }

internal fun mapResultToTerminalProgress(result: OutboundResult): Float =
    if (result is OutboundResult.Completed) 1f else 0f

internal fun failureReasonFromState(context: Context, state: OutboundConnectionState): String? =
    when (state) {
        is OutboundConnectionState.Failed -> state.reason
        is OutboundConnectionState.Rejected -> context.getString(R.string.libre_drop_error_rejected, state.status)
        is OutboundConnectionState.Cancelled -> context.getString(R.string.libre_drop_error_cancelled, state.cause)
        else -> null
    }

internal fun failureReasonFromResult(context: Context, result: OutboundResult): String? =
    when (result) {
        is OutboundResult.Failed -> result.reason
        is OutboundResult.Rejected -> context.getString(R.string.libre_drop_error_rejected, result.status)
        is OutboundResult.Cancelled -> context.getString(R.string.libre_drop_error_cancelled, result.cause)
        is OutboundResult.Completed -> null
    }
