const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const root = path.resolve(__dirname, "..");
const configPath = process.env.FOOD_PRINT_CONFIG || path.join(root, "config.json");
if (!fs.existsSync(configPath)) {
  console.error(`Missing ${configPath}. Copy config.example.json to config.json and configure it.`);
  process.exit(1);
}
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
const ledgerPath = path.join(root, "printed-jobs.json");
let printedJobs = new Set();
try {
  printedJobs = new Set(JSON.parse(fs.readFileSync(ledgerPath, "utf8")));
} catch (_) {}

const ESC = Buffer.from([0x1b]);
const GS = Buffer.from([0x1d]);
// Dependency-free by design (see README): only UTF-8 passthrough and an
// ASCII-safe fallback are implemented. True codepage transcoding (e.g. for
// legacy printers without Unicode firmware) would need iconv-lite and is
// documented as a hardware-commissioning limitation in
// docs/PRINTING_SETUP.md instead of adding a new dependency here.
const asciiSafe = (value) => String(value || "").replace(/[^\x20-\x7e]/g, "?");
const encodeText = (value, encoding) =>
  Buffer.from(encoding === "ascii" ? asciiSafe(value) : String(value ?? ""), "utf8");
const widthForPaper = (paperWidthMm) => (String(paperWidthMm) === "58" ? 32 : 42);
const line = (left, right = "", width = 42) => {
  const lhs = String(left);
  const rhs = String(right);
  return `${lhs}${" ".repeat(Math.max(1, width - lhs.length - rhs.length))}${rhs}\n`;
};

function receipt(payload, options = {}) {
  const encoding = options.encoding || "utf-8";
  const width = widthForPaper(options.paperWidthMm);
  const cutterEnabled = options.cutterEnabled !== false;
  const text = (value) => encodeText(value, encoding);
  const customer = payload.customer || {};
  const chunks = [
    ESC, Buffer.from([0x40]),
    ESC, Buffer.from([0x61, 0x01]),
    ESC, Buffer.from([0x45, 0x01]), text("FOOD ORDER\n"),
    text(`${payload.target.toUpperCase()} - ${payload.order_number}\n`),
    ESC, Buffer.from([0x45, 0x00, 0x61, 0x00]),
    text("-".repeat(width) + "\n"),
    text(`Name: ${customer.name}\nPhone: ${customer.phone}\n`),
    text(`Department: ${customer.department}  Floor: ${customer.floor}\n`),
    text(`Own cup: ${customer.own_cup ? "YES" : "NO"}\n`),
  ];
  if (payload.promotion) chunks.push(text(`Promotion: ${payload.promotion}\n`));
  if (customer.note) chunks.push(ESC, Buffer.from([0x45, 0x01]), text(`NOTE: ${customer.note}\n`), ESC, Buffer.from([0x45, 0x00]));
  chunks.push(text("-".repeat(width) + "\n"));
  for (const item of payload.lines || []) {
    chunks.push(text(line(`${item.quantity} x ${item.name}`, Number(item.subtotal).toFixed(2), width)));
    if (Number(item.own_cup_quantity || 0) > 0) chunks.push(text(`  Own cup x ${item.own_cup_quantity}\n`));
    for (const modifier of item.modifier_details || []) {
      const extra = Number(modifier.price_extra || 0);
      chunks.push(text(`  ${modifier.group}: ${modifier.option}${extra ? " (+" + extra.toFixed(0) + ")" : ""}\n`));
    }
    if (item.note) chunks.push(text(`  Note: ${item.note}\n`));
  }
  chunks.push(
    text("-".repeat(width) + "\n"),
    ESC, Buffer.from([0x45, 0x01]), text(line("TOTAL", `${Number(payload.amount_total).toFixed(2)} ${payload.currency}`, width)),
    ESC, Buffer.from([0x45, 0x00]), text("\n\n\n"),
  );
  if (cutterEnabled) chunks.push(GS, Buffer.from([0x56, 0x00]));
  return Buffer.concat(chunks);
}

function satoReceipt(payload) {
  const safe = (value) => String(value || "").replace(/[^A-Za-z0-9 .,:/#()_-]/g, "?").slice(0, 44);
  const customer = payload.customer || {};
  const rows = [
    `FOOD ORDER ${safe(payload.order_number)}`,
    `${safe(customer.name)} ${safe(customer.phone)}`,
    `${safe(customer.department)} ${safe(customer.floor)}`,
  ];
  for (const item of payload.lines || []) {
    rows.push(`${item.quantity}x ${safe(item.name)}`);
    if (Number(item.own_cup_quantity || 0) > 0) rows.push(`  OWN CUP x${item.own_cup_quantity}`);
    if (item.note) rows.push(`  NOTE ${safe(item.note)}`);
  }
  rows.push(`TOTAL ${Number(payload.amount_total || 0).toFixed(0)} ${safe(payload.currency)}`);
  let commands = "<A>";
  rows.slice(0, 14).forEach((row, index) => {
    commands += `<V>${35 + index * 35}<H>25<P>2<L>0101<XM>${safe(row)}`;
  });
  return Buffer.from(commands + "<Q>1<Z>", "ascii");
}

function printNetwork(data) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection(
      { host: config.printer.host, port: config.printer.port || 9100, timeout: 10000 },
      () => socket.end(data)
    );
    socket.on("close", (hadError) => !hadError && resolve());
    socket.on("timeout", () => socket.destroy(new Error("Printer connection timed out")));
    socket.on("error", reject);
  });
}

function printWindows(data) {
  return new Promise((resolve, reject) => {
    const tempFile = path.join(os.tmpdir(), `food-order-${Date.now()}.bin`);
    fs.writeFileSync(tempFile, data);
    const helper = path.join(root, "windows-raw-print.ps1");
    const child = spawn("powershell.exe", [
      "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", helper,
      "-PrinterName", config.printer.name, "-FilePath", tempFile,
    ], { windowsHide: true });
    let error = "";
    child.stderr.on("data", chunk => { error += chunk.toString(); });
    child.on("exit", code => {
      try { fs.unlinkSync(tempFile); } catch (_) {}
      code === 0 ? resolve() : reject(new Error(error || `Windows print helper exited ${code}`));
    });
    child.on("error", reject);
  });
}

async function acknowledge(jobId, success, error = "") {
  const response = await fetch(`${config.serverUrl}/api/food/v1/print/jobs/${jobId}/ack`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Device-Key": config.deviceKey },
    body: JSON.stringify({ success, error }),
  });
  if (!response.ok) throw new Error(`Ack failed: HTTP ${response.status}`);
}

function saveLedger() {
  const values = Array.from(printedJobs).slice(-10000);
  fs.writeFileSync(ledgerPath, JSON.stringify(values));
}

async function processJob(job) {
  // Keyed by id+template_version, not just id: a reprint reuses the same
  // job row with a bumped template_version, so this still prints it while
  // a genuine duplicate delivery of the exact same version is skipped.
  const ledgerKey = `${job.id}:${job.template_version || 1}`;
  if (printedJobs.has(ledgerKey)) {
    await acknowledge(job.id, true);
    return;
  }
  try {
    const protocol = String(config.protocol || "escpos").toLowerCase();
    const options = {
      encoding: job.encoding, paperWidthMm: job.paper_width_mm, cutterEnabled: job.cutter_enabled,
    };
    const data = protocol === "sato_sbpl" ? satoReceipt(job.payload) : receipt(job.payload, options);
    if (config.printer.type === "network") await printNetwork(data);
    else if (config.printer.type === "windows") await printWindows(data);
    else throw new Error(`Unsupported printer type: ${config.printer.type}`);
    printedJobs.add(ledgerKey);
    saveLedger();
    await acknowledge(job.id, true);
    console.log(`Printed job ${job.id} / ${job.payload.order_number}`);
  } catch (error) {
    console.error(`Print job ${job.id} failed:`, error.message);
    try { await acknowledge(job.id, false, error.message); } catch (ackError) { console.error(ackError.message); }
  }
}

async function poll() {
  try {
    const response = await fetch(`${config.serverUrl}/api/food/v1/print/jobs`, {
      headers: { "X-Device-Key": config.deviceKey },
    });
    if (!response.ok) throw new Error(`Poll failed: HTTP ${response.status}`);
    const body = await response.json();
    for (const job of body.jobs || []) await processJob(job);
  } catch (error) {
    console.error(new Date().toISOString(), error.message);
  } finally {
    setTimeout(poll, Math.max(1000, config.pollIntervalMs || 3000));
  }
}

console.log(`Food print agent started: ${config.printer.type} / ${config.protocol || "escpos"}`);
poll();
