# Food Order Staff Android App

Native Kotlin/Jetpack Compose cashier and kitchen client for Odoo 19 Food Ordering. It supports live operations, recent-order history, walk-in POS, payments, status transitions, role-targeted printing and failed-job retry.

## Install

Install `FoodOrderStaff-1.4.0-debug.apk` on Android 8.0 (API 26) or newer. This is a debug-signed test build; create a release-signed APK/AAB before production distribution.

Version 1.4.0 includes the full staff workflow:

- Cashier live queue, Accept, Cancel, payment collection and Complete.
- Walk-in POS basket with quantity, per-item notes and own-cup quantity.
- Cash-only payment with cash-received and change calculation.
- Kitchen queue with Start preparing and Mark ready actions.
- Active/Recent tabs, order/customer/phone search and role-specific reprint.
- Tablet master-detail workspace: a compact queue on the left and a large, scrollable selected-order panel on the right. Cashier/Kitchen role switching has its own top row; order mode, search, connectivity and Walk-in controls sit below it.
- Full-screen tablet Walk-in POS with searchable products, basket quantities, per-item notes, own-cup quantities and cash checkout.
- Loud alarm-stream alert and vibration when a genuinely new active order arrives after the initial screen load.
- Three-second background print polling while the staff screen is open; every claimed job is acknowledged even when another job in the batch fails.
- The tablet screen stays awake during live operations.
- Separate cashier and kitchen device keys with server-side role enforcement.
- Optional printing; orders continue to work without a connected printer.

## First setup

1. Open **Settings** in the app.
2. Set the Odoo URL:
   - Android emulator: `http://10.0.2.2:8069`
   - Physical phone: `http://<PC-LAN-IP>:8069`
3. In Odoo, open **Food Ordering > Printer Devices** and paste the key required by this tablet. A shared tablet may store both keys; a dedicated kitchen tablet needs only the Kitchen key.
4. Select the printer language:
   - **ESC/POS** for Nippon and compatible thermal POS receipt printers.
   - **SATO SBPL** for a SATO printer configured for SBPL. Confirm the language and label setup in the exact model manual.
5. Select a connection:
   - **Network:** enter `192.168.1.50:9100`, then tap **Test printer**.
   - **USB:** connect by USB OTG, select the printer, tap **Grant USB permission**, then **Test printer**.

The Android USB permission belongs to the connected device and may need to be granted again after reconnecting it. Keep the app open during cashier operation if it is the device responsible for automatic printing.

## Approve and print flow

1. The app refreshes active or recent orders every eight seconds and polls its role-targeted print queue every three seconds.
2. **Accept & print** confirms the Odoo order and creates separate cashier and kitchen print jobs.
3. The Android cashier app claims and prints only the cashier job.
4. A kitchen-target print agent or device claims the kitchen job.
5. If printing fails, open **Print queue** to retry failed jobs. Batch failures do not leave later jobs stuck in claimed state.

## Local network requirement

The supplied Docker configuration publishes Odoo on the computer's LAN so a physical phone can connect. Allow TCP 8069 only on the Windows **Private** network, keep the phone and computer on the same Wi-Fi, and do not forward port 8069 on the router or expose an unprotected Odoo instance to the internet.

## Build from source

Open this directory in a current Android Studio release with Android SDK 35 installed, or run:

```powershell
.\gradlew.bat assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`.

## Printer commissioning

Raw printing is implemented for common ESC/POS TCP/USB printers and basic SATO SBPL. Real printers still require a test with the exact model, paper or label size, code page, cutter, USB interface, and Myanmar-text requirements. Some SATO models need model-specific SBPL positioning commands or a different emulation.
