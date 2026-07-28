package com.foodorder.staff

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.Manifest
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

enum class PrinterProtocol(val label: String) {
    ESC_POS("ESC/POS · Nippon POS / thermal"),
    SATO_SBPL("SATO SBPL · label printer"),
    TSPL("TSPL2 · 4BARCODE / TSC label"),
}

/** How the receipt bytes physically reach the printer — independent of
 *  [PrinterProtocol], which is which command language they're encoded in. */
enum class PrinterTransport(val label: String) {
    NETWORK("WiFi/Network"),
    USB("USB"),
    BLUETOOTH("Bluetooth"),
}

data class UsbPrinter(val deviceId: Int, val label: String)
data class BluetoothPrinter(val address: String, val label: String)

/** Standard Serial Port Profile UUID — practically universal for how
 *  ESC/POS thermal printers expose themselves over classic Bluetooth. */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

const val DEFAULT_LABEL_WIDTH_MM = 50
const val DEFAULT_TEXT_SCALE = 2
const val DEFAULT_TEAR_MARGIN_MM = 35

object PrinterBridge {
    private const val USB_PERMISSION_ACTION = "com.foodorder.staff.USB_PERMISSION"

    fun receipt(
        payload: JSONObject,
        protocol: PrinterProtocol,
        labelWidthMm: Int = DEFAULT_LABEL_WIDTH_MM,
        textScale: Int = DEFAULT_TEXT_SCALE,
        tearMarginMm: Int = DEFAULT_TEAR_MARGIN_MM,
        cutterEnabled: Boolean = true,
        encoding: String = "utf-8",
    ): ByteArray = when (protocol) {
        // Paper width (58/80mm) from the paired device's server config is
        // deliberately not applied here yet: it's meaningful for an ESC/POS
        // receipt roll, but the printer auto-wraps by physical width today
        // rather than this code doing manual column wrapping, and TSPL's
        // labelWidthMm is a separately hand-calibrated per-printer setting
        // (see Setup's tear-margin note) that a generic 58/80 value would
        // clobber. Applying it correctly needs a text-wrapping pass, tracked
        // as a follow-up rather than risked here — see the final report's
        // known-limitations list.
        PrinterProtocol.ESC_POS -> escPosReceipt(payload, cutterEnabled, encoding)
        PrinterProtocol.SATO_SBPL -> satoReceipt(payload)
        PrinterProtocol.TSPL -> tsplReceipt(payload, labelWidthMm, textScale, tearMarginMm)
    }

    fun testReceipt(
        protocol: PrinterProtocol,
        labelWidthMm: Int = DEFAULT_LABEL_WIDTH_MM,
        textScale: Int = DEFAULT_TEXT_SCALE,
        tearMarginMm: Int = DEFAULT_TEAR_MARGIN_MM,
        cutterEnabled: Boolean = true,
        encoding: String = "utf-8",
    ): ByteArray {
        val payload = JSONObject()
            .put("target", "cashier")
            .put("order_number", "TEST-001")
            .put("amount_total", 3500)
            .put("currency", "MMK")
            .put("customer", JSONObject().put("name", "Printer Test").put("phone", "-").put("department", "Counter").put("floor", "-").put("note", "Connection OK"))
            .put("lines", org.json.JSONArray().put(JSONObject().put("quantity", 1).put("name", "Americano").put("subtotal", 3500).put("own_cup_quantity", 1)))
        return receipt(payload, protocol, labelWidthMm, textScale, tearMarginMm, cutterEnabled, encoding)
    }

    /** cp874 (Thai codepage) is ASCII-compatible for Latin text but, like
     *  plain "ascii", cannot represent Myanmar glyphs — printers without a
     *  Myanmar-capable firmware/font need one of these two fallbacks
     *  instead of raw UTF-8 (see food.printer.device's encoding field and
     *  docs/PRINTING_SETUP.md). Non-representable characters degrade to
     *  "?" rather than corrupting the rest of the ticket. */
    private fun charsetFor(encoding: String) = when (encoding) {
        "ascii" -> Charsets.US_ASCII
        "cp874" -> runCatching { charset("windows-874") }.getOrDefault(Charsets.US_ASCII)
        else -> Charsets.UTF_8
    }

    private fun escPosReceipt(payload: JSONObject, cutterEnabled: Boolean, encoding: String): ByteArray {
        val output = ByteArrayOutputStream()
        val charset = charsetFor(encoding)
        fun bytes(vararg values: Int) = output.write(values.map(Int::toByte).toByteArray())
        fun text(value: String) = output.write(value.toByteArray(charset))
        val customer = payload.getJSONObject("customer")
        bytes(0x1B, 0x40, 0x1B, 0x61, 0x01, 0x1B, 0x45, 0x01)
        text("FOOD ORDER\n${payload.optString("target").uppercase()} · ${payload.optString("order_number")}\n")
        bytes(0x1B, 0x45, 0x00, 0x1B, 0x61, 0x00)
        text("------------------------------------------\n")
        text("${customer.optString("name")}  ${customer.optString("phone")}\n")
        text("${customer.optString("department")} · ${customer.optString("floor")}\n")
        customer.optString("note").takeIf(String::isNotBlank)?.let { text("NOTE: $it\n") }
        text("------------------------------------------\n")
        val lines = payload.getJSONArray("lines")
        for (index in 0 until lines.length()) {
            val line = lines.getJSONObject(index)
            text("${cleanQuantity(line.optDouble("quantity"))} x ${line.optString("name")}\n")
            val cups = line.optInt("own_cup_quantity")
            if (cups > 0) text("  Own cup x $cups\n")
            line.optString("note").takeIf(String::isNotBlank)?.let { text("  Note: $it\n") }
        }
        text("------------------------------------------\n")
        bytes(0x1B, 0x45, 0x01)
        text("TOTAL: ${"%.0f".format(payload.optDouble("amount_total"))} ${payload.optString("currency")}\n")
        bytes(0x1B, 0x45, 0x00)
        text("\n\n\n")
        if (cutterEnabled) bytes(0x1D, 0x56, 0x00)
        return output.toByteArray()
    }

    private fun satoReceipt(payload: JSONObject): ByteArray {
        fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9 .,:/#()_-]"), "?").take(44)
        val customer = payload.getJSONObject("customer")
        val rows = mutableListOf(
            "FOOD ORDER ${safe(payload.optString("order_number"))}",
            "${safe(customer.optString("name"))} ${safe(customer.optString("phone"))}",
            "${safe(customer.optString("department"))} ${safe(customer.optString("floor"))}",
        )
        val lines = payload.getJSONArray("lines")
        for (index in 0 until lines.length()) {
            val line = lines.getJSONObject(index)
            rows += "${cleanQuantity(line.optDouble("quantity"))}x ${safe(line.optString("name"))}"
            if (line.optInt("own_cup_quantity") > 0) rows += "  OWN CUP x${line.optInt("own_cup_quantity")}"
        }
        rows += "TOTAL ${"%.0f".format(payload.optDouble("amount_total"))} ${safe(payload.optString("currency"))}"
        val commands = StringBuilder("<A>")
        rows.take(14).forEachIndexed { index, row ->
            commands.append("<V>").append(35 + index * 35).append("<H>25<P>2<L>0101<XM>").append(row)
        }
        commands.append("<Q>1<Z>")
        return commands.toString().toByteArray(Charsets.US_ASCII)
    }

    // 203 dpi (~8 dots/mm) is the common resolution on 4BARCODE/TSC-clone label
    // printers; only used to size the canvas from labelWidthMm. Text itself is
    // rendered with Android's own Canvas/Paint (see tsplReceipt) rather than the
    // printer's built-in TSPL fonts, which are Latin-only bitmap/TrueType fonts
    // that cannot draw Myanmar (or any non-Latin) script at all.
    private const val TSPL_DOTS_PER_MM = 8
    private const val TSPL_MARGIN_DOTS = 10
    private const val TSPL_BASE_TEXT_PX = 14f

    private fun tsplReceipt(payload: JSONObject, labelWidthMm: Int, textScale: Int, tearMarginMm: Int): ByteArray {
        val widthDots = labelWidthMm * TSPL_DOTS_PER_MM
        val customer = payload.getJSONObject("customer")
        val body = textScale.coerceAtLeast(1)
        val emphasis = body + 1
        val rule = "-".repeat(40)
        // Pair of (text, isEmphasis). Same ordering ESC/POS uses: quantity+name,
        // then own-cup count, then the item note.
        val rows = mutableListOf<Pair<String, Boolean>>()
        rows += "FOOD ORDER" to true
        rows += "${payload.optString("target").uppercase()} - ${payload.optString("order_number")}" to false
        rows += rule to false
        rows += "${customer.optString("name")}  ${customer.optString("phone")}" to false
        rows += "${customer.optString("department")} - ${customer.optString("floor")}" to false
        customer.optString("note").takeIf(String::isNotBlank)?.let { rows += "NOTE: $it" to false }
        rows += rule to false
        val lines = payload.getJSONArray("lines")
        for (index in 0 until lines.length()) {
            val line = lines.getJSONObject(index)
            rows += "${cleanQuantity(line.optDouble("quantity"))} x ${line.optString("name")}" to false
            val cups = line.optInt("own_cup_quantity")
            if (cups > 0) rows += "  Own cup x $cups" to false
            line.optString("note").takeIf(String::isNotBlank)?.let { rows += "  Note: $it" to false }
        }
        rows += rule to false
        rows += "TOTAL: ${"%.0f".format(payload.optDouble("amount_total"))} ${payload.optString("currency")}" to true

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TSPL_BASE_TEXT_PX * body
        }
        val emphasisPaint = Paint(bodyPaint).apply {
            textSize = TSPL_BASE_TEXT_PX * emphasis
            isFakeBoldText = true
        }
        fun paintFor(isEmphasis: Boolean) = if (isEmphasis) emphasisPaint else bodyPaint

        val lineHeights = rows.map { (_, isEmphasis) ->
            val metrics = paintFor(isEmphasis).fontMetrics
            (metrics.descent - metrics.ascent) + TSPL_MARGIN_DOTS / 2f
        }
        val contentHeightDots = TSPL_MARGIN_DOTS * 2 + lineHeights.sum()
        // On continuous (gapless) stock the printer's only cue for where the next
        // ticket starts is the declared SIZE height, so an underestimate here makes
        // consecutive tickets overlap. Content height itself is exact (measured via
        // Canvas font metrics), so only a small safety margin is needed here - a
        // large one just wastes paper between tickets.
        val heightDots = (contentHeightDots * 1.1f).toInt()
        val heightMm = (heightDots / TSPL_DOTS_PER_MM) + 3
        // Separate concern from the overlap margin above: this is how much blank
        // paper sits between the print head and the tear bar. This is a physical
        // property of the exact printer unit (not something derivable from any
        // spec), so it's a Setup field instead of a guessed constant.
        val trailingFeedDots = tearMarginMm * TSPL_DOTS_PER_MM

        val bitmap = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var y = TSPL_MARGIN_DOTS.toFloat()
        rows.forEachIndexed { index, (text, isEmphasis) ->
            val paint = paintFor(isEmphasis)
            val baseline = y - paint.fontMetrics.ascent
            val textWidth = paint.measureText(text)
            val x = if (isEmphasis) ((widthDots - textWidth) / 2f).coerceAtLeast(TSPL_MARGIN_DOTS.toFloat())
                else TSPL_MARGIN_DOTS.toFloat()
            canvas.drawText(text, x, baseline, paint)
            y += lineHeights[index]
        }

        // Pack the bitmap into TSPL's BITMAP format: 1 bit/pixel, MSB first,
        // each row padded to a byte boundary. The TSC manual documents 1=black,
        // but this printer prints the opposite (a black-bit canvas came out with
        // an inked background and white text) - so bits are set for LIGHT pixels
        // (leave white) and left clear for dark pixels (this printer burns 0s).
        val widthBytes = (widthDots + 7) / 8
        val packed = ByteArray(widthBytes * heightDots)
        val pixels = IntArray(widthDots * heightDots)
        bitmap.getPixels(pixels, 0, widthDots, 0, 0, widthDots, heightDots)
        for (row in 0 until heightDots) {
            val rowOffset = row * widthDots
            val byteRowOffset = row * widthBytes
            for (col in 0 until widthDots) {
                val pixel = pixels[rowOffset + col]
                val luminance = (((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)) / 3
                if (luminance >= 128) {
                    val byteIndex = byteRowOffset + col / 8
                    packed[byteIndex] = (packed[byteIndex].toInt() or (0x80 shr (col % 8))).toByte()
                }
            }
        }
        bitmap.recycle()

        val header = "SIZE $labelWidthMm mm,$heightMm mm\r\n" +
            "GAP 0 mm,0 mm\r\n" + // continuous stock: no gap/black-mark sensing
            "DIRECTION 1\r\n" +
            "CLS\r\n" +
            "BITMAP 0,0,$widthBytes,$heightDots,0,"
        val output = ByteArrayOutputStream()
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(packed)
        output.write("\r\nPRINT 1,1\r\nFEED $trailingFeedDots\r\n".toByteArray(Charsets.US_ASCII))
        return output.toByteArray()
    }

    private fun cleanQuantity(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    fun usbPrinters(context: Context): List<UsbPrinter> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.filter(::hasBulkOutEndpoint).map {
            val vendor = it.manufacturerName ?: "USB"
            val name = it.productName ?: "Printer"
            UsbPrinter(it.deviceId, "$vendor $name · ${it.vendorId}:${it.productId}")
        }
    }

    fun requestUsbPermission(context: Context, deviceId: Int): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return false
        if (manager.hasPermission(device)) return true
        val permission = PendingIntent.getBroadcast(
            context, deviceId, Intent(USB_PERMISSION_ACTION), PendingIntent.FLAG_IMMUTABLE,
        )
        manager.requestPermission(device, permission)
        return false
    }

    fun bluetoothPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true // pre-Android-12: normal, install-time permission
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /** Only ever lists already-paired (bonded) devices — thermal printers are
     *  paired once via Android's own Bluetooth settings like any other
     *  classic-Bluetooth accessory; this app doesn't do its own discovery/
     *  pairing UI. Returns empty (rather than throwing) if the permission
     *  isn't granted yet, so callers can show a "grant permission" prompt
     *  instead of crashing. */
    fun bluetoothPrinters(context: Context): List<BluetoothPrinter> {
        if (!bluetoothPermissionGranted(context)) return emptyList()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.map { device -> BluetoothPrinter(device.address, device.name ?: device.address) }
        }.getOrDefault(emptyList())
    }

    fun print(
        context: Context,
        data: ByteArray,
        networkAddress: String,
        usbDeviceId: Int,
        bluetoothAddress: String = "",
    ) {
        when {
            bluetoothAddress.isNotBlank() -> printBluetooth(context, data, bluetoothAddress)
            networkAddress.isNotBlank() -> printNetwork(data, networkAddress)
            else -> printUsb(context, data, usbDeviceId)
        }
    }

    private fun printBluetooth(context: Context, data: ByteArray, address: String) {
        if (!bluetoothPermissionGranted(context)) {
            throw IllegalStateException("Bluetooth permission not granted. Allow it in Setup, then try again.")
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw IllegalStateException("Bluetooth is not available on this device")
        val adapter = manager.adapter ?: throw IllegalStateException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw IllegalStateException("Turn Bluetooth on, then try again")
        val device = adapter.bondedDevices.firstOrNull { it.address == address }
            ?: throw IllegalStateException("Printer not paired. Pair it in Android Bluetooth settings first.")
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery() // discovery in progress slows/blocks a fresh connect attempt
            socket.connect()
            socket.outputStream.use { it.write(data); it.flush() }
        } finally {
            socket?.close()
        }
    }

    private fun printNetwork(data: ByteArray, address: String) {
        val cleanAddress = address.trim().removePrefix("tcp://")
        val parts = cleanAddress.split(":", limit = 2)
        require(parts[0].isNotBlank()) { "Enter the printer IP address" }
        val socket = Socket()
        socket.connect(InetSocketAddress(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 9100), 10_000)
        socket.soTimeout = 10_000
        try {
            socket.getOutputStream().use { it.write(data); it.flush() }
        } finally {
            socket.close()
        }
    }

    private fun printUsb(context: Context, data: ByteArray, selectedDeviceId: Int) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { it.deviceId == selectedDeviceId }
            ?: manager.deviceList.values.firstOrNull(::hasBulkOutEndpoint)
            ?: throw IllegalStateException("No compatible USB printer connected")
        if (!manager.hasPermission(device)) {
            requestUsbPermission(context, device.deviceId)
            throw IllegalStateException("USB permission requested. Allow it, then try again.")
        }
        val usbInterface = (0 until device.interfaceCount).map(device::getInterface).first {
            (0 until it.endpointCount).any { endpointIndex ->
                val endpoint = it.getEndpoint(endpointIndex)
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT
            }
        }
        val endpoint = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint).first {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT
        }
        val connection = manager.openDevice(device) ?: throw IllegalStateException("Cannot open USB printer")
        try {
            check(connection.claimInterface(usbInterface, true)) { "Cannot claim USB printer interface" }
            var offset = 0
            while (offset < data.size) {
                val chunk = data.copyOfRange(offset, minOf(offset + 16_384, data.size))
                val written = connection.bulkTransfer(endpoint, chunk, chunk.size, 15_000)
                if (written <= 0) throw IllegalStateException("USB printer stopped while writing")
                offset += written
            }
        } finally {
            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }

    private fun hasBulkOutEndpoint(device: UsbDevice): Boolean = (0 until device.interfaceCount)
        .map(device::getInterface)
        .any { usbInterface ->
            (0 until usbInterface.endpointCount).any {
                val endpoint = usbInterface.getEndpoint(it)
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT
            }
        }
}
