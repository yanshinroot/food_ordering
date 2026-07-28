# Food Ordering Print Agent

This bridge provides silent automatic printing. Browsers and cloud servers cannot reliably access a USB or private-LAN receipt printer directly. Keep this agent running on a computer at the shop; it makes an outbound HTTPS connection to cloud Odoo, claims its device-targeted jobs, and writes them to the local printer.

1. In Odoo, open **Food Ordering → Printer Devices** and create a Cashier or Kitchen device.
2. Copy `config.example.json` to `config.json` for a network ESC/POS printer, `config.windows-usb.example.json` for a USB printer installed as a Windows printer, or one of the `config.bluetooth-*.example.json` files for a Bluetooth printer (see below).
3. Put the Odoo-generated device key and exact printer address/name in `config.json`.
4. Run `npm start` from this directory. No npm packages are required on Node.js 20+.

Run one agent per printer/target. The agent records printed job IDs locally so an acknowledgement retry does not print a duplicate receipt.

For cloud deployment, set `serverUrl` to the public HTTPS Odoo URL, for example `https://orders.example.com`. No inbound port or public printer IP is required. Allow outbound HTTPS from the shop computer.

Set `protocol` to `escpos` for Nippon/compatible thermal POS printers or `sato_sbpl` for a SATO printer configured for SBPL. All transports (`network`, `windows`, `bluetooth`) use the selected protocol. Exact SATO label dimensions and command support must be commissioned against the model.

### Bluetooth printer setup

The printer is paired with the computer running this agent first (once), then this agent talks to it as a plain serial device — no Bluetooth npm package needed, same dependency-free design as everything else here.

**Linux:** pair the printer once (e.g. `bluetoothctl` → `pair AA:BB:CC:DD:EE:FF`, `trust AA:BB:CC:DD:EE:FF`), then bind it to a device file:

```
sudo rfcomm bind rfcomm0 AA:BB:CC:DD:EE:FF 1
```

Use `config.bluetooth-linux.example.json` and set `printer.devicePath` to `/dev/rfcomm0`. To make the bind survive a reboot, add it to a systemd unit or `/etc/rc.local` rather than running it by hand each time.

**Windows:** pair the printer in **Settings → Bluetooth & devices**; Windows assigns it an outgoing COM port automatically (check **Bluetooth → More Bluetooth options → COM Ports**). Use `config.bluetooth-windows.example.json` and set `printer.comPort` to that port (e.g. `COM5`).

Either way, the same computer can run one agent per printer — a kitchen printer used from both Odoo web (via this agent) and the Android app (via its own in-app Bluetooth support) is the same physical device, just reached through two independent paths.
