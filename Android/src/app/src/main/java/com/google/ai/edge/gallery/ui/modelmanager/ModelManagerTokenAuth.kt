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

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.activity.result.ActivityResult
import androidx.core.net.toUri
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.proto.AccessTokenData
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues

private const val TAG = "AGModelManagerToken"
private const val TOKEN_EXPIRY_BUFFER_MIN = 5L

enum class TokenStatus { NOT_STORED, EXPIRED, NOT_EXPIRED }

enum class TokenRequestResultType { FAILED, SUCCEEDED, USER_CANCELLED }

data class TokenStatusAndData(val status: TokenStatus, val data: AccessTokenData?)

data class TokenRequestResult(
  val status: TokenRequestResultType,
  val errorMessage: String? = null,
)

/**
 * Returns the current OAuth access-token status. The token is considered expired once the
 * expiration timestamp passes the [TOKEN_EXPIRY_BUFFER_MIN]-minute safety margin.
 */
suspend fun ModelManagerViewModel.getTokenStatusAndData(): TokenStatusAndData {
  var tokenStatus = TokenStatus.NOT_STORED
  BaoLog.d(TAG, "Reading token data from data store...")
  val tokenData = dataStoreRepository.readAccessTokenData()
  if (tokenData != null && tokenData.accessToken.isNotEmpty()) {
    BaoLog.d(TAG, "Token exists and loaded.")
    val curTs = System.currentTimeMillis()
    val expirationTs = tokenData.expiresAtMs - TOKEN_EXPIRY_BUFFER_MIN * 60 * 1000
    if (curTs >= expirationTs) {
      BaoLog.d(TAG, "Token expired!")
      tokenStatus = TokenStatus.EXPIRED
    } else {
      BaoLog.d(TAG, "Token not expired.")
      tokenStatus = TokenStatus.NOT_EXPIRED
      curAccessToken = tokenData.accessToken
    }
  } else {
    BaoLog.d(TAG, "Token does not exist.")
  }
  return TokenStatusAndData(status = tokenStatus, data = tokenData)
}

/** Builds a fresh OAuth authorization request for this model manager. */
fun ModelManagerViewModel.getAuthorizationRequest(): AuthorizationRequest? {
  val serviceConfig = ProjectConfig.authServiceConfig ?: return null
  return AuthorizationRequest.Builder(
      serviceConfig,
      ProjectConfig.clientId,
      ResponseTypeValues.CODE,
      ProjectConfig.redirectUri.toUri(),
    )
    .setScope("read-repos")
    .build()
}

/**
 * Handles the result returned by the OAuth flow. Persists a successful exchange or reports a
 * failure or user cancellation to [onTokenRequested].
 */
fun ModelManagerViewModel.handleAuthResult(
  result: ActivityResult,
  onTokenRequested: (TokenRequestResult) -> Unit,
) {
  val dataIntent = result.data
  if (dataIntent == null) {
    onTokenRequested(
      TokenRequestResult(
        status = TokenRequestResultType.FAILED,
        errorMessage = "Empty auth result",
      )
    )
    return
  }
  val response = AuthorizationResponse.fromIntent(dataIntent)
  val exception = AuthorizationException.fromIntent(dataIntent)
  when {
    response?.authorizationCode != null ->
      exchangeCode(this, authService, response, onTokenRequested)
    exception != null ->
      onTokenRequested(
        TokenRequestResult(
          status =
            if (exception.message == "User cancelled flow") {
              TokenRequestResultType.USER_CANCELLED
            } else {
              TokenRequestResultType.FAILED
            },
          errorMessage = exception.message,
        ),
      )
    else ->
      onTokenRequested(TokenRequestResult(status = TokenRequestResultType.USER_CANCELLED))
  }
}

private fun exchangeCode(
  vm: ModelManagerViewModel,
  service: AuthorizationService,
  response: AuthorizationResponse,
  onTokenRequested: (TokenRequestResult) -> Unit,
) {
  var errorMessage: String? = null
  service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenEx ->
    when {
      tokenResponse == null ->
        errorMessage = "Token exchange failed: ${tokenEx?.message ?: "unknown error"}"
      tokenResponse.accessToken == null -> errorMessage = "Empty access token"
      tokenResponse.refreshToken == null -> errorMessage = "Empty refresh token"
      tokenResponse.accessTokenExpirationTime == null -> errorMessage = "Empty expiration time"
      else -> {
        val accessToken = tokenResponse.accessToken
        val refreshToken = tokenResponse.refreshToken
        val expiresAt = tokenResponse.accessTokenExpirationTime
        if (accessToken != null && refreshToken != null && expiresAt != null) {
          BaoLog.d(TAG, "Token exchange successful. Storing tokens...")
          vm.saveAccessToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
          )
          vm.curAccessToken = accessToken
          BaoLog.d(TAG, "Token successfully saved.")
        } else {
          errorMessage = "Token response missing required fields"
        }
      }
    }
    onTokenRequested(
      if (errorMessage == null) {
        TokenRequestResult(status = TokenRequestResultType.SUCCEEDED)
      } else {
        TokenRequestResult(status = TokenRequestResultType.FAILED, errorMessage = errorMessage)
      },
    )
  }
}
