/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop.discovery.wifi.hotspot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.medium.Medium
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.medium.MediumRegistry

/**
 * Convenience wiring for [WifiHotspotMediumProvider] in Android apps.
 *
 * App-side glue (`:app`, `:service-android`) creates the provider with:
 *
 * ```kotlin
 * val hotspotProvider = WifiHotspotMediumProviderFactory.create(applicationContext)
 * val registry = MediumRegistry(listOf(WifiLanDefaultProvider, hotspotProvider))
 * ```
 *
     * On devices without Wi-Fi hardware it still returns a
     * [WifiHotspotMediumProvider] that reports `isSupported() == false`,
     * and the framework treats it as an unavailable rung in the ladder.
 *
 * The Phase 4 #54 orchestrator ("upgrade hook") is the planned single
 * entry point for installing the resulting registry into
 * [com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundConnection] /
 * [com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.InboundConnection];
 * until it lands the registry can still be exercised through tests
 * and through manual on-device runs.
 */
public object WifiHotspotMediumProviderFactory {
    /**
     * Build a [WifiHotspotMediumProvider] for [context].
     */
    public fun create(context: Context): WifiHotspotMediumProvider {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            return WifiHotspotMediumProvider()
        }
        val controller = AndroidLocalOnlyHotspotController(appContext)
        val client = AndroidWifiNetworkSpecifierClient(appContext)
        return WifiHotspotMediumProvider(
            controller = controller,
            client = client,
            available = { hasRequiredPermissions(appContext) },
        )
    }

    /**
     * Convenience: build a [MediumRegistry] containing the project's
     * default Wi-Fi LAN provider plus this medium's provider. Apps
     * that want a different mix should build their own registry
     * directly.
     */
    public fun registryWithDefaults(context: Context): MediumRegistry {
        val hotspot = create(context)
        // The project's MediumRegistry.DefaultWifiLan companion object
        // owns the trivial Wi-Fi LAN provider; pull it back out by
        // grabbing the registered provider for Wi-Fi LAN. This keeps
        // the LAN default in lockstep with whatever :core-protocol
        // ships, even if its internals change.
        val lanProvider =
            requireNotNull(MediumRegistry.DefaultWifiLan.providerFor(Medium.WIFI_LAN)) {
                "MediumRegistry.DefaultWifiLan must register a Wi-Fi LAN provider."
            }
        return MediumRegistry(listOf(lanProvider, hotspot))
    }

    @Suppress("ReturnCount") // One early return per granted-permission path keeps the gate explicit.
    private fun hasRequiredPermissions(context: Context): Boolean {
        val fineLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (fineLocation) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby =
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
            if (nearby) return true
        }
        return false
    }
}
