# Cloud Printing Architecture

The cloud Odoo server must not connect directly to a printer on a shop's private LAN or USB bus. Printing uses an outbound polling bridge:

```text
Customer / cashier website
          |
          v
Cloud Odoo over HTTPS
  durable target print queue
          ^
          | outbound HTTPS polling with X-Device-Key
          |
Android staff app OR local Node print agent
          |
          +--> USB Host / Windows raw printer
          +--> private-LAN printer IP:9100
```

No router port-forwarding or public printer IP is required. The shop device only needs outbound HTTPS access to Odoo and local access to the printer.

## Recommended production arrangement

- Run Odoo behind HTTPS, for example `https://orders.example.com`.
- Run one always-on local print agent per target printer: Cashier and Kitchen.
- Prefer the Windows Node agent for unattended printing. The Android app is suitable while the staff screen is open and the tablet remains powered.
- Use a unique device key for every target and rotate all development keys before going live.
- Keep printer TCP 9100 private to the shop LAN.

## Network ESC/POS or SATO

Copy `print-agent/config.example.json` to `print-agent/config.json`:

```json
{
  "serverUrl": "https://orders.example.com",
  "deviceKey": "COPY_FROM_ODOO_PRINTER_DEVICE",
  "pollIntervalMs": 3000,
  "protocol": "escpos",
  "printer": {
    "type": "network",
    "host": "192.168.1.50",
    "port": 9100
  }
}
```

Use `protocol: "sato_sbpl"` only for a SATO model configured for SBPL.

## Windows USB / installed printer

Copy `config.windows-usb.example.json` to `config.json`, then set the exact Windows printer name. Install the vendor driver first. The agent sends raw bytes through the Windows spooler.

Start the bridge:

```powershell
cd print-agent
npm start
```

Use Windows Task Scheduler or a service wrapper to start the agent at boot under a restricted local account. Protect `config.json` because it contains the device key.

## Monitoring

- Cashier and Kitchen web screens show `Printer bridge online/offline`, queued jobs, and failed jobs.
- `Food Ordering > Printer Devices` shows the last heartbeat and health counts.
- `Food Ordering > Print Jobs` shows every durable job and provides Retry for failures.
- A bridge is considered offline if Odoo has not received a poll for 30 seconds.

## Commissioning checklist

1. Test one receipt with the exact printer model.
2. Verify paper/label width, cutter, cash-drawer pulse, code page and Myanmar text.
3. Disconnect the network/USB cable and confirm the job becomes Failed.
4. Reconnect, Retry, and confirm exactly one physical receipt prints.
5. Restart the local bridge and confirm its duplicate ledger prevents reprinting an acknowledged job.
6. Test an internet outage and recovery.

Software compilation and queue/acknowledgement behavior do not prove physical compatibility. Final acceptance requires the target SATO or Nippon printer hardware.
