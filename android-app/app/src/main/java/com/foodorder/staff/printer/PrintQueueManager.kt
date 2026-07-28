package com.foodorder.staff.printer

import android.content.Context
import com.foodorder.staff.PrinterBridge
import com.foodorder.staff.core.ApiResult
import com.foodorder.staff.net.FoodApiClient
import com.foodorder.staff.storage.UiPreferences

data class PrintBatchResult(val printed: Int, val failed: Int)

/**
 * Claim → print → acknowledge loop shared by both flavors. The server is
 * the sole authority on which jobs a device may see at all: `GET
 * /api/food/v1/print/jobs` only ever returns jobs whose `target` matches
 * the calling device's own `target` (see controllers/api.py `print_jobs`,
 * which claims with `WHERE target = device.target`), so a Kitchen-paired
 * device physically cannot receive a Cashier-target job's payload and vice
 * versa — this class never needs to (and does not) filter by role itself.
 *
 * A job is never marked printed locally before the physical print call
 * succeeds, and is never printed at all before the server's claim
 * (`FOR UPDATE SKIP LOCKED`) hands it to this device.
 */
/** Pure — pulled out of PrintQueueManager so it's testable without a real
 *  Context-backed UiPreferences/SharedPreferences instance. */
fun isPrinterConfigured(networkPrinter: String, usbDeviceId: Int): Boolean =
    networkPrinter.isNotBlank() || usbDeviceId >= 0

class PrintQueueManager(private val context: Context, private val api: FoodApiClient, private val prefs: UiPreferences) {
    fun printerConfigured(): Boolean = isPrinterConfigured(prefs.networkPrinter, prefs.usbDeviceId)

    suspend fun claimAndPrintQueued(): PrintBatchResult {
        if (!printerConfigured()) return PrintBatchResult(0, 0)
        val jobsResult = api.printJobs()
        val jobs = when (jobsResult) {
            is ApiResult.Success -> jobsResult.value
            is ApiResult.Failure -> return PrintBatchResult(0, if (jobsResult.error is com.foodorder.staff.core.ApiError.Network) 0 else 1)
        }
        return printAndAck(jobs)
    }

    suspend fun retryFailedThenPrint(): PrintBatchResult {
        if (!printerConfigured()) return PrintBatchResult(0, 0)
        val failedResult = api.failedPrintJobs()
        if (failedResult is ApiResult.Success) {
            failedResult.value.forEach { job -> api.retryPrintJob(job.id) }
        }
        return claimAndPrintQueued()
    }

    private suspend fun printAndAck(jobs: List<com.foodorder.staff.net.PrintJob>): PrintBatchResult {
        var printed = 0
        var failed = 0
        jobs.forEach { job ->
            try {
                val bytes = PrinterBridge.receipt(
                    job.payload,
                    prefs.protocol,
                    prefs.labelWidthMm,
                    prefs.textScale,
                    prefs.tearMarginMm,
                    cutterEnabled = job.cutterEnabled,
                    encoding = job.encoding,
                )
                PrinterBridge.print(context, bytes, prefs.networkPrinter, prefs.usbDeviceId)
                api.acknowledgePrint(job.id, success = true)
                printed++
            } catch (error: Exception) {
                // Local printing failed after a successful server claim — ack the
                // failure so the job goes back to the retry queue instead of
                // sitting invisibly "claimed" until the 5-minute stale-claim sweep.
                api.acknowledgePrint(job.id, success = false, error = error.message ?: "Print failed")
                failed++
            }
        }
        return PrintBatchResult(printed, failed)
    }
}
