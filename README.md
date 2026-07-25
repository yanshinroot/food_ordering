# Odoo 19 Food Ordering System

Production-oriented foundation for guest web ordering, cashier/kitchen operations, own-cup discounts, reliable receipt printing, and an Android cashier client.

## Live local system

- Customer catalog: `http://localhost:8069/food`
- Odoo backend: `http://localhost:8069/odoo`
- Database: `odoo`
- Odoo module: `food_ordering` (installed)
- Starter products: Americano, Milk Tea, Chicken Rice, Avocado Smoothie
- Own-cup discount: 500 MMK per eligible drink

Customers do not need an Odoo account. Checkout requires Name, Phone Number, Department, Floor and accepts an optional Note. Own Cup quantity is selectable from zero up to the purchased quantity and only discounts the selected eligible beverage units.

The customer storefront is a custom responsive interface (not the standard Odoo shop form): searchable/filterable product cards, original menu photography, an AJAX slide-out cart, a dedicated delivery-details checkout and a live order-status timeline. It adapts to desktop, tablet and mobile screens.

Products use a simple one-click **Add** flow. In the basket, customers can save a separate note and own-cup quantity on each eligible line. Checkout delivery fields live inside the basket drawer, and item notes/own-cup quantities are carried through sale-order lines and receipt payloads. Guest customers can revisit orders from **Recent orders** in the same browser session without creating an account.

## Real workflow

1. A guest places an order on `/food`, or a client posts to `/api/food/v1/orders`.
2. Odoo creates a draft Sales Order with food status `pending`.
3. Cashier accepts an online order, or creates a **New walk-in** order from the dedicated cashier screen.
4. Cashier collects **Cash** payment. Cash tender calculates change.
5. Odoo confirms the Sales Order and atomically creates Cashier and Kitchen print jobs.
6. Each printer agent claims only jobs for its target and sends raw ESC/POS data to the configured printer.
7. The agent acknowledges success/failure. Failed jobs remain visible and retryable.
8. Kitchen moves the order through `accepted → preparing → ready`.
9. Cashier marks the paid, collected/delivered order `completed`. Unpaid orders cannot be completed.

The browser never attempts silent USB printing. That is intentionally delegated to a trusted local agent because browser security does not provide reliable unattended USB/network printing.

## Components

- `custom_addons/food_ordering`: Odoo 19 module, views, guest website and versioned HTTP API.
- `print-agent`: dependency-free Node.js 20+ bridge for network ESC/POS and Windows raw USB/OS printers.
- `android-app`: native Kotlin/Jetpack Compose cashier app supporting network/USB ESC/POS and SATO SBPL.
- `compose.yaml`: reproducible Odoo/PostgreSQL deployment.

## Dedicated staff web screens

- `/food/cashier`: authenticated full-screen cashier queue with Accept & Print, cancellation, live status and completion actions.
- `/food/kitchen`: authenticated kitchen display with Queue, Preparing and Ready columns.
- Both screens refresh every five seconds, show live order age, customer delivery location, item-level notes and own-cup choices.
- Assign users to **Food Cashier** or **Food Kitchen** under Odoo access rights. Food Managers can open and switch between both screens.
- The screens are also available from **Food Ordering → Cashier Screen** and **Food Ordering → Kitchen Screen**.

## Printer setup

1. Open **Food Ordering → Printer Devices** as Food Manager.
2. Configure the generated Cashier and Kitchen devices. Copy each secret Device Key once.
3. For Windows/network printing, copy the appropriate `print-agent/config.*.example.json` to `config.json` and fill in the device key and exact printer address.
4. Run `npm start` from `print-agent`. Use one agent per physical printer/target.

Network ESC/POS printers normally use TCP port 9100. For USB, install the vendor's Windows printer driver and use the exact Windows printer name. Actual paper output must be commissioned with the target printer model because code pages, cutter commands, paper width and Myanmar glyph support vary by hardware.

For cloud Odoo, keep the printer private at the shop and run the Android bridge or local Node print agent. The bridge polls Odoo over outbound HTTPS, so no printer port is exposed to the internet. See `CLOUD_PRINTING.md` for topology, configuration, monitoring and commissioning.

## Android setup

Install the supplied debug APK on Android 8.0 or newer, or open `android-app` in current Android Studio with Android SDK 35 to build it. The app uses the official Android USB Host API. Choose a detected USB printer and grant permission, or enter `IP:9100` for a network printer. Choose ESC/POS for compatible Nippon/thermal receipt printers, or SATO SBPL only when the exact SATO model is configured for that language.

Android emulator reaches local Odoo at `http://10.0.2.2:8069`. A physical Android device must use this computer's trusted-LAN IP, and TCP 8069 must be allowed only on the Windows Private network. The supplied development mapping listens on the LAN; do not router-forward it or use it as the cloud production exposure.

## API summary

- `GET /api/food/v1/catalog`: public menu.
- `POST /api/food/v1/orders`: guest order creation.
- `GET /api/food/v1/orders/{access_token}`: private-link customer status.
- `GET /api/food/v1/staff/orders?status=active|recent`: authenticated live queue or latest 50 completed/cancelled orders.
- `POST /api/food/v1/staff/orders/{id}/{action}`: device-role-checked state transition.
- `POST /api/food/v1/staff/orders/{id}/reprint`: safely requeue a receipt for the calling cashier/kitchen role.
- `GET /api/food/v1/print/jobs`: claim device-targeted print jobs.
- `GET /api/food/v1/print/jobs/failed`: list failed jobs for the device.
- `POST /api/food/v1/print/jobs/{id}/retry`: requeue a failed job.
- `POST /api/food/v1/print/jobs/{id}/ack`: print success/failure acknowledgement.

Staff and print endpoints require `X-Device-Key`. Device keys are generated with cryptographically secure randomness and should be rotated if exposed.

## Before real production

- Put Odoo behind HTTPS and a reverse proxy; never publish port 8069 directly to the internet.
- Cash-only Walk-in checkout and cashier payment recording are included. Accounting reconciliation is a separate production integration.
- Configure real taxes, company details, fiscal policy and order cancellation/refund policy.
- Add rate limiting/CAPTCHA at the reverse proxy for public order creation.
- Replace Odoo's default database master password and configure database-list filtering.
- Back up both PostgreSQL and `odoo19-web-data`; attachments require both.
- Commission cashier and kitchen printer models using real receipts, including Myanmar text rendering.
- Build/sign Android release APK/AAB and store device keys in Android Keystore for the release build.
- Add notification transport (Firebase/SMS/email) if customers need push updates.

## Verified locally

- Odoo 19 module installation and database tables.
- Public catalog and responsive guest checkout at desktop and 390px mobile width.
- Guest API order creation without an Odoo account.
- Required customer information persistence.
- Own-cup eligibility and a -500 MMK discount line.
- Cashier acceptance, Sales Order confirmation and role checks.
- Two queued print jobs per accepted order (cashier and kitchen).
- Tokenized public order status.
- Node and PowerShell print-agent syntax.

Android debug APK compilation (API 35) and package metadata were verified. Physical printer output remains a hardware commissioning step because no target printer was attached during development.
