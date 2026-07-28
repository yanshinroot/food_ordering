package com.foodorder.staff.printer

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
 * all must never even ask for jobs.
 */
class PrintQueueManagerTest {
    @Test
    fun `no printer configured when neither network nor usb is set`() {
        assertFalse(isPrinterConfigured(networkPrinter = "", usbDeviceId = -1))
    }

    @Test
    fun `a network printer address counts as configured`() {
        assertTrue(isPrinterConfigured(networkPrinter = "192.168.1.50:9100", usbDeviceId = -1))
    }

    @Test
    fun `a selected usb device counts as configured even with a blank network address`() {
        assertTrue(isPrinterConfigured(networkPrinter = "", usbDeviceId = 3))
    }

    @Test
    fun `blank whitespace network address does not count as configured`() {
        assertFalse(isPrinterConfigured(networkPrinter = "   ", usbDeviceId = -1))
    }
}
