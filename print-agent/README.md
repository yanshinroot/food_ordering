# Food Ordering Print Agent

This bridge provides silent automatic printing. Browsers and cloud servers cannot reliably access a USB or private-LAN receipt printer directly. Keep this agent running on a computer at the shop; it makes an outbound HTTPS connection to cloud Odoo, claims its device-targeted jobs, and writes them to the local printer.

1. In Odoo, open **Food Ordering → Printer Devices** and create a Cashier or Kitchen device.
2. Copy `config.example.json` to `config.json` for a network ESC/POS printer, or use the Windows USB example.
3. Put the Odoo-generated device key and exact printer address/name in `config.json`.
4. Run `npm start` from this directory. No npm packages are required on Node.js 20+.

Run one agent per printer/target. The agent records printed job IDs locally so an acknowledgement retry does not print a duplicate receipt.

For cloud deployment, set `serverUrl` to the public HTTPS Odoo URL, for example `https://orders.example.com`. No inbound port or public printer IP is required. Allow outbound HTTPS from the shop computer.

Set `protocol` to `escpos` for Nippon/compatible thermal POS printers or `sato_sbpl` for a SATO printer configured for SBPL. Both `network` raw TCP and Windows raw-printer transports use the selected protocol. Exact SATO label dimensions and command support must be commissioned against the model.
