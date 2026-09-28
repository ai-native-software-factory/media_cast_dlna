package br.com.felnanuke2.media_cast_dlna

import DlnaDevice
import DiscoveryEventsFlutterApi
import DeviceUdn
import android.util.Log
import android.os.Handler
import android.os.Looper
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.registry.Registry
import org.jupnp.registry.RegistryListener
import java.lang.Exception

/**
 * RegistryListener implementation for handling UPnP device discovery events.
 * This class manages device discovery callbacks and communicates with Flutter
 * through the provided MediaCastDlnaApi and DeviceDiscoveryApi instances.
 *
 * IMPORTANT: All Flutter API calls must be executed on the main UI thread.
 * The UPnP registry callbacks are executed on background threads (e.g., jupnp-4),
 * so this class uses a Handler to post all Flutter API calls to the main thread
 * to avoid the "Methods marked with @UiThread must be executed on the main thread" error.
 *
 * IMPORTANT: jUPnP only notifies registry listeners for the *root* device that was
 * actually registered. Embedded devices (e.g. a MediaRenderer nested inside a vendor
 * wrapper device) never generate their own [RegistryListener] callbacks. Therefore every
 * callback below walks the full embedded-device graph of the reported root and applies the
 * media filter to each device individually, so nested renderers are discovered/removed
 * even when the root itself is not a media device.
 */
class UpnpRegistryListener(
    private val discoveryEventsFlutterApi: DiscoveryEventsFlutterApi? = null,
) : RegistryListener {

    private enum class DiscoveryTargetFilter {
        ALL_MEDIA,
        MEDIA_RENDERER,
        MEDIA_SERVER
    }

    private val _devices = mutableListOf<DlnaDevice>()
    val devices: List<DlnaDevice> get() = _devices
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Maps a discovered device UDN to the UDN of the root device whose embedded graph
     * produced it. Used to remove every descendant when its owning root goes away,
     * independent of the media filter that was active when the descendant was discovered.
     */
    private val ownerRootByUdn = mutableMapOf<String, String>()

    @Volatile
    private var activeFilter: DiscoveryTargetFilter = DiscoveryTargetFilter.ALL_MEDIA

    companion object {
        // Device types that we're interested in for media casting
        private val MEDIA_DEVICE_TYPES = setOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:device:MediaRenderer:2",
            "urn:schemas-upnp-org:device:MediaRenderer:3",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:device:MediaServer:2",
            "urn:schemas-upnp-org:device:MediaServer:3",
            "urn:schemas-upnp-org:device:MediaServer:4"
        )

        // Device type prefixes to filter for media devices
        private val MEDIA_DEVICE_PREFIXES = setOf(
            "MediaRenderer", "MediaServer"
        )

        // Devices to exclude (like Internet Gateway Devices)
        private val EXCLUDED_DEVICE_PREFIXES = setOf(
            "InternetGatewayDevice", "WANDevice", "LANDevice", "WFADevice"
        )
    }

    fun setSearchTarget(searchTarget: String?) {
        activeFilter = when {
            searchTarget.isNullOrBlank() -> DiscoveryTargetFilter.ALL_MEDIA
            searchTarget.contains("MediaRenderer", ignoreCase = true) -> DiscoveryTargetFilter.MEDIA_RENDERER
            searchTarget.contains("MediaServer", ignoreCase = true) -> DiscoveryTargetFilter.MEDIA_SERVER
            else -> DiscoveryTargetFilter.ALL_MEDIA
        }
    }

    fun clearDiscoveredDevices() {
        val removedDevices = _devices.toList()
        _devices.clear()
        ownerRootByUdn.clear()

        removedDevices.forEach { device ->
            notifyDeviceLost(device.udn)
        }
    }

    private fun notifyDeviceFound(device: DlnaDevice) {
        mainHandler.post {
            discoveryEventsFlutterApi?.onDeviceFound(device) { result ->
                result.exceptionOrNull()?.let { error ->
                    Log.w("UpnpRegistryListener", "Failed to send onDeviceFound callback", error)
                }
            }
        }
    }

    private fun notifyDeviceLost(deviceUdn: DeviceUdn) {
        mainHandler.post {
            discoveryEventsFlutterApi?.onDeviceLost(deviceUdn) { result ->
                result.exceptionOrNull()?.let { error ->
                    Log.w("UpnpRegistryListener", "Failed to send onDeviceLost callback", error)
                }
            }
        }
    }

    private fun notifyRendererOffline(deviceUdn: DeviceUdn) {
        mainHandler.post {
            discoveryEventsFlutterApi?.onRendererOffline(deviceUdn) { result ->
                result.exceptionOrNull()?.let { error ->
                    Log.w("UpnpRegistryListener", "Failed to send onRendererOffline callback", error)
                }
            }
        }
    }

    private fun matchesActiveSearchTarget(deviceType: String): Boolean {
        return when (activeFilter) {
            DiscoveryTargetFilter.ALL_MEDIA -> {
                MEDIA_DEVICE_PREFIXES.any { deviceType.contains(it, ignoreCase = true) }
            }

            DiscoveryTargetFilter.MEDIA_RENDERER -> {
                deviceType.contains("MediaRenderer", ignoreCase = true)
            }

            DiscoveryTargetFilter.MEDIA_SERVER -> {
                deviceType.contains("MediaServer", ignoreCase = true)
            }
        }
    }

    private fun isRendererType(deviceType: String): Boolean {
        return deviceType.contains("MediaRenderer", ignoreCase = true)
    }

    /**
     * Check if a device is a media device (MediaRenderer or MediaServer) matching the
     * currently active filter.
     */
    private fun isMediaDevice(device: Device<*, *, *>): Boolean {
        val deviceType = device.type.toString()

        // First check if it's explicitly excluded
        if (EXCLUDED_DEVICE_PREFIXES.any { deviceType.contains(it, ignoreCase = true) }) {
            return false
        }

        // Check for exact match with known media device types
        if (MEDIA_DEVICE_TYPES.contains(deviceType)) {
            return matchesActiveSearchTarget(deviceType)
        }

        // Check for partial match with media device prefixes
        val isMediaDevice = MEDIA_DEVICE_PREFIXES.any { deviceType.contains(it, ignoreCase = true) }
        return isMediaDevice && matchesActiveSearchTarget(deviceType)
    }

    // Traverse wrappers too: the media filter applies to each device, not its subtree.
    private fun visitMediaDevices(
        device: Device<*, *, *>,
        publish: (Device<*, *, *>) -> Unit
    ) {
        if (isMediaDevice(device)) publish(device)
        device.embeddedDevices?.forEach { visitMediaDevices(it, publish) }
    }

    /**
     * Returns the UDN of the device and all of its embedded descendants, regardless of
     * the active media filter. Used to detect devices that disappeared from an updated
     * graph without re-applying a (possibly changed) filter.
     */
    private fun collectAllDeviceUdns(root: Device<*, *, *>): Set<String> {
        val udns = mutableSetOf<String>()

        fun visit(current: Device<*, *, *>) {
            udns.add(current.identity.udn.identifierString)
            val embedded = current.embeddedDevices
            if (embedded != null) {
                embedded.forEach { child -> visit(child) }
            }
        }

        visit(root)
        return udns
    }

    private fun Device<*, *, *>.toDlnaDeviceOrNull(): DlnaDevice? {
        return when (this) {
            is RemoteDevice -> toDlnaDevice()
            is LocalDevice -> toDlnaDevice()
            else -> null
        }
    }

    /**
     * Adds a discovered device if its UDN is not already present (dedup by UDN across
     * roots), and records its owning root for later removal.
     */
    private fun addDiscoveredDevice(device: Device<*, *, *>, ownerRootUdn: String) {
        val dlnaDevice = device.toDlnaDeviceOrNull() ?: return
        ownerRootByUdn[dlnaDevice.udn.value] = ownerRootUdn

        if (_devices.any { it.udn == dlnaDevice.udn }) {
            return
        }

        _devices.add(dlnaDevice)
        notifyDeviceFound(dlnaDevice)
    }

    /**
     * Refreshes the stored snapshot for a device, adding and announcing it if it was not
     * previously known.
     */
    private fun updateDiscoveredDevice(device: Device<*, *, *>, ownerRootUdn: String) {
        val dlnaDevice = device.toDlnaDeviceOrNull() ?: return
        val index = _devices.indexOfFirst { it.udn == dlnaDevice.udn }
        if (index != -1) {
            _devices[index] = dlnaDevice
        } else {
            _devices.add(dlnaDevice)
            notifyDeviceFound(dlnaDevice)
        }
        ownerRootByUdn[dlnaDevice.udn.value] = ownerRootUdn
    }

    /**
     * Removes every discovered device owned by [ownerRootUdn] (including the root itself),
     * emitting device-lost and, for renderers, renderer-offline events. The active media
     * filter is deliberately not consulted so entries discovered under a previous filter
     * are still removed correctly.
     */
    private fun removeOwnedDevices(ownerRootUdn: String) {
        val iterator = _devices.iterator()
        while (iterator.hasNext()) {
            val device = iterator.next()
            if (ownerRootByUdn[device.udn.value] != ownerRootUdn) {
                continue
            }
            iterator.remove()
            ownerRootByUdn.remove(device.udn.value)
            notifyDeviceLost(device.udn)
            if (isRendererType(device.deviceType)) {
                notifyRendererOffline(device.udn)
            }
        }
    }

    /**
     * Removes owned devices that are no longer present in an updated device graph.
     * Uses the filter-independent UDN set so filter changes do not cause spurious removals.
     */
    private fun pruneOwnedDevices(ownerRootUdn: String, presentUdns: Set<String>) {
        val iterator = _devices.iterator()
        while (iterator.hasNext()) {
            val device = iterator.next()
            if (ownerRootByUdn[device.udn.value] == ownerRootUdn && device.udn.value !in presentUdns) {
                iterator.remove()
                ownerRootByUdn.remove(device.udn.value)
                notifyDeviceLost(device.udn)
                if (isRendererType(device.deviceType)) {
                    notifyRendererOffline(device.udn)
                }
            }
        }
    }

    override fun remoteDeviceDiscoveryStarted(registry: Registry?, device: RemoteDevice?) {
        // This method is called when remote device discovery starts.
        // Note: We don't add the device here yet, only when discovery is complete.
    }

    override fun remoteDeviceDiscoveryFailed(
        registry: Registry?, device: RemoteDevice?, e: Exception?
    ) {
        device ?: return

        val rootUdn = device.identity.udn.identifierString
        Log.w(
            "UpnpRegistryListener",
            "Remote device discovery failed for: ${device.details?.friendlyName} ($rootUdn)",
            e
        )

        // A failed discovery may have a partially hydrated graph, so rely on the recorded
        // ownership to clear the root and any descendants discovered previously.
        removeOwnedDevices(rootUdn)
    }

    override fun remoteDeviceAdded(registry: Registry?, device: RemoteDevice?) {
        device ?: return
        val ownerRootUdn = device.identity.udn.identifierString
        visitMediaDevices(device) { addDiscoveredDevice(it, ownerRootUdn) }
    }

    override fun remoteDeviceUpdated(registry: Registry?, device: RemoteDevice?) {
        device ?: return
        val ownerRootUdn = device.identity.udn.identifierString
        val presentUdns = collectAllDeviceUdns(device)
        visitMediaDevices(device) { updateDiscoveredDevice(it, ownerRootUdn) }
        pruneOwnedDevices(ownerRootUdn, presentUdns)
    }

    override fun remoteDeviceRemoved(registry: Registry?, device: RemoteDevice?) {
        val deviceUdn = device?.identity?.udn ?: return
        removeOwnedDevices(deviceUdn.identifierString)
    }

    override fun localDeviceAdded(registry: Registry?, device: LocalDevice?) {
        device ?: return
        val ownerRootUdn = device.identity.udn.identifierString
        visitMediaDevices(device) { addDiscoveredDevice(it, ownerRootUdn) }
    }

    override fun localDeviceRemoved(registry: Registry?, device: LocalDevice?) {
        val deviceUdn = device?.identity?.udn ?: return
        removeOwnedDevices(deviceUdn.identifierString)
    }

    override fun beforeShutdown(registry: Registry?) {
        // This method is called before the registry is shut down.
        // Clear all devices from the list
        _devices.clear()
        ownerRootByUdn.clear()
    }

    override fun afterShutdown() {
        // This method is called after the registry has been shut down.
        // Ensure devices list is cleared
        _devices.clear()
        ownerRootByUdn.clear()
    }
}
