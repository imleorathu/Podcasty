/*
 * Podcasty — Android Podcast & Music Player
 * Licensed under GNU GPL v3.0.
 */
package com.podcasty.app.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UsbVolumeSummary(
    val description: String,
    val isRemovable: Boolean,
    val state: String,
)

sealed interface UsbStorageEvent {
    data class Attached(val deviceName: String) : UsbStorageEvent
    data class Detached(val deviceName: String) : UsbStorageEvent
}

/**
 * Monitors USB OTG storage attach/detach and external volume mount/unmount events.
 * Allows Podcasty to safely stop playback when a USB audio source is removed and
 * automatically refresh local folders when a USB drive reconnects.
 */
class UsbStorageMonitor(private val context: Context) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<UsbStorageEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UsbStorageEvent> = _events.asSharedFlow()

    private val _removableVolumes = MutableStateFlow<List<UsbVolumeSummary>>(emptyList())
    val removableVolumes: StateFlow<List<UsbVolumeSummary>> = _removableVolumes.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            ioScope.launch { refreshVolumes() }
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, Intent.ACTION_MEDIA_MOUNTED -> {
                    val label = extractDeviceLabel(intent) ?: "USB storage device"
                    _events.tryEmit(UsbStorageEvent.Attached(label))
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                Intent.ACTION_MEDIA_UNMOUNTED,
                Intent.ACTION_MEDIA_EJECT,
                Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_BAD_REMOVAL,
                -> {
                    val label = extractDeviceLabel(intent) ?: "USB storage device"
                    _events.tryEmit(UsbStorageEvent.Detached(label))
                }
            }
        }
    }

    fun start() {
        if (registered) return
        ioScope.launch { refreshVolumes() }
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, usbFilter, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, receiver, mediaFilter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun refreshVolumes() {
        val manager = context.getSystemService(StorageManager::class.java)
        val volumes = runCatching {
            manager?.storageVolumes?.map { vol ->
                UsbVolumeSummary(
                    description = vol.getDescription(context) ?: "Storage volume",
                    isRemovable = vol.isRemovable,
                    state = vol.state,
                )
            }.orEmpty()
        }.getOrDefault(emptyList())
        _removableVolumes.value = volumes
    }

    private fun extractDeviceLabel(intent: Intent): String? {
        val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        return usbDevice?.productName?.takeIf { it.isNotBlank() } ?: intent.data?.lastPathSegment
    }
}
