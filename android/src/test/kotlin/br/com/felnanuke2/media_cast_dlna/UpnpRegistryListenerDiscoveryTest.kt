package br.com.felnanuke2.media_cast_dlna

import DlnaDevice
import DeviceUdn
import DiscoveryEventsFlutterApi
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import org.jupnp.model.meta.DeviceDetails
import org.jupnp.model.meta.DeviceIdentity
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.LocalService
import org.jupnp.model.meta.ManufacturerDetails
import org.jupnp.model.meta.ModelDetails
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.RemoteDeviceIdentity
import org.jupnp.model.meta.RemoteService
import org.jupnp.model.types.DeviceType
import org.jupnp.model.types.ServiceId
import org.jupnp.model.types.ServiceType
import org.jupnp.model.types.UDN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer

/**
 * Regression tests for embedded-media-device discovery.
 *
 * A synthetic non-media wrapper embeds the actual MediaRenderer. This fixture is not
 * a captured Xiaomi descriptor. jUPnP reports the registered root, so the listener must
 * walk its embedded graph and apply the media filter to every descendant.
 *
 * These tests drive the real [UpnpRegistryListener] public callbacks with real jUPnP
 * [RemoteDevice]/[LocalDevice] trees and assert the Pigeon payloads that actually reach the
 * Flutter side (encoded through the production [DiscoveryEventsFlutterApi] codec).
 *
 * Robolectric is required because [UpnpRegistryListener] creates an Android `Handler` and posts
 * every Flutter callback to the main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class UpnpRegistryListenerDiscoveryTest {

    private lateinit var messenger: RecordingBinaryMessenger
    private lateinit var listener: UpnpRegistryListener

    @Before
    fun setUp() {
        messenger = RecordingBinaryMessenger()
        listener = UpnpRegistryListener(DiscoveryEventsFlutterApi(messenger))
    }

    // --- Remote device graph: non-media wrapper with nested media children -----------------

    @Test
    fun remoteDeviceAdded_preservesRootRendererDiscovery() {
        listener.remoteDeviceAdded(null, rendererDevice())
        idleMainLooper()
        assertEquals(listOf(RENDERER_UDN), listener.devices.map { it.udn.value })
        assertEquals(listOf(RENDERER_UDN), foundUdns())
    }

    @Test
    fun remoteDeviceAdded_registersRendererNestedInNonMediaWrapper() {
        val root = wrapperDevice(children = arrayOf(rendererDevice()))

        listener.remoteDeviceAdded(null, root)
        idleMainLooper()

        assertEquals(1, listener.devices.size)
        val discovered = listener.devices[0]
        assertEquals(RENDERER_UDN, discovered.udn.value)
        assertEquals("MediaRenderer", discovered.deviceType)
        assertEquals("Xiaomi Mi Box", discovered.friendlyName)
        assertEquals("192.168.1.50", discovered.ipAddress.value)
        assertEquals(49152L, discovered.port.value)

        // The wrapper itself must not be reported, and exactly one found event carries the child.
        assertTrue(listener.devices.none { it.udn.value == WRAPPER_UDN })
        assertEquals(listOf(RENDERER_UDN), foundUdns())
    }

    @Test
    fun remoteDeviceAdded_reachesRendererNestedTwoLevelsDeep() {
        val root = wrapperDevice(
            children = arrayOf(
                wrapperDevice(udn = MIDDLE_UDN, friendlyName = "Xiaomi Middle", children = arrayOf(rendererDevice()))
            )
        )

        listener.remoteDeviceAdded(null, root)
        idleMainLooper()

        assertEquals(listOf(RENDERER_UDN), listener.devices.map { it.udn.value })
        assertEquals(listOf(RENDERER_UDN), foundUdns())
    }

    @Test
    fun remoteDeviceAdded_registersRendererUnderExcludedWrapperType() {
        val root = wrapperDevice(type = "InternetGatewayDevice", children = arrayOf(rendererDevice()))

        listener.remoteDeviceAdded(null, root)
        idleMainLooper()

        assertEquals(listOf(RENDERER_UDN), listener.devices.map { it.udn.value })
        assertEquals(listOf(RENDERER_UDN), foundUdns())
    }

    // --- Filter semantics applied per descendant -------------------------------------------

    @Test
    fun rendererSearchTarget_keepsOnlyRendererDescendants() {
        listener.setSearchTarget("urn:schemas-upnp-org:device:MediaRenderer:1")
        val root = wrapperDevice(children = arrayOf(rendererDevice(), mediaServerDevice()))

        listener.remoteDeviceAdded(null, root)
        idleMainLooper()

        assertEquals(listOf(RENDERER_UDN), listener.devices.map { it.udn.value })
        assertEquals(listOf(RENDERER_UDN), foundUdns())
    }

    @Test
    fun serverSearchTarget_keepsOnlyServerDescendants() {
        listener.setSearchTarget("urn:schemas-upnp-org:device:MediaServer:1")
        val root = wrapperDevice(children = arrayOf(rendererDevice(), mediaServerDevice()))

        listener.remoteDeviceAdded(null, root)
        idleMainLooper()

        assertEquals(listOf(SERVER_UDN), listener.devices.map { it.udn.value })
        assertEquals(listOf(SERVER_UDN), foundUdns())
    }

    // --- Dedup / update --------------------------------------------------------------------

    @Test
    fun remoteDeviceAdded_isIdempotentForSameRoot() {
        val root = wrapperDevice(children = arrayOf(rendererDevice()))

        listener.remoteDeviceAdded(null, root)
        listener.remoteDeviceAdded(null, root)
        idleMainLooper()

        assertEquals(1, listener.devices.size)
        assertEquals(listOf(RENDERER_UDN), foundUdns())
    }

    @Test
    fun remoteDeviceUpdated_replacesChildSnapshotWithoutDuplicate() {
        listener.remoteDeviceAdded(null, wrapperDevice(children = arrayOf(rendererDevice(friendlyName = "Old Name"))))
        idleMainLooper()
        messenger.clear()

        listener.remoteDeviceUpdated(null, wrapperDevice(children = arrayOf(rendererDevice(friendlyName = "New Name"))))
        idleMainLooper()

        assertEquals(1, listener.devices.size)
        assertEquals(RENDERER_UDN, listener.devices[0].udn.value)
        assertEquals("New Name", listener.devices[0].friendlyName)
        assertTrue(foundUdns().isEmpty())
        assertTrue(lostUdns().isEmpty())
        assertTrue(offlineUdns().isEmpty())
    }

    @Test
    fun remoteDeviceUpdated_prunesDescendantMissingFromUpdatedGraph() {
        listener.remoteDeviceAdded(null, wrapperDevice(children = arrayOf(rendererDevice(), mediaServerDevice())))
        idleMainLooper()
        messenger.clear()

        listener.remoteDeviceUpdated(null, wrapperDevice(children = arrayOf(mediaServerDevice())))
        idleMainLooper()

        assertEquals(listOf(SERVER_UDN), listener.devices.map { it.udn.value })
        assertEquals(listOf(RENDERER_UDN), lostUdns())
        assertEquals(listOf(RENDERER_UDN), offlineUdns())
    }

    // --- Removal / failure -----------------------------------------------------------------

    @Test
    fun remoteDeviceRemoved_clearsOwnedDescendants() {
        val root = wrapperDevice(children = arrayOf(rendererDevice()))
        listener.remoteDeviceAdded(null, root)
        idleMainLooper()
        messenger.clear()

        listener.remoteDeviceRemoved(null, root)
        idleMainLooper()

        assertTrue(listener.devices.isEmpty())
        assertEquals(listOf(RENDERER_UDN), lostUdns())
        assertEquals(listOf(RENDERER_UDN), offlineUdns())
    }

    @Test
    fun remoteDeviceRemoved_afterFilterChange_stillClearsPreviouslyDiscoveredRenderer() {
        val root = wrapperDevice(children = arrayOf(rendererDevice()))
        listener.remoteDeviceAdded(null, root)
        idleMainLooper()

        // A later search target no longer matches the renderer; removal must ignore the filter.
        listener.setSearchTarget("urn:schemas-upnp-org:device:MediaServer:1")
        messenger.clear()

        listener.remoteDeviceRemoved(null, root)
        idleMainLooper()

        assertTrue(listener.devices.isEmpty())
        assertEquals(listOf(RENDERER_UDN), lostUdns())
        assertEquals(listOf(RENDERER_UDN), offlineUdns())
    }

    @Test
    fun remoteDeviceRemoved_mediaServerDoesNotEmitRendererOffline() {
        val root = wrapperDevice(children = arrayOf(mediaServerDevice()))
        listener.remoteDeviceAdded(null, root)
        idleMainLooper()
        messenger.clear()

        listener.remoteDeviceRemoved(null, root)
        idleMainLooper()

        assertEquals(listOf(SERVER_UDN), lostUdns())
        assertTrue(offlineUdns().isEmpty())
    }

    @Test
    fun remoteDeviceDiscoveryFailed_clearsOwnedDescendants() {
        val root = wrapperDevice(children = arrayOf(rendererDevice()))
        listener.remoteDeviceAdded(null, root)
        idleMainLooper()
        messenger.clear()

        listener.remoteDeviceDiscoveryFailed(null, root, RuntimeException("ssdp timeout"))
        idleMainLooper()

        assertTrue(listener.devices.isEmpty())
        assertEquals(listOf(RENDERER_UDN), lostUdns())
        assertEquals(listOf(RENDERER_UDN), offlineUdns())
    }

    @Test
    fun remoteDeviceDiscoveryFailed_withPartiallyHydratedRoot_clearsPreviouslyDiscoveredChildren() {
        listener.remoteDeviceAdded(null, wrapperDevice(children = arrayOf(rendererDevice())))
        idleMainLooper()
        messenger.clear()

        // The failure callback may carry the same root UDN with an empty embedded graph.
        val partiallyHydratedRoot = wrapperDevice(children = arrayOf<RemoteDevice>())
        listener.remoteDeviceDiscoveryFailed(null, partiallyHydratedRoot, RuntimeException("descriptor fetch failed"))
        idleMainLooper()

        assertTrue(listener.devices.isEmpty())
        assertEquals(listOf(RENDERER_UDN), lostUdns())
        assertEquals(listOf(RENDERER_UDN), offlineUdns())
    }

    // --- Local devices ---------------------------------------------------------------------

    @Test
    fun localDeviceAdded_registersRendererNestedInLocalWrapper() {
        listener.localDeviceAdded(null, localWrapperDevice())
        idleMainLooper()

        assertEquals(listOf(LOCAL_RENDERER_UDN), listener.devices.map { it.udn.value })
        assertEquals("Local Renderer", listener.devices[0].friendlyName)
        assertEquals(listOf(LOCAL_RENDERER_UDN), foundUdns())
    }

    @Test
    fun localDeviceRemoved_clearsNestedRenderer() {
        listener.localDeviceAdded(null, localWrapperDevice())
        idleMainLooper()
        messenger.clear()

        listener.localDeviceRemoved(null, localWrapperDevice())
        idleMainLooper()

        assertTrue(listener.devices.isEmpty())
        assertEquals(listOf(LOCAL_RENDERER_UDN), lostUdns())
        assertEquals(listOf(LOCAL_RENDERER_UDN), offlineUdns())
    }


    // --- Fixtures --------------------------------------------------------------------------

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun foundDevices(): List<DlnaDevice> =
        messenger.payloads(FOUND_CHANNEL).map { it as DlnaDevice }

    private fun foundUdns(): List<String> = foundDevices().map { it.udn.value }

    private fun lostUdns(): List<String> = messenger.payloads(LOST_CHANNEL).map { (it as DeviceUdn).value }

    private fun offlineUdns(): List<String> = messenger.payloads(OFFLINE_CHANNEL).map { (it as DeviceUdn).value }

    private fun details(friendlyName: String): DeviceDetails = DeviceDetails(
        friendlyName,
        ManufacturerDetails("Xiaomi", URI("http://www.xiaomi.com")),
        ModelDetails("Mi Device", "Xiaomi device", "1.0", URI("http://www.xiaomi.com/model"))
    )

    private fun remoteIdentity(udn: String): RemoteDeviceIdentity = RemoteDeviceIdentity(
        UDN.valueOf("uuid:$udn"),
        1800,
        URL("http://192.168.1.50:49152/description.xml"),
        null,
        null
    )

    private fun remoteService(type: String, id: String): RemoteService {
        val base = "http://192.168.1.50:49152"
        return RemoteService(
            ServiceType("schemas-upnp-org", type, 1),
            ServiceId("upnp-org", id),
            URI("$base/$id/scpd.xml"),
            URI("$base/$id/control"),
            URI("$base/$id/event")
        )
    }

    private fun rendererDevice(
        udn: String = RENDERER_UDN,
        friendlyName: String = "Xiaomi Mi Box"
    ): RemoteDevice = RemoteDevice(
        remoteIdentity(udn),
        DeviceType("schemas-upnp-org", "MediaRenderer", 1),
        details(friendlyName),
        arrayOf(remoteService("AVTransport", "AVTransport")),
        arrayOf<RemoteDevice>()
    )

    private fun mediaServerDevice(
        udn: String = SERVER_UDN,
        friendlyName: String = "Xiaomi NAS"
    ): RemoteDevice = RemoteDevice(
        remoteIdentity(udn),
        DeviceType("schemas-upnp-org", "MediaServer", 1),
        details(friendlyName),
        arrayOf(remoteService("ContentDirectory", "ContentDirectory")),
        arrayOf<RemoteDevice>()
    )

    private fun wrapperDevice(
        udn: String = WRAPPER_UDN,
        friendlyName: String = "Xiaomi Router",
        type: String = "Basic",
        children: Array<RemoteDevice>
    ): RemoteDevice = RemoteDevice(
        remoteIdentity(udn),
        DeviceType("schemas-upnp-org", type, 1),
        details(friendlyName),
        arrayOf<RemoteService>(),
        children
    )

    private fun localWrapperDevice(): LocalDevice = LocalDevice(
        DeviceIdentity(UDN.valueOf("uuid:$LOCAL_WRAPPER_UDN")),
        DeviceType("schemas-upnp-org", "Basic", 1),
        details("Local Wrapper"),
        NO_LOCAL_SERVICES,
        arrayOf(localRendererDevice())
    )

    private fun localRendererDevice(): LocalDevice = LocalDevice(
        DeviceIdentity(UDN.valueOf("uuid:$LOCAL_RENDERER_UDN")),
        DeviceType("schemas-upnp-org", "MediaRenderer", 1),
        details("Local Renderer"),
        NO_LOCAL_SERVICES,
        arrayOf<LocalDevice>()
    )


    /**
     * Captures the messages the plugin sends to Flutter. Messages are decoded with the production
     * Pigeon codec, so assertions observe exactly the payload the Dart side receives.
     */
    private class RecordingBinaryMessenger : BinaryMessenger {
        private val recorded = mutableListOf<Pair<String, Any?>>()

        override fun send(channel: String, message: ByteBuffer?) {
            record(channel, message)
        }

        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) {
            record(channel, message)
        }

        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            // No inbound traffic is expected from the listener.
        }

        fun clear() {
            recorded.clear()
        }

        fun payloads(channel: String): List<Any?> = recorded
            .filter { it.first == channel }
            .mapNotNull { (it.second as? List<*>)?.firstOrNull() }

        private fun record(channel: String, message: ByteBuffer?) {
            if (message == null) {
                recorded.add(channel to null)
                return
            }
            val buffer = message.duplicate()
            buffer.rewind()
            recorded.add(channel to DiscoveryEventsFlutterApi.codec.decodeMessage(buffer))
        }
    }

    private companion object {
        // jUPnP stores UDNs prefix-free in `UDN.identifierString` (that is what the
        // DlnaDevice converter forwards to Flutter); fixtures build them via UDN.valueOf,
        // exactly like the SSDP/descriptor parsers do.
        const val WRAPPER_UDN = "xiaomi-wrapper-0001"
        const val MIDDLE_UDN = "xiaomi-middle-0001"
        const val RENDERER_UDN = "xiaomi-renderer-0001"
        const val SERVER_UDN = "xiaomi-server-0001"
        const val LOCAL_WRAPPER_UDN = "local-wrapper-0001"
        const val LOCAL_RENDERER_UDN = "local-renderer-0001"

        const val CHANNEL_PREFIX = "dev.flutter.pigeon.media_cast_dlna.DiscoveryEventsFlutterApi"
        const val FOUND_CHANNEL = "$CHANNEL_PREFIX.onDeviceFound"
        const val LOST_CHANNEL = "$CHANNEL_PREFIX.onDeviceLost"
        const val OFFLINE_CHANNEL = "$CHANNEL_PREFIX.onRendererOffline"

        val NO_LOCAL_SERVICES: Array<LocalService<*>> = emptyArray()
    }
}
