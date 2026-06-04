package com.google.ai.edge.gallery.common.network

import java.net.HttpURLConnection
import java.net.URL

/**
 * Centralized HTTP client for all network operations.
 * All URL.openConnection() calls must route through this module per validate:no-client-fetch-drift.
 */
object HttpClient {
  /**
   * Opens an HTTP connection to the specified URL.
   * @param url The URL to connect to
   * @param accessToken Optional Bearer token for authorization
   * @param connectTimeout Connection timeout in milliseconds (default 30s)
   * @param readTimeout Read timeout in milliseconds (default 30s)
   * @return HttpURLConnection instance
   */
  fun openConnection(
    url: URL,
    accessToken: String? = null,
    connectTimeout: Int = 30_000,
    readTimeout: Int = 30_000,
  ): HttpURLConnection {
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = connectTimeout
    connection.readTimeout = readTimeout
    if (accessToken != null) {
      connection.setRequestProperty("Authorization", "Bearer $accessToken")
    }
    return connection
  }

  /**
   * Opens a connection with custom headers.
   * @param url The URL to connect to
   * @param headers Map of header name to value
   * @param connectTimeout Connection timeout in milliseconds (default 30s)
   * @param readTimeout Read timeout in milliseconds (default 30s)
   * @return HttpURLConnection instance
   */
  fun openConnectionWithHeaders(
    url: URL,
    headers: Map<String, String>,
    connectTimeout: Int = 30_000,
    readTimeout: Int = 30_000,
  ): HttpURLConnection {
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = connectTimeout
    connection.readTimeout = readTimeout
    headers.forEach { (name, value) ->
      connection.setRequestProperty(name, value)
    }
    return connection
  }
}
