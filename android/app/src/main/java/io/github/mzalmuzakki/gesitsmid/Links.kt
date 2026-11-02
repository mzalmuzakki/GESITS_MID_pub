package io.github.mzalmuzakki.gesitsmid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** A byte pipe to the MID. Received bytes go to [onData]; [onClosed] fires once when the link drops. */
interface MidLink {
    val name: String
    fun open()                       // blocking; throws IOException on failure
    fun write(data: ByteArray)       // blocking; throws IOException on failure
    fun close()
    val isOpen: Boolean
}

/** Bluetooth Classic SPP (RFCOMM). The main path used by both known Gesits apps. */
@SuppressLint("MissingPermission")
class ClassicLink(
    private val device: BluetoothDevice,
    private val onData: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
    /** May Android show a pairing request? Only when you started the connection, never on background retries. */
    private val allowPairing: Boolean = true,
    /** The method that worked last time, tried first. */
    private val preferred: String? = null,
) : MidLink {
    override val name = "Classic"
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closedReported = false

    // isConnected stays true after the other side vanishes; the receive thread ending is the real signal.
    override val isOpen get() = socket?.isConnected == true && !closedReported

    /** The method that worked: "insecure", "secure" or "ch1". */
    var usedMethod: String? = null; private set

    override fun open() {
        // insecure: no pairing needed (survives the MID forgetting this phone) and never pops up a request.
        // secure: the usual paired connection, as GRID Mobile / GESITS Dashboard do.  ch1: RFCOMM channel 1.
        // The way that worked last time goes first, so reconnecting doesn't wait for a way this MID refuses.
        // Without a pairing, the paired ways are skipped on background retries so Android doesn't keep asking.
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        val order = (listOfNotNull(preferred) + listOf("insecure", "secure", "ch1")).distinct()
            .filter { it == "insecure" || bonded || allowPairing }
        if (order.isEmpty()) throw IOException(NOT_PAIRED)
        var firstError: Exception? = null
        var s: BluetoothSocket? = null
        for (m in order) {
            var sock: BluetoothSocket? = null
            try {
                val created: BluetoothSocket = when (m) {
                    "insecure" -> device.createInsecureRfcommSocketToServiceRecord(Mid.SPP)
                    "secure" -> device.createRfcommSocketToServiceRecord(Mid.SPP)
                    else -> device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
                }
                sock = created
                created.connect()
                s = created; usedMethod = m; break
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                try { sock?.close() } catch (_: IOException) {}
            }
        }
        if (s == null) {
            if (!bonded && !allowPairing && order == listOf("insecure")) throw IOException(NOT_PAIRED)
            throw IOException("Classic connect failed: ${firstError?.message}")
        }
        val conn: BluetoothSocket = s
        socket = conn
        Thread({
            val buf = ByteArray(1024)
            try {
                val input = conn.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onData(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            reportClosed()
        }, "mid-classic-rx").start()
    }

    override fun write(data: ByteArray) {
        val s = socket ?: throw IOException("not connected")
        synchronized(this) {
            s.outputStream.write(data)
            s.outputStream.flush()
        }
    }

    companion object { const val NOT_PAIRED = "not paired" }

    private fun reportClosed() {
        if (!closedReported) { closedReported = true; onClosed() }
    }

    override fun close() {
        closedReported = true
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }
}

/**
 * Bluetooth LE: device "GESITS", characteristic FFF2 (notify + read + write).
 * Always write WITH response (FFF2 has no write-without-response). Long commands are
 * split; the MID joins pieces until CR LF. AT+MAPS uses: header, 50 ms, 100-char pieces
 * with 30 ms gaps, closing quote + CR LF - as GESITS Dashboard's working ESP32 sketch.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onData: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) : MidLink {
    override val name = "BLE"
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ch: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var ready = false
    @Volatile private var closedReported = false
    private val readyLatch = CountDownLatch(1)
    private val writeDone = Semaphore(0)
    @Volatile private var lastWriteOk = true

    override val isOpen get() = ready && gatt != null

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                readyLatch.countDown()
                writeDone.release()
                if (!closedReported) { closedReported = true; onClosed() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.services.flatMap { it.characteristics }.firstOrNull { it.uuid == Mid.FFF2 }
            ch = c
            if (c == null) { readyLatch.countDown(); return }
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(Mid.CCCD)
            if (d == null) { ready = true; readyLatch.countDown(); return }
            val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) else { d.value = v; g.writeDescriptor(d) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready = true
            readyLatch.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            lastWriteOk = status == BluetoothGatt.GATT_SUCCESS
            writeDone.release()
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }

        // Android 8-12
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { onData(it.copyOf()) }
        }
    }

    override fun open() {
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (!readyLatch.await(20, TimeUnit.SECONDS) || !ready) {
            val why = if (ch == null && gatt != null) "characteristic FFF2 not found" else "timeout / disconnected"
            close()
            throw IOException("BLE connect failed: $why")
        }
    }

    private fun writePiece(piece: ByteArray) {
        val g = gatt ?: throw IOException("not connected")
        val c = ch ?: throw IOException("not connected")
        writeDone.drainPermits()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, piece, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = piece
            g.writeCharacteristic(c)
        }
        if (!started) throw IOException("BLE write could not start")
        if (!writeDone.tryAcquire(3, TimeUnit.SECONDS)) throw IOException("BLE write timeout")
        if (!ready) throw IOException("BLE disconnected")
        if (!lastWriteOk) throw IOException("BLE write rejected")
    }

    override fun write(data: ByteArray) {
        val size = maxOf(20, minOf(100, mtu - 3))
        val header = "AT+MAPS=\"".toByteArray()
        synchronized(this) {
            if (data.size > 200 && data.copyOfRange(0, header.size).contentEquals(header)) {
                var end = data.size
                val tail = if (data.size >= 3 && data[end - 3] == '"'.code.toByte()) 3 else 0
                end -= tail
                writePiece(header); Thread.sleep(50)
                var i = header.size
                while (i < end) {
                    val j = minOf(i + size, end)
                    writePiece(data.copyOfRange(i, j)); Thread.sleep(30)
                    i = j
                }
                if (tail > 0) writePiece(data.copyOfRange(end, data.size))
            } else {
                var i = 0
                while (i < data.size) {
                    val j = minOf(i + size, data.size)
                    writePiece(data.copyOfRange(i, j))
                    i = j
                }
            }
        }
    }

    override fun close() {
        closedReported = true
        ready = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeDone.release()
    }
}
