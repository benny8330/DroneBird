package com.dronebird.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Matched USB device identity, surfaced to the UI. */
data class DeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val productName: String?
) {
    /** "0x2CA3 / 0x001F — DJI USB VCOM For Protocol" style label. */
    val label: String
        get() = String.format("0x%04X / 0x%04X — %s", vendorId, productId, productName ?: "unknown")
}

/** Observable connection lifecycle of the serial controller. */
sealed class ConnectionState {
    /** No matching/openable USB device is attached. */
    object NoDevice : ConnectionState()
    /** A device was found but USB permission was refused by the user. */
    object PermissionDenied : ConnectionState()
    /** Permission granted / open in progress. */
    object Connecting : ConnectionState()
    /** Streaming bytes from [device]. */
    data class Connected(val device: DeviceInfo) : ConnectionState()
    /** Device detached or stream ended. */
    object Disconnected : ConnectionState()
    /** Something failed; [message] explains what. */
    data class Error(val message: String) : ConnectionState()
}

/**
 * Finds the DJI RC-N1 on the USB bus, requests permission, opens it as a CDC
 * serial port and streams raw byte chunks on a background coroutine.
 *
 * This class is deliberately protocol-agnostic: it only moves bytes. Decoding is
 * [FrameParser]'s job. Create one per screen that needs serial, call [start] in
 * onStart and [stop] in onStop.
 */
class UsbSerialController(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private var port: UsbSerialPort? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NoDevice)
    /** Connection lifecycle observed by the detection and diagnostic screens. */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    /** Raw byte chunks as they arrive from the serial port. */
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private var permissionReceiver: BroadcastReceiver? = null
    private var detachReceiver: BroadcastReceiver? = null

    /** Registers receivers and attempts to connect to an attached controller. */
    fun start() {
        registerDetachReceiver()
        connect()
    }

    /** Closes the port, cancels the read loop and unregisters receivers. */
    fun stop() {
        readJob?.cancel()
        readJob = null
        closePort()
        unregister(permissionReceiver); permissionReceiver = null
        unregister(detachReceiver); detachReceiver = null
    }

    /** Fully disposes the controller (call from onDestroy). */
    fun dispose() {
        stop()
        scope.cancel()
    }

    /** Scans for a matching device and either opens it or requests permission. */
    fun connect() {
        val driver = findDriver()
        if (driver == null) {
            _state.value = ConnectionState.NoDevice
            return
        }
        val device = driver.device
        if (usbManager.hasPermission(device)) {
            open(driver)
        } else {
            requestPermission(device)
        }
    }

    /**
     * Human-readable dump of every USB device the app can currently see (matched or
     * not), plus the serial-driver probe count. This is the ground truth for
     * diagnosing "No controller detected": if the OTG icon shows but this reports
     * 0 devices, the phone is not acting as USB host for the controller.
     */
    fun attachedDevicesInfo(): String {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            return "0 USB devices visible to the app.\n\n" +
                "If the phone shows a USB/OTG icon but this is 0, the phone is NOT " +
                "in host mode for this device — usually a USB-C power-role or cable " +
                "issue (the controller is charging the phone instead of being read)."
        }
        val sb = StringBuilder("${devices.size} USB device(s) visible:\n\n")
        for (d in devices) {
            sb.append(String.format("• VID 0x%04X (%d)  PID 0x%04X (%d)\n",
                d.vendorId, d.vendorId, d.productId, d.productId))
            sb.append("  name: ${d.deviceName}\n")
            sb.append("  mfr: ${runCatching { d.manufacturerName }.getOrNull() ?: "?"}" +
                "  product: ${runCatching { d.productName }.getOrNull() ?: "?"}\n")
            sb.append("  class: ${d.deviceClass}  interfaces: ${d.interfaceCount}\n")
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                sb.append("    if$i class=${intf.interfaceClass} subclass=${intf.interfaceSubclass}\n")
            }
            sb.append("  permission: ${usbManager.hasPermission(d)}\n\n")
        }
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        sb.append("serial drivers recognised by library: ${drivers.size}")
        return sb.toString()
    }

    /**
     * Requests USB permission for the first attached device that lacks it, to force
     * the permission dialog. Returns false if nothing is attached.
     */
    fun requestPermissionForAttached(): Boolean {
        val target = usbManager.deviceList.values.firstOrNull { !usbManager.hasPermission(it) }
        if (target != null) {
            requestPermission(target)
            return true
        }
        // Already have permission for everything attached, or nothing attached.
        return usbManager.deviceList.isNotEmpty()
    }

    // ------------------------------------------------------------------
    // Device discovery
    // ------------------------------------------------------------------

    /**
     * Returns a usable driver. Strategy: (1) a custom prober mapping the DJI
     * VID/PID to a CDC driver, (2) the library default prober, (3) first available
     * device probed as CDC. Logs whatever it finds so discovery is transparent.
     */
    private fun findDriver(): UsbSerialDriver? {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            Log.i(TAG, "No USB devices attached.")
            return null
        }
        devices.forEach { d ->
            Log.i(TAG, "USB present: vid=0x%04X pid=0x%04X name=%s"
                .format(d.vendorId, d.productId, d.productName))
        }

        // (1) Custom prober for the configured DJI IDs, forced to CDC.
        if (DjiProtocol.USE_CDC_DRIVER) {
            val table = ProbeTable()
            devices.filter { matchesConfiguredId(it) }.forEach { d ->
                table.addProduct(d.vendorId, d.productId, CdcAcmSerialDriver::class.java)
            }
            val custom = UsbSerialProber(table).findAllDrivers(usbManager)
            if (custom.isNotEmpty()) {
                Log.i(TAG, "Matched via custom CDC prober: ${custom.first().device.productName}")
                return custom.first()
            }
        }

        // (2) Library default prober (recognises many known CDC/FTDI/CP21xx chips).
        val defaults = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val byId = defaults.firstOrNull { matchesConfiguredId(it.device) }
        if (byId != null) {
            Log.i(TAG, "Matched configured id via default prober.")
            return byId
        }
        if (defaults.isNotEmpty()) {
            Log.i(TAG, "No id match; falling back to first CDC device: " +
                defaults.first().device.productName)
            return defaults.first()
        }

        // (3) Last resort: probe the first attached device as CDC regardless.
        val first = devices.first()
        val table = ProbeTable().apply {
            addProduct(first.vendorId, first.productId, CdcAcmSerialDriver::class.java)
        }
        val forced = UsbSerialProber(table).findAllDrivers(usbManager)
        if (forced.isNotEmpty()) {
            Log.i(TAG, "Forcing CDC on first device: ${first.productName}")
            return forced.first()
        }
        return null
    }

    /** True when [device] matches the configured VID (and PID, unless PID is -1). */
    private fun matchesConfiguredId(device: UsbDevice): Boolean {
        if (device.vendorId != DjiProtocol.VENDOR_ID) return false
        return DjiProtocol.PRODUCT_ID == -1 || device.productId == DjiProtocol.PRODUCT_ID
    }

    // ------------------------------------------------------------------
    // Permission
    // ------------------------------------------------------------------

    /** Requests USB permission, wiring a one-shot receiver to open on grant. */
    private fun requestPermission(device: UsbDevice) {
        _state.value = ConnectionState.Connecting
        unregister(permissionReceiver)
        permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    val driver = findDriver()
                    if (driver != null) open(driver) else _state.value = ConnectionState.NoDevice
                } else {
                    _state.value = ConnectionState.PermissionDenied
                }
            }
        }
        registerReceiver(permissionReceiver!!, IntentFilter(ACTION_USB_PERMISSION))

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags
        )
        usbManager.requestPermission(device, pi)
    }

    // ------------------------------------------------------------------
    // Open + read loop
    // ------------------------------------------------------------------

    /** Opens the first port of [driver], applies serial params and starts reading. */
    private fun open(driver: UsbSerialDriver) {
        _state.value = ConnectionState.Connecting
        val device = driver.device
        val connection: UsbDeviceConnection? = usbManager.openDevice(device)
        if (connection == null) {
            _state.value = ConnectionState.Error("Could not open USB device (null connection).")
            return
        }
        val serialPort = driver.ports.firstOrNull()
        if (serialPort == null) {
            _state.value = ConnectionState.Error("Device exposes no serial port.")
            return
        }
        try {
            serialPort.open(connection)
            serialPort.setParameters(
                DjiProtocol.BAUD_RATE,
                DjiProtocol.DATA_BITS,
                stopBitsConst(DjiProtocol.STOP_BITS),
                parityConst(DjiProtocol.PARITY)
            )
            runCatching { serialPort.setDTR(true) }
            runCatching { serialPort.setRTS(true) }
        } catch (e: Exception) {
            runCatching { serialPort.close() }
            _state.value = ConnectionState.Error("Open failed: ${e.message}")
            return
        }
        port = serialPort
        _state.value = ConnectionState.Connected(
            DeviceInfo(device.vendorId, device.productId, device.productName)
        )
        startReadLoop(serialPort)
    }

    /** Launches the background coroutine that pumps raw bytes into [frames]. */
    private fun startReadLoop(serialPort: UsbSerialPort) {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(DjiProtocol.READ_BUFFER_SIZE)
            try {
                while (isActive) {
                    val len = try {
                        serialPort.read(buffer, DjiProtocol.READ_TIMEOUT_MS)
                    } catch (e: Exception) {
                        _state.value = ConnectionState.Error("Read error: ${e.message}")
                        break
                    }
                    if (len > 0) {
                        _frames.emit(buffer.copyOf(len))
                    }
                }
            } finally {
                // Loop ended: either cancelled (stop) or stream died.
                if (_state.value is ConnectionState.Connected) {
                    _state.value = ConnectionState.Disconnected
                }
            }
        }
    }

    private fun closePort() {
        port?.let { p -> runCatching { p.close() } }
        port = null
    }

    // ------------------------------------------------------------------
    // USB attach/detach receiver
    // ------------------------------------------------------------------

    /** Reconnects on attach and tears down on detach. */
    private fun registerDetachReceiver() {
        unregister(detachReceiver)
        detachReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        readJob?.cancel(); readJob = null
                        closePort()
                        _state.value = ConnectionState.Disconnected
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(detachReceiver!!, filter)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun unregister(receiver: BroadcastReceiver?) {
        if (receiver != null) runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun stopBitsConst(bits: Int) = when (bits) {
        2 -> UsbSerialPort.STOPBITS_2
        3 -> UsbSerialPort.STOPBITS_1_5
        else -> UsbSerialPort.STOPBITS_1
    }

    private fun parityConst(parity: Int) = when (parity) {
        1 -> UsbSerialPort.PARITY_ODD
        2 -> UsbSerialPort.PARITY_EVEN
        3 -> UsbSerialPort.PARITY_MARK
        4 -> UsbSerialPort.PARITY_SPACE
        else -> UsbSerialPort.PARITY_NONE
    }

    private companion object {
        const val TAG = "UsbSerialController"
        const val ACTION_USB_PERMISSION = "com.dronebird.USB_PERMISSION"
    }
}
