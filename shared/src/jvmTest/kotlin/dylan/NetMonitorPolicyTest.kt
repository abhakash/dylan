package dylan

import dylan.support.FakeNetMonitor
import dylan.util.DISK_UNKNOWN
import dylan.util.JvmNetMonitor
import dylan.util.NetClass
import dylan.util.NetMonitorPolicy
import dylan.util.freeDiskBytes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The failure-default policy and the disk sentinel. Before this seam, the JVM implementation
 * returned `UNMETERED` / `isOnline() == true` unconditionally, so all 90 legacy tests ran with
 * the network baked in and "unknown" was indistinguishable from "connected and cheap".
 */
class NetMonitorPolicyTest {
    @Test
    fun aFreshFakeIsOfflineAndMeteredNotOptimistic() {
        val net = FakeNetMonitor()
        assertEquals(NetMonitorPolicy.UNKNOWN_NET_CLASS, net.current())
        assertEquals(NetMonitorPolicy.UNKNOWN_ONLINE, net.isOnline())
        assertEquals(NetClass.METERED, net.current(), "unknown must fail closed to METERED")
        assertTrue(!net.isOnline(), "unknown must not claim a connection")
    }

    @Test
    fun theJvmMonitorDefaultsToTheSameFailurePolicy() {
        val net = JvmNetMonitor()
        assertEquals(NetClass.METERED, net.current())
        assertTrue(!net.isOnline(), "the JVM monitor must not be a hardcoded 'online' lie")
    }

    @Test
    fun pushesDriveBothDirections() {
        val net = FakeNetMonitor()
        net.pushOnline(true)
        net.pushMetered(false)
        assertEquals(NetClass.UNMETERED, net.current())
        assertTrue(net.isOnline())
        net.set(online = true, netClass = NetClass.METERED)
        assertEquals(NetClass.METERED, net.current())
    }

    @Test
    fun freeDiskOnAMissingPathIsUnknownNotZero() {
        val missing = File(System.getProperty("java.io.tmpdir"), "dylan-does-not-exist-${System.nanoTime()}")
        val free = freeDiskBytes(missing.absolutePath)
        assertEquals(DISK_UNKNOWN, free, "unknown free space must be -1; 0 reads as 'no space' to the download guard")
    }

    @Test
    fun freeDiskOnAnExistingPathIsARealByteCount() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dylan-disk-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val free = freeDiskBytes(dir.absolutePath)
            assertTrue(free > 0, "an existing path must report real free bytes, got $free")
        } finally {
            dir.delete()
        }
    }
}
