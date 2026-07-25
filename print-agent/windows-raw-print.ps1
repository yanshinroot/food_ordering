param(
    [Parameter(Mandatory = $true)][string]$PrinterName,
    [Parameter(Mandatory = $true)][string]$FilePath
)
$source = @'
using System;
using System.IO;
using System.Runtime.InteropServices;
public static class RawPrinter {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public class DOCINFO { public string pDocName; public string pOutputFile; public string pDataType; }
    [DllImport("winspool.drv", SetLastError=true, CharSet=CharSet.Unicode)] static extern bool OpenPrinter(string name, out IntPtr printer, IntPtr defaults);
    [DllImport("winspool.drv", SetLastError=true)] static extern bool ClosePrinter(IntPtr printer);
    [DllImport("winspool.drv", SetLastError=true, CharSet=CharSet.Unicode)] static extern int StartDocPrinter(IntPtr printer, int level, DOCINFO info);
    [DllImport("winspool.drv", SetLastError=true)] static extern bool EndDocPrinter(IntPtr printer);
    [DllImport("winspool.drv", SetLastError=true)] static extern bool StartPagePrinter(IntPtr printer);
    [DllImport("winspool.drv", SetLastError=true)] static extern bool EndPagePrinter(IntPtr printer);
    [DllImport("winspool.drv", SetLastError=true)] static extern bool WritePrinter(IntPtr printer, byte[] data, int count, out int written);
    public static void Send(string printerName, string path) {
        byte[] data = File.ReadAllBytes(path); IntPtr printer;
        if (!OpenPrinter(printerName, out printer, IntPtr.Zero)) throw new System.ComponentModel.Win32Exception();
        try {
            var info = new DOCINFO { pDocName = "Odoo Food Order", pDataType = "RAW" };
            if (StartDocPrinter(printer, 1, info) == 0) throw new System.ComponentModel.Win32Exception();
            try { StartPagePrinter(printer); int written; if (!WritePrinter(printer, data, data.Length, out written) || written != data.Length) throw new System.ComponentModel.Win32Exception(); EndPagePrinter(printer); }
            finally { EndDocPrinter(printer); }
        } finally { ClosePrinter(printer); }
    }
}
'@
Add-Type -TypeDefinition $source
[RawPrinter]::Send($PrinterName, $FilePath)
