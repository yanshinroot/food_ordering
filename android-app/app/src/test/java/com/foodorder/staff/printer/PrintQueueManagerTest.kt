package com.foodorder.staff.printer

import com.foodorder.staff.PrinterTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenarios 21 & 22 (print claim-before-print, print acknowledgement after
 * success): the ordering guarantee itself — a job is only ever printed
 * after the server's `FOR UPDATE SKIP LOCKED` claim hands it to this
 * device (see controllers/api.py print_jobs), and is only ever acked
 * "printed" after PrinterBridge.print() returns without throwing — is
 * structural in PrintQueueManager.printAndAck: it iterates the jobs
 * *returned by* `api.printJobs()` and only calls `api.acknowledgePrint(id,
 * success = true)` inside the same try block as the print call, after it
 * returns. FoodApiClient is a concrete class over a real HttpURLConnection
 * with no injectable transport seam, so that full claim→print→ack
 * sequence isn't mockable here without adding a network-mocking dependency
 * this pass deliberately didn't introduce (see the final report's known
 * limitations). What *is* independently unit-tested is the precondition
 * every print attempt is gated on: a device with no printer configured at
 * all must never even ask for jobs — and specifically, only the field
 * matching the currently-selected transport counts.
 */
class PrintQueueManagerTest {
    @Test
    fun `nothing configured on any transport`() {
        assertFalse(isPrinterConfigured(PrinterTransport.NETWORK, "", -1, ""))
        assertFalse(isPrinterConfigured(PrinterTransport.USB, "", -1, ""))
        assertFalse(isPrinterConfigured(PrinterTransport.BLUETOOTH, "", -1, ""))
    }

    @Test
    fun `a network printer address counts as configured only for the network transport`() {
        assertTrue(isPrinterConfigured(PrinterTransport.NETWORK, "192.168.1.50:9100", -1, ""))
        assertFalse(isPrinterConfigured(PrinterTransport.USB, "192.168.1.50:9100", -1, ""))
    }

    @Test
    fun `a selected usb device counts as configured only for the usb transport`() {
        assertTrue(isPrinterConfigured(PrinterTransport.USB, "", 3, ""))
        assertFalse(isPrinterConfigured(PrinterTransport.BLUETOOTH, "", 3, ""))
    }

    @Test
    fun `a paired bluetooth address counts as configured only for the bluetooth transport`() {
        assertTrue(isPrinterConfigured(PrinterTransport.BLUETOOTH, "", -1, "AA:BB:CC:DD:EE:FF"))
        assertFalse(isPrinterConfigured(PrinterTransport.NETWORK, "", -1, "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `blank whitespace network address does not count as configured`() {
        assertFalse(isPrinterConfigured(PrinterTransport.NETWORK, "   ", -1, ""))
    }
}
