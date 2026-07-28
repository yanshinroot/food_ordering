(function () {
    "use strict";

    function ready(callback) {
        if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", callback);
        else callback();
    }

    // ------------------------------------------------------------------
    // Translation-ready strings shared by Cashier and Kitchen.
    // ------------------------------------------------------------------
    const STRINGS = {
        en: {
            offline: "Offline — actions are unavailable until the connection returns.",
            online: "Back online.",
            staleVersion: "This order was updated elsewhere. Showing the latest version.",
            orderUpdated: "Order updated",
            paymentCollected: "Payment collected",
            walkinSent: "Walk-in order paid and sent to kitchen",
            shiftOpened: "Shift opened",
            shiftClosed: "Shift closed",
            cashRecorded: "Recorded",
            printRetried: "Reprint requested",
            undo: "Undo",
            undone: "Action undone",
        },
        my: {
            offline: "အော့ဖ်လိုင်း — အင်တာနက်ပြန်ရောက်မှ လုပ်ဆောင်နိုင်ပါမည်။",
            online: "အင်တာနက် ပြန်ရောက်ပါပြီ။",
            staleVersion: "ဒီအော်ဒါကို တခြားနေရာမှာ အပ်ဒိတ်လုပ်ခဲ့ပါသည်။ နောက်ဆုံးအခြေအနေပြပါမည်။",
            orderUpdated: "အော်ဒါ အပ်ဒိတ်ပြီးပါပြီ",
            paymentCollected: "ငွေရရှိပြီးပါပြီ",
            walkinSent: "အော်ဒါကို ငွေရှင်းပြီး မီးဖိုချောင်သို့ ပို့ပြီးပါပြီ",
            shiftOpened: "အလှည့် ဖွင့်ပြီးပါပြီ",
            shiftClosed: "အလှည့် ပိတ်ပြီးပါပြီ",
            cashRecorded: "မှတ်တမ်းတင်ပြီးပါပြီ",
            printRetried: "ထပ်မံပုံနှိပ်ရန် တောင်းဆိုပြီးပါပြီ",
            undo: "ပြန်ဖြည့်ရန်",
            undone: "လုပ်ဆောင်ချက် ပြန်ဖျက်ပြီးပါပြီ",
        },
    };
    const LANG = (document.documentElement.getAttribute("lang") || "").toLowerCase().startsWith("my") ? "my" : "en";
    function t(key) { return (STRINGS[LANG] && STRINGS[LANG][key]) || STRINGS.en[key] || key; }

    function generateRequestId() {
        return (window.crypto && crypto.randomUUID) ? crypto.randomUUID()
            : "fo-" + Date.now() + "-" + Math.random().toString(16).slice(2);
    }

    function trapFocus(container, event) {
        if (event.key !== "Tab") return;
        const focusable = Array.from(container.querySelectorAll(
            'a[href], button:not([disabled]), textarea, input:not([disabled]), select, [tabindex]:not([tabindex="-1"])'
        )).filter(function (el) { return el.offsetParent !== null; });
        if (!focusable.length) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) { last.focus(); event.preventDefault(); }
        else if (!event.shiftKey && document.activeElement === last) { first.focus(); event.preventDefault(); }
    }

    ready(function () {
        const app = document.querySelector(".fo-staff-app");
        if (!app) return;
        document.body.classList.add("fo-staff-page");

        const role = app.dataset.role;
        const isManager = app.dataset.isManager === "1";
        const slaWarn = Number(app.dataset.slaWarn || 10);
        const slaLate = Number(app.dataset.slaLate || 20);
        const toast = app.querySelector("[data-staff-toast]");
        let orders = [];
        let soundEnabled = false;
        let alertedOrderIds = new Set();
        let toastTimer;
        let products = [];
        let cart = [];
        let ownCupDiscount = 500;
        let posCategory = "all";
        let posPromotion = null;
        let paymentOrder = null;
        let refundContext = null;
        let searchQuery = "";
        let filterQuery = "";
        let pollTimer = null;
        let pollDelay = 4000;
        let lastGoodSync = null;
        const walkinModal = app.querySelector("[data-walkin-modal]");
        const paymentModal = app.querySelector("[data-payment-modal]");
        const refundModal = app.querySelector("[data-refund-modal]");
        const sessionModal = app.querySelector("[data-session-modal]");
        const detailModal = app.querySelector("[data-detail-modal]");

        // ------------------------------------------------------------------
        // Offline banner + online/offline aware fetch wrapper.
        // ------------------------------------------------------------------
        const offlineBanner = app.querySelector("[data-offline-banner]");
        function applyOnlineState(online) {
            if (offlineBanner) offlineBanner.hidden = online;
            app.classList.toggle("fo-is-offline", !online);
            const liveChip = app.querySelector("[data-live-chip]");
            if (liveChip) {
                liveChip.classList.toggle("is-offline", !online);
                const label = liveChip.querySelector("[data-live-label]");
                if (label) label.textContent = online ? "Live" : "Offline";
            }
        }
        applyOnlineState(navigator.onLine);
        window.addEventListener("online", function () { applyOnlineState(true); refresh(true); });
        window.addEventListener("offline", function () { applyOnlineState(false); });

        function element(tag, className, text) {
            const item = document.createElement(tag);
            if (className) item.className = className;
            if (text !== undefined) item.textContent = text;
            return item;
        }

        function showToast(message) {
            toast.textContent = message;
            toast.classList.add("is-visible");
            clearTimeout(toastTimer);
            toastTimer = setTimeout(function () { toast.classList.remove("is-visible"); }, 2600);
        }

        // Normalized API client: every state-changing call goes through
        // this, so CSRF handling, idempotency, and error normalization
        // (including a distinct stale-version signal) live in one place.
        async function apiCall(url, { method = "POST", body = null } = {}) {
            const form = body instanceof FormData ? body : new FormData();
            if (!(body instanceof FormData) && body) {
                Object.keys(body).forEach(function (key) { if (body[key] !== undefined && body[key] !== null) form.append(key, body[key]); });
            }
            if (method === "POST") form.append("csrf_token", app.dataset.csrf || "");
            let response;
            try {
                response = await fetch(url, {
                    method, body: method === "GET" ? undefined : form, credentials: "same-origin",
                });
            } catch (networkError) {
                const error = new Error(navigator.onLine ? "Could not reach the server." : t("offline"));
                error.network = true;
                throw error;
            }
            let data = {};
            try { data = await response.json(); } catch (e) { /* empty body */ }
            if (!response.ok) {
                const error = new Error(data.error || "Request failed.");
                error.errorCode = data.error_code || null;
                error.status = response.status;
                throw error;
            }
            return data;
        }

        function beep() {
            if (!soundEnabled) return;
            try {
                const AudioContext = window.AudioContext || window.webkitAudioContext;
                const context = new AudioContext();
                const oscillator = context.createOscillator();
                const gain = context.createGain();
                oscillator.frequency.value = 740;
                gain.gain.setValueAtTime(0.08, context.currentTime);
                gain.gain.exponentialRampToValueAtTime(0.001, context.currentTime + 0.25);
                oscillator.connect(gain); gain.connect(context.destination);
                oscillator.start(); oscillator.stop(context.currentTime + 0.25);
                if (navigator.vibrate) navigator.vibrate(120);
            } catch (_error) { /* Sound is optional. */ }
        }

        function columnFor(order) {
            if (role === "cashier") {
                if (order.status === "pending") return "queue";
                if (order.status === "accepted" || order.status === "preparing") return "preparing";
                if (order.status === "ready") return "ready";
                if (order.status === "completed" || order.status === "cancelled") return "recent";
            } else {
                if (order.status === "accepted") return "queue";
                if (order.status === "preparing") return "preparing";
                if (order.status === "ready") return "ready";
            }
            return "hidden";
        }

        function slaClass(minutes) {
            if (minutes >= slaLate) return "is-late";
            if (minutes >= slaWarn) return "is-warn";
            return "";
        }

        function actionButton(label, action, primary, danger) {
            const button = element("button", primary ? "fo-primary-staff-action" : (danger ? "fo-danger-action" : ""), label);
            button.type = "button"; button.dataset.staffAction = action;
            if (danger) button.setAttribute("aria-label", "Cancel order");
            return button;
        }

        function formatMoney(amount, currency) {
            return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(Number(amount || 0)) + " " + (currency || "MMK");
        }

        function matchesFilters(order) {
            if (searchQuery) {
                const haystack = (order.number + " " + order.customer + " " + order.phone).toLowerCase();
                if (!haystack.includes(searchQuery)) return false;
            }
            if (filterQuery) {
                const haystack = ((order.department || "") + " " + (order.floor || "")).toLowerCase();
                if (!haystack.includes(filterQuery)) return false;
            }
            return true;
        }

        function orderCard(order) {
            const card = element("article", "fo-staff-order");
            card.dataset.orderId = order.id;
            card.dataset.stateVersion = order.state_version;

            const head = element("div", "fo-order-head");
            const number = element("div", "fo-order-number");
            number.append(element("small", "", "Order"), element("strong", "", order.number));
            const minutes = order.elapsed_minutes;
            const ageClass = "fo-order-age " + (slaClass(minutes) ? "is-" + (slaClass(minutes) === "is-late" ? "late" : "warn") : "");
            const age = element("span", ageClass.trim());
            const clockIcon = element("i", "fa fa-clock-o"); clockIcon.setAttribute("aria-hidden", "true");
            age.append(clockIcon, element("span", "", minutes + " min"));
            if (slaClass(minutes)) age.append(element("span", "fo-sla-label", slaClass(minutes) === "is-late" ? "OVERDUE" : "APPROACHING SLA"));
            head.append(number, age);

            const body = element("div", "fo-order-body");
            const customerRow = element("div", "fo-customer-row");
            const customer = element("div"); customer.append(element("strong", "", order.customer));
            if (role === "cashier") customer.append(element("small", "", order.phone));
            customerRow.append(customer, element("span", "fo-location", order.department + " · " + order.floor)); body.append(customerRow);

            const lines = element("div", "fo-order-lines");
            order.lines.forEach(function (line) {
                const row = element("div", "fo-order-line");
                row.append(element("b", "", String(line.quantity).replace(/\.0$/, "") + "×"), element("span", "", line.name));
                if (line.modifiers) row.append(element("small", "fo-line-modifiers", line.modifiers));
                const detail = [line.own_cup_quantity ? "Own cup × " + line.own_cup_quantity : "", line.note].filter(Boolean).join(" · ");
                if (detail) row.append(element("small", "fo-line-note-text", detail));
                lines.append(row);
            });
            body.append(lines);
            if (order.note) {
                const note = element("div", "fo-order-note");
                const noteIcon = element("i", "fa fa-sticky-note-o"); noteIcon.setAttribute("aria-hidden", "true");
                note.append(noteIcon, element("span", "", order.note)); body.append(note);
            }
            if (order.promotion) body.append(element("div", "fo-order-promotion", "🏷 " + order.promotion));
            if (order.status === "cancelled" && order.cancel_reason) {
                body.append(element("div", "fo-order-cancelled", "Cancelled: " + order.cancel_reason));
            }
            const meta = element("div", "fo-order-meta");
            const leftMeta = element("div", "fo-order-meta-left");
            leftMeta.append(element("span", "fo-source-badge", order.source));
            if (role === "cashier") {
                const paymentLabel = order.payment_status === "paid" ? "Paid · " + order.payment_method
                    : order.payment_status === "refunded" ? "Refunded" : order.payment_status === "partially_refunded" ? "Partial refund" : "Payment due";
                leftMeta.append(element("span", order.payment_status === "paid" ? "fo-payment-state is-paid" : "fo-payment-state", paymentLabel));
            }
            const myTarget = role === "cashier" ? "cashier" : "kitchen";
            const printState = order.print_jobs[myTarget] || "not queued";
            leftMeta.append(element("span", printState === "printed" ? "fo-print-state is-printed" : (printState === "failed" || printState === "dead_letter" ? "fo-print-state is-failed" : "fo-print-state"), "Print: " + printState));
            meta.append(leftMeta);
            if (role === "cashier") meta.append(element("strong", "fo-order-total", order.total));
            body.append(meta); card.append(head, body);

            const actions = element("div", "fo-order-actions");
            const detailBtn = actionButton("Details", "details", false);
            actions.append(detailBtn);
            if (printState === "failed" || printState === "dead_letter") {
                actions.append(actionButton("Retry print", "retry-print", false));
            }
            if (role === "cashier" && order.status === "pending") {
                const cancel = actionButton("", "cancel", false, true);
                const trash = element("i", "fa fa-times"); trash.setAttribute("aria-hidden", "true"); cancel.append(trash);
                actions.append(cancel, actionButton("Accept & print", "accept", true));
            } else if (role === "cashier" && order.status === "ready") {
                if (order.payment_status !== "paid") actions.append(actionButton("Collect payment", "payment", true));
                else actions.append(actionButton("Complete order", "complete", true));
                actions.append(actionButton("Reprint", "reprint", false));
            } else if (role === "cashier" && (order.status === "accepted" || order.status === "preparing")) {
                if (order.payment_status !== "paid") actions.append(actionButton("Collect payment", "payment", true));
                actions.append(actionButton("Reprint", "reprint", false));
                if (order.food_status !== "completed") actions.append(actionButton("Cancel", "cancel-dialog", false, true));
            } else if (role === "cashier" && order.status === "completed") {
                actions.append(actionButton("Reprint", "reprint", false));
                if (order.payment_status === "paid") actions.append(actionButton("Refund", "refund-dialog", false, true));
            } else if (role === "kitchen" && order.status === "accepted") {
                actions.append(actionButton("Start preparing", "prepare", true));
                actions.append(actionButton("Reprint", "reprint", false));
            } else if (role === "kitchen" && order.status === "preparing") {
                actions.append(actionButton("Mark ready", "ready", true));
                actions.append(actionButton("Reprint", "reprint", false));
            } else if (role === "kitchen" && order.status === "ready") {
                actions.append(actionButton("Reprint", "reprint", false));
            }
            card.append(actions);
            return card;
        }

        function render() {
            const buckets = { queue: [], preparing: [], ready: [], recent: [] };
            orders.filter(matchesFilters).forEach(function (order) {
                const column = columnFor(order);
                if (buckets[column]) buckets[column].push(order);
            });
            Object.keys(buckets).forEach(function (name) {
                const container = app.querySelector('[data-order-column="' + name + '"]');
                if (!container) return;
                const count = buckets[name].length;
                app.querySelectorAll('[data-column-count="' + name + '"], [data-stat="' + name + '"]').forEach(function (item) { item.textContent = count; });
                container.replaceChildren();
                if (!count) {
                    const empty = element("div", "fo-empty-column");
                    const icon = element("i", "fa fa-check-circle-o"); icon.setAttribute("aria-hidden", "true");
                    empty.append(icon, element("strong", "", "All clear"), element("small", "", "New orders will appear automatically.")); container.append(empty);
                } else buckets[name].forEach(function (order) { container.append(orderCard(order)); });
            });
            const newIds = buckets.queue.map(function (order) { return order.id; });
            const freshlyNew = newIds.filter(function (id) { return !alertedOrderIds.has(id); });
            if (alertedOrderIds.size && freshlyNew.length) beep();
            alertedOrderIds = new Set(newIds);
        }

        // ------------------------------------------------------------------
        // Adaptive, visibility-aware polling.
        // ------------------------------------------------------------------
        async function refresh(showError) {
            try {
                const url = app.dataset.ordersUrl + "?role=" + encodeURIComponent(role) + (role === "cashier" ? "&include_recent=1" : "");
                const data = await apiCall(url, { method: "GET" });
                orders = data.orders || []; render();
                lastGoodSync = new Date();
                app.querySelectorAll("[data-last-sync]").forEach(function (el) { el.textContent = lastGoodSync.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); });
            } catch (error) { if (showError) showToast(error.message); }
        }
        function schedulePoll() {
            clearTimeout(pollTimer);
            if (document.visibilityState === "hidden") return; // paused while tab hidden
            pollTimer = setTimeout(function () { refresh(false).then(schedulePoll); }, pollDelay);
        }
        document.addEventListener("visibilitychange", function () {
            if (document.visibilityState === "visible") { refresh(false).then(schedulePoll); }
            else { clearTimeout(pollTimer); }
        });

        async function refreshPrinterState() {
            const chip = app.querySelector("[data-printer-chip]");
            if (!chip || !app.dataset.printersUrl) return;
            try {
                const data = await apiCall(app.dataset.printersUrl + "?role=" + encodeURIComponent(role), { method: "GET" });
                const online = data.state === "online";
                chip.classList.toggle("is-online", online);
                chip.classList.toggle("is-offline", !online);
                chip.classList.toggle("has-failed", Number(data.failed || 0) > 0);
                chip.querySelector("[data-printer-label]").textContent = online ? "Printer bridge online" : "Printer bridge offline";
                const queueText = Number(data.queued || 0) + " queued";
                const failedText = Number(data.failed || 0) ? " · " + data.failed + " failed" : "";
                chip.querySelector("[data-printer-detail]").textContent = queueText + failedText;
            } catch (_error) {
                chip.classList.remove("is-online");
                chip.classList.add("is-offline");
                chip.querySelector("[data-printer-label]").textContent = "Printer status unavailable";
                chip.querySelector("[data-printer-detail]").textContent = "Check the local bridge";
            }
        }

        function findOrder(id) { return orders.find(function (order) { return String(order.id) === String(id); }); }

        async function sendAction(card, button, action) {
            card.querySelectorAll("button").forEach(function (item) { item.disabled = true; });
            try {
                const url = app.dataset.actionBase + "/" + card.dataset.orderId + "/" + action;
                const body = { role: role, request_id: generateRequestId(), expected_version: card.dataset.stateVersion };
                if (action === "cancel") body.reason = "Cancelled before acceptance";
                await apiCall(url, { body: body });
                showToast(t("orderUpdated")); await refresh(true);
            } catch (error) {
                if (error.errorCode === "stale_version") { showToast(t("staleVersion")); await refresh(true); }
                else showToast(error.message);
                card.querySelectorAll("button").forEach(function (item) { item.disabled = false; });
            }
        }

        async function runAction(card, button) {
            const action = button.dataset.staffAction;
            const order = findOrder(card.dataset.orderId);
            if (action === "payment") { openPayment(order); return; }
            if (action === "cancel-dialog") { openRefund(order, "cancel"); return; }
            if (action === "refund-dialog") { openRefund(order, "refund"); return; }
            if (action === "details") { openDetail(order); return; }
            if (action === "retry-print") {
                try {
                    // The order payload only carries job state, not job ids;
                    // reprint safely requeues a fresh job for this order/target
                    // either way (failed or not), which is what "retry" means here.
                    await apiCall(app.dataset.actionBase + "/" + card.dataset.orderId + "/reprint", { body: { role: role } });
                    showToast(t("printRetried")); await refresh(true);
                } catch (error) { showToast(error.message); }
                return;
            }
            // Kitchen's "prepare"/"ready" hold briefly before actually being
            // sent, so tapping Undo simply cancels the send — this is the
            // only safe form of undo since the backend state machine is
            // deliberately one-way (no revert transition exists).
            if (role === "kitchen" && (action === "prepare" || action === "ready")) {
                scheduleKitchenAction(card, button, action, order);
                return;
            }
            await sendAction(card, button, action);
        }

        function setModalOpen(modal, open, focusTarget) {
            if (!modal) return;
            modal.classList.toggle("is-open", open);
            modal.setAttribute("aria-hidden", String(!open));
            document.body.classList.toggle("fo-modal-open", open);
            if (open && focusTarget) focusTarget.focus();
        }
        [walkinModal, paymentModal, refundModal, sessionModal, detailModal].forEach(function (modal) {
            if (modal) modal.addEventListener("keydown", function (event) { trapFocus(modal, event); });
        });

        // ------------------------------------------------------------------
        // Numeric keypad + quick amounts (payment & walk-in).
        // ------------------------------------------------------------------
        function buildKeypad(input, onChange) {
            const pad = element("div", "fo-keypad");
            ["1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "back"].forEach(function (key) {
                const button = element("button", "fo-keypad-key", key === "back" ? "⌫" : (key === "clear" ? "C" : key));
                button.type = "button";
                button.addEventListener("click", function () {
                    let value = String(input.value || "");
                    if (key === "clear") value = "";
                    else if (key === "back") value = value.slice(0, -1);
                    else value += key;
                    input.value = value.replace(/^0+(?=\d)/, "");
                    onChange();
                });
                pad.append(button);
            });
            return pad;
        }
        function buildQuickAmounts(container, dueGetter, input, onChange) {
            container.replaceChildren();
            const due = Math.max(0, dueGetter());
            if (!due) return;
            const roundUp = function (value, step) { return Math.ceil(value / step) * step; };
            const options = Array.from(new Set([
                Math.ceil(due), roundUp(due, 1000), roundUp(due, 5000), roundUp(due, 10000),
            ])).slice(0, 4);
            options.forEach(function (amount) {
                const button = element("button", "fo-quick-amount", formatMoney(amount));
                button.type = "button";
                button.addEventListener("click", function () { input.value = amount; onChange(); });
                container.append(button);
            });
        }

        function updateCashChange(form) {
            const received = Number(String(form.querySelector('[name="amount_received"]').value || "0").replace(/,/g, ""));
            const due = form.matches("[data-walkin-form]") ? cartTotal() : Number(paymentOrder && paymentOrder.amount_total || 0);
            const currency = form.matches("[data-walkin-form]") ? (products[0] && products[0].currency) : (paymentOrder && paymentOrder.currency);
            form.querySelector("[data-cash-change] strong").textContent = formatMoney(Math.max(0, received - due), currency);
            const quickAmounts = form.querySelector("[data-quick-amounts]");
            if (quickAmounts) buildQuickAmounts(quickAmounts, function () { return due; }, form.querySelector('[name="amount_received"]'), function () { updateCashChange(form); });
        }

        // ------------------------------------------------------------------
        // Walk-in POS (Cashier only).
        // ------------------------------------------------------------------
        async function loadProducts() {
            if (products.length) return;
            const data = await apiCall(app.dataset.productsUrl, { method: "GET" });
            products = data.products || [];
            ownCupDiscount = Number(data.own_cup_discount || 0);
            posPromotion = data.promotion_banner || null;
            const promoBox = app.querySelector("[data-pos-promo]");
            if (promoBox) {
                promoBox.hidden = !posPromotion;
                if (posPromotion) promoBox.querySelector("[data-pos-promo-text]").textContent = posPromotion.headline + (posPromotion.subtext ? " — " + posPromotion.subtext : "");
            }
            renderProducts();
        }

        function renderProducts(query) {
            const container = app.querySelector("[data-pos-products]");
            if (!container) return;
            const needle = String(query || "").trim().toLowerCase();
            const visible = products.filter(function (product) {
                return (posCategory === "all" || product.category === posCategory) && (!needle || product.name.toLowerCase().includes(needle));
            });
            container.replaceChildren();
            visible.forEach(function (product) {
                const button = element("button", "fo-pos-product"); button.type = "button"; button.dataset.addProduct = product.id;
                const icon = element("span", "fo-product-media");
                const image = document.createElement("img"); image.src = product.image_url; image.alt = ""; image.loading = "lazy";
                const fallback = element("b", "", product.name.charAt(0).toUpperCase());
                image.addEventListener("error", function () { image.hidden = true; }); icon.append(image, fallback);
                const copy = element("span", "fo-pos-product-copy"); copy.append(element("strong", "", product.name), element("small", "", product.price_label));
                const plus = element("i", "fa fa-plus fo-pos-add"); plus.setAttribute("aria-hidden", "true");
                button.append(icon, copy, plus); container.append(button);
            });
            const visibleCount = app.querySelector("[data-visible-products]");
            if (visibleCount) visibleCount.textContent = visible.length + (visible.length === 1 ? " product" : " products");
            if (!visible.length) container.append(element("p", "fo-no-products", "No matching items."));
        }

        function modifierExtra(item) {
            // price_extra from the server is untaxed; apply the same rate
            // the product itself carries so the displayed/charged total
            // matches what the server will compute (extras are folded into
            // the line's price_unit before tax there too).
            const rate = 1 + (item.product.tax_rate || 0);
            return (item.selectedModifiers || []).reduce(function (sum, sel) { return sum + (sel.price_extra || 0) * rate; }, 0);
        }
        function cartTotal() {
            return cart.reduce(function (sum, item) {
                const unit = item.product.price + modifierExtra(item);
                return sum + (unit * item.quantity) - (ownCupDiscount * item.own_cup_quantity);
            }, 0);
        }

        function renderCart() {
            const container = app.querySelector("[data-pos-cart]");
            if (!container) return;
            container.replaceChildren();
            cart.forEach(function (item, index) {
                const row = element("div", "fo-pos-cart-item"); row.dataset.cartIndex = index;
                const top = element("div", "fo-cart-item-top");
                const thumb = document.createElement("img"); thumb.className = "fo-cart-item-thumb"; thumb.src = item.product.image_url; thumb.alt = "";
                const unit = item.product.price + modifierExtra(item);
                const name = element("div"); name.append(element("strong", "", item.product.name), element("small", "", formatMoney(unit * item.quantity, item.product.currency)));
                const quantity = element("div", "fo-cart-quantity");
                const minus = element("button", "", "−"); minus.type = "button"; minus.dataset.cartDelta = "-1";
                const plus = element("button", "", "+"); plus.type = "button"; plus.dataset.cartDelta = "1";
                quantity.append(minus, element("b", "", item.quantity), plus); top.append(thumb, name, quantity); row.append(top);
                if (item.selectedModifiers && item.selectedModifiers.length) {
                    row.append(element("small", "fo-cart-modifier-summary", item.selectedModifiers.map(function (m) { return m.option_name; }).join(", ")));
                }
                const note = element("input", "fo-cart-note"); note.type = "text"; note.placeholder = "Item note (optional)"; note.value = item.note; note.dataset.cartNote = ""; row.append(note);
                if (item.product.own_cup_eligible) {
                    const cup = element("div", "fo-pos-cup-qty");
                    const cupText = element("span", "", "Own cups"); cupText.append(element("small", "", "Save " + formatMoney(ownCupDiscount, item.product.currency) + " each"));
                    const cupStep = element("div", "fo-cart-quantity");
                    const cupMinus = element("button", "", "−"); cupMinus.type = "button"; cupMinus.dataset.cupDelta = "-1"; cupMinus.disabled = !item.own_cup_quantity;
                    const cupPlus = element("button", "", "+"); cupPlus.type = "button"; cupPlus.dataset.cupDelta = "1"; cupPlus.disabled = item.own_cup_quantity >= item.quantity;
                    cupStep.append(cupMinus, element("b", "", item.own_cup_quantity + " / " + item.quantity), cupPlus); cup.append(cupText, cupStep); row.append(cup);
                }
                container.append(row);
            });
            if (!cart.length) {
                const empty = element("div", "fo-pos-empty"); const icon = element("i", "fa fa-shopping-basket");
                empty.append(icon, element("strong", "", "Your basket is empty"), element("small", "", "Select items from the menu.")); container.append(empty);
            }
            const total = cartTotal();
            app.querySelector("[data-pos-count]").textContent = cart.reduce(function (sum, item) { return sum + item.quantity; }, 0) + " items";
            app.querySelector("[data-pos-total]").textContent = formatMoney(total, products[0] && products[0].currency);
            app.querySelector("[data-charge-total]").textContent = formatMoney(total, products[0] && products[0].currency);
            const walkinForm = app.querySelector("[data-walkin-form]");
            walkinForm.querySelector('[name="amount_received"]').value = Math.ceil(total || 0);
            updateCashChange(walkinForm);
            walkinForm.querySelector(".fo-charge-button").disabled = !cart.length;
        }

        // ------------------------------------------------------------------
        // Compact modifier picker for walk-in POS items that have modifier
        // groups. Reuses the .fo-modifier-group/.fo-option-row styling
        // already shipped for the customer product dialog (same asset
        // bundle) instead of introducing a parallel set of styles.
        // ------------------------------------------------------------------
        let modifierPickerState = null;
        function openModifierPicker(product) {
            let overlay = app.querySelector("[data-modifier-picker]");
            if (!overlay) {
                overlay = element("div", "fo-pos-backdrop fo-modifier-picker-backdrop");
                overlay.dataset.modifierPicker = "";
                overlay.innerHTML =
                    '<section class="fo-modifier-picker-panel" role="dialog" aria-modal="true">' +
                    '<header><h3 data-modifier-picker-title></h3><button type="button" data-modifier-picker-close aria-label="Close"><i class="fa fa-times"></i></button></header>' +
                    '<div class="fo-product-modifiers" data-modifier-picker-groups></div>' +
                    '<p class="fo-customize-error" data-modifier-picker-error hidden="hidden"></p>' +
                    '<button type="button" class="fo-charge-button" data-modifier-picker-confirm>Add to ticket</button>' +
                    '</section>';
                walkinModal.appendChild(overlay);
            }
            modifierPickerState = { product: product, selected: {} };
            product.modifier_groups.forEach(function (group) {
                if (group.selection_type === "single" && group.options.length && (group.required || group.min_selection)) {
                    modifierPickerState.selected[group.id] = [group.options[0].id];
                }
            });
            overlay.querySelector("[data-modifier-picker-title]").textContent = product.name;
            renderModifierPicker();
            overlay.classList.add("is-open");
        }
        function renderModifierPicker() {
            const overlay = app.querySelector("[data-modifier-picker]");
            if (!overlay || !modifierPickerState) return;
            const container = overlay.querySelector("[data-modifier-picker-groups]");
            container.replaceChildren();
            modifierPickerState.product.modifier_groups.forEach(function (group) {
                const fieldset = element("fieldset", "fo-modifier-group");
                const legend = element("legend");
                legend.append(element("span", "", group.name));
                if (group.required || group.min_selection) legend.append(element("small", "", "Required"));
                fieldset.append(legend);
                const inputType = group.selection_type === "multiple" ? "checkbox" : "radio";
                group.options.forEach(function (option) {
                    const row = element("label", "fo-option-row");
                    const input = document.createElement("input");
                    input.type = inputType; input.name = "pos-modifier-" + group.id; input.value = option.id;
                    input.checked = (modifierPickerState.selected[group.id] || []).includes(option.id);
                    input.addEventListener("change", function () {
                        const current = modifierPickerState.selected[group.id] || [];
                        modifierPickerState.selected[group.id] = inputType === "radio" ? [option.id]
                            : (input.checked ? current.concat([option.id]) : current.filter(function (id) { return id !== option.id; }));
                    });
                    const check = element("span", "fo-option-check");
                    const textWrap = element("span");
                    textWrap.append(element("strong", "", option.name));
                    if (option.price_extra) textWrap.append(element("small", "", "+" + formatMoney(option.price_extra)));
                    row.append(input, check, textWrap); fieldset.append(row);
                });
                container.append(fieldset);
            });
        }
        function closeModifierPicker() {
            const overlay = app.querySelector("[data-modifier-picker]");
            if (overlay) overlay.classList.remove("is-open");
            modifierPickerState = null;
        }
        function confirmModifierPicker() {
            if (!modifierPickerState) return;
            const overlay = app.querySelector("[data-modifier-picker]");
            const errorBox = overlay.querySelector("[data-modifier-picker-error]");
            for (const group of modifierPickerState.product.modifier_groups) {
                const count = (modifierPickerState.selected[group.id] || []).length;
                if (count < group.min_selection) { errorBox.hidden = false; errorBox.textContent = "Select at least " + group.min_selection + " for " + group.name + "."; return; }
                if (group.max_selection && count > group.max_selection) { errorBox.hidden = false; errorBox.textContent = "Select at most " + group.max_selection + " for " + group.name + "."; return; }
            }
            errorBox.hidden = true;
            const selectedModifiers = [];
            modifierPickerState.product.modifier_groups.forEach(function (group) {
                (modifierPickerState.selected[group.id] || []).forEach(function (optionId) {
                    const option = group.options.find(function (o) { return o.id === optionId; });
                    if (option) selectedModifiers.push({ id: option.id, option_name: option.name, price_extra: option.price_extra });
                });
            });
            cart.push({ product: modifierPickerState.product, quantity: 1, note: "", own_cup_quantity: 0, selectedModifiers: selectedModifiers });
            renderCart();
            closeModifierPicker();
        }

        async function openWalkin() {
            setModalOpen(walkinModal, true);
            try {
                await loadProducts(); renderCart();
                if (!walkinModal.querySelector("[data-keypad-mounted]")) {
                    const input = walkinModal.querySelector('[data-keypad-target]');
                    const pad = buildKeypad(input, function () { updateCashChange(walkinModal.querySelector("[data-walkin-form]")); });
                    pad.dataset.keypadMounted = "";
                    input.insertAdjacentElement("afterend", pad);
                    walkinModal.dataset.keypadMounted = "1";
                }
            } catch (error) { showToast(error.message); }
        }

        function openPayment(order) {
            if (!order || !paymentModal) return;
            paymentOrder = order;
            paymentModal.querySelector("[data-payment-order]").textContent = order.number;
            paymentModal.querySelector("[data-payment-due]").textContent = order.total;
            paymentModal.querySelector('[name="amount_received"]').value = Math.ceil(order.amount_total || 0);
            paymentModal.querySelector("[data-payment-error]").hidden = true;
            updateCashChange(paymentModal.querySelector("[data-payment-form]"));
            if (!paymentModal.dataset.keypadMounted) {
                const input = paymentModal.querySelector('[data-keypad-target]');
                const pad = buildKeypad(input, function () { updateCashChange(paymentModal.querySelector("[data-payment-form]")); });
                input.insertAdjacentElement("afterend", pad);
                paymentModal.dataset.keypadMounted = "1";
            }
            setModalOpen(paymentModal, true, paymentModal.querySelector('[name="amount_received"]'));
        }

        function openRefund(order, kind) {
            if (!order || !refundModal) return;
            refundContext = { order: order, kind: kind };
            refundModal.querySelector("[data-refund-order]").textContent = order.number;
            refundModal.querySelector("[data-refund-kind]").textContent = kind === "refund" ? "REFUND ORDER" : "CANCEL ORDER";
            refundModal.querySelector("[data-refund-submit-label]").textContent = kind === "refund" ? "Confirm refund" : "Confirm cancellation";
            const amountRow = refundModal.querySelector("[data-refund-amount-row]");
            const isPaid = order.payment_status === "paid" || order.payment_status === "partially_refunded";
            amountRow.hidden = kind !== "refund";
            refundModal.querySelector("[data-refund-amount]").value = Math.ceil(order.amount_total || 0);
            refundModal.querySelector("[data-manager-pin-field]").hidden = !(isPaid && !isManager);
            refundModal.querySelector("[data-refund-cash-note]").hidden = !isPaid;
            refundModal.querySelector("[data-refund-error]").hidden = true;
            refundModal.querySelector('[name="reason"]').value = "";
            setModalOpen(refundModal, true, refundModal.querySelector('[name="reason"]'));
        }

        async function openDetail(order) {
            if (!order || !detailModal) return;
            detailModal.querySelector("[data-detail-title]").textContent = "Order " + order.number;
            const body = detailModal.querySelector("[data-detail-body]");
            body.replaceChildren();
            const rows = [
                ["Customer", order.customer], ["Phone", order.phone],
                ["Department / Floor", order.department + " / " + order.floor],
                ["Source", order.source], ["Status", order.status],
                ["Total", order.total], ["Payment", order.payment_status],
            ];
            if (role === "cashier") rows.push(["Received / Change", formatMoney(order.amount_received) + " / " + formatMoney(order.change_amount)]);
            rows.forEach(function (pair) {
                const row = element("div", "fo-detail-row");
                row.append(element("span", "", pair[0]), element("strong", "", pair[1]));
                body.append(row);
            });
            const timeline = detailModal.querySelector("[data-detail-timeline]");
            timeline.replaceChildren(element("li", "fo-detail-loading", "Loading history…"));
            setModalOpen(detailModal, true);
            try {
                const data = await apiCall(app.dataset.actionBase + "/" + order.id + "/events?role=" + role, { method: "GET" });
                timeline.replaceChildren();
                (data.events || []).forEach(function (event) {
                    const item = element("li", "fo-timeline-item");
                    item.append(element("strong", "", event.event_type.replace(/_/g, " ")));
                    if (event.reason) item.append(element("span", "fo-timeline-reason", event.reason));
                    item.append(element("small", "", (event.actor ? event.actor + " · " : "") + event.created_at));
                    timeline.append(item);
                });
                if (!data.events || !data.events.length) timeline.append(element("li", "fo-detail-loading", "No events yet."));
            } catch (error) {
                timeline.replaceChildren(element("li", "fo-detail-loading", error.message));
            }
        }

        // ------------------------------------------------------------------
        // Cash session (Cashier only).
        // ------------------------------------------------------------------
        async function refreshSession() {
            if (role !== "cashier" || !sessionModal) return null;
            try {
                const data = await apiCall(app.dataset.sessionBase + "/current", { method: "GET" });
                renderSession(data.session);
                return data.session;
            } catch (error) { return null; }
        }
        function renderSession(session) {
            const chip = app.querySelector(".fo-shift-chip");
            const openForm = sessionModal.querySelector("[data-session-open-form]");
            const openView = sessionModal.querySelector("[data-session-open-view]");
            if (chip) {
                chip.classList.toggle("is-open", !!session);
                chip.querySelector("strong").textContent = session ? "Shift open" : "Shift closed";
                chip.querySelector("[data-shift-summary]").textContent = session ? "Expected " + formatMoney(session.closing_cash_expected) : "Tap to open";
            }
            if (session) {
                openForm.hidden = true; openView.hidden = false;
                openView.querySelector("[data-session-opened-by]").textContent = session.opened_by || "";
                openView.querySelector("[data-session-opening]").textContent = formatMoney(session.opening_cash);
                openView.querySelector("[data-session-sales]").textContent = formatMoney(session.sale_total);
                openView.querySelector("[data-session-refunds]").textContent = formatMoney(session.refund_total);
                openView.querySelector("[data-session-cash-in]").textContent = formatMoney(session.cash_in_total);
                openView.querySelector("[data-session-cash-out]").textContent = formatMoney(session.cash_out_total);
                openView.querySelector("[data-session-expected]").textContent = formatMoney(session.closing_cash_expected);
            } else {
                openForm.hidden = false; openView.hidden = true;
            }
        }

        // ------------------------------------------------------------------
        // Kitchen: short controlled undo for the most recent transition.
        // The server's state machine is deliberately one-way (no revert
        // transition), so the only safe form of "undo" is to hold the
        // request briefly before it is actually sent — tapping Undo just
        // cancels that pending send. Nothing is optimistically applied to
        // the card in the meantime.
        // ------------------------------------------------------------------
        let pendingKitchenAction = null;
        const undoBar = app.querySelector("[data-kitchen-undo]");
        const KITCHEN_UNDO_WINDOW_MS = 4000;

        function scheduleKitchenAction(card, button, action, order) {
            if (!undoBar) { sendAction(card, button, action); return; }
            if (pendingKitchenAction) { clearTimeout(pendingKitchenAction.timer); finalizePendingKitchenAction(); }
            button.disabled = true;
            const label = action === "prepare" ? "Start preparing" : "Mark ready";
            undoBar.querySelector("[data-kitchen-undo-text]").textContent = order.number + ": " + label + "…";
            undoBar.hidden = false;
            const timer = setTimeout(function () {
                pendingKitchenAction = null;
                undoBar.hidden = true;
                sendAction(card, button, action);
            }, KITCHEN_UNDO_WINDOW_MS);
            pendingKitchenAction = { timer: timer, card: card, button: button };
        }
        function finalizePendingKitchenAction() {
            if (!pendingKitchenAction) return;
            pendingKitchenAction.button.disabled = false;
            pendingKitchenAction = null;
        }
        if (undoBar) {
            undoBar.querySelector("[data-kitchen-undo-action]").addEventListener("click", function () {
                if (!pendingKitchenAction) return;
                clearTimeout(pendingKitchenAction.timer);
                finalizePendingKitchenAction();
                undoBar.hidden = true;
                showToast(t("undone"));
            });
        }

        // ------------------------------------------------------------------
        // Event delegation.
        // ------------------------------------------------------------------
        app.addEventListener("click", function (event) {
            const action = event.target.closest("[data-staff-action]");
            if (action) { const card = action.closest("[data-order-id]"); if (card) runAction(card, action); return; }
            const sound = event.target.closest("[data-sound-toggle]");
            if (sound) {
                soundEnabled = !soundEnabled; sound.classList.toggle("is-on", soundEnabled); sound.setAttribute("aria-pressed", String(soundEnabled));
                const icon = sound.querySelector("i"); icon.className = soundEnabled ? "fa fa-volume-up" : "fa fa-volume-off";
                sound.querySelector("span").textContent = soundEnabled ? "Sound on" : "Sound off";
                if (soundEnabled) beep();
            }
            if (event.target.closest("[data-manual-refresh]")) refresh(true);
            if (event.target.closest("[data-open-walkin]")) openWalkin();
            if (event.target.closest("[data-open-session]")) { refreshSession(); setModalOpen(sessionModal, true); }
            if (event.target.closest("[data-fullscreen-toggle]")) {
                const button = event.target.closest("[data-fullscreen-toggle]");
                if (!document.fullscreenElement) {
                    document.documentElement.requestFullscreen().catch(function () {});
                    button.setAttribute("aria-pressed", "true");
                } else {
                    document.exitFullscreen().catch(function () {});
                    button.setAttribute("aria-pressed", "false");
                }
            }
            if (event.target.closest("[data-close-modal]")) setModalOpen(walkinModal, false);
            if (event.target.closest("[data-close-payment]")) setModalOpen(paymentModal, false);
            if (event.target.closest("[data-close-refund]")) setModalOpen(refundModal, false);
            if (event.target.closest("[data-close-session]")) setModalOpen(sessionModal, false);
            if (event.target.closest("[data-close-detail]")) setModalOpen(detailModal, false);
            const category = event.target.closest("[data-pos-category]");
            if (category) {
                posCategory = category.dataset.posCategory;
                app.querySelectorAll("[data-pos-category]").forEach(function (button) { button.classList.toggle("is-active", button === category); });
                renderProducts(app.querySelector("[data-product-search]").value);
            }
            const add = event.target.closest("[data-add-product]");
            if (add) {
                const product = products.find(function (item) { return String(item.id) === add.dataset.addProduct; });
                if (product.modifier_groups && product.modifier_groups.length) {
                    openModifierPicker(product);
                } else {
                    const existing = cart.find(function (item) { return item.product.id === product.id && !item.note && !item.own_cup_quantity && !(item.selectedModifiers && item.selectedModifiers.length); });
                    if (existing) existing.quantity += 1; else cart.push({ product: product, quantity: 1, note: "", own_cup_quantity: 0, selectedModifiers: [] });
                    renderCart();
                }
            }
            const pickerConfirm = event.target.closest("[data-modifier-picker-confirm]");
            if (pickerConfirm) confirmModifierPicker();
            if (event.target.closest("[data-modifier-picker-close]")) closeModifierPicker();
            const delta = event.target.closest("[data-cart-delta]");
            if (delta) {
                const index = Number(delta.closest("[data-cart-index]").dataset.cartIndex); cart[index].quantity += Number(delta.dataset.cartDelta);
                if (cart[index].quantity < 1) cart.splice(index, 1); else cart[index].own_cup_quantity = Math.min(cart[index].own_cup_quantity, cart[index].quantity); renderCart();
            }
            const cupDelta = event.target.closest("[data-cup-delta]");
            if (cupDelta) {
                const index = Number(cupDelta.closest("[data-cart-index]").dataset.cartIndex);
                cart[index].own_cup_quantity = Math.max(0, Math.min(cart[index].quantity, cart[index].own_cup_quantity + Number(cupDelta.dataset.cupDelta))); renderCart();
            }
            const movementButton = event.target.closest("[data-movement-type]");
            if (movementButton) submitMovement(movementButton.dataset.movementType);
        });

        app.addEventListener("input", function (event) {
            if (event.target.matches("[data-product-search]")) renderProducts(event.target.value);
            if (event.target.matches("[data-cart-note]")) cart[Number(event.target.closest("[data-cart-index]").dataset.cartIndex)].note = event.target.value;
            if (event.target.matches('[name="amount_received"]')) updateCashChange(event.target.closest("form"));
            if (event.target.matches("[data-staff-search]")) { searchQuery = event.target.value.trim().toLowerCase(); render(); }
            if (event.target.matches("[data-staff-filter]")) { filterQuery = event.target.value.trim().toLowerCase(); render(); }
        });
        app.addEventListener("submit", function (event) {
            if (event.target.matches("[data-walkin-form]")) { event.preventDefault(); submitWalkin(event.target); }
            if (event.target.matches("[data-payment-form]")) { event.preventDefault(); submitPayment(event.target); }
            if (event.target.matches("[data-refund-form]")) { event.preventDefault(); submitRefund(event.target); }
            if (event.target.matches("[data-session-open-form]")) { event.preventDefault(); submitSessionOpen(event.target); }
            if (event.target.matches("[data-session-close-form]")) { event.preventDefault(); submitSessionClose(event.target); }
        });
        document.addEventListener("keydown", function (event) {
            if (event.key === "Escape") {
                setModalOpen(walkinModal, false); setModalOpen(paymentModal, false);
                setModalOpen(refundModal, false); setModalOpen(sessionModal, false); setModalOpen(detailModal, false);
            }
        });

        async function submitWalkin(form) {
            const submit = form.querySelector(".fo-charge-button"); submit.disabled = true;
            const errorBox = form.querySelector("[data-walkin-error]"); errorBox.hidden = true;
            const payload = {
                customer_name: form.customer_name.value, phone: form.phone.value, order_note: form.order_note.value,
                amount_received: form.amount_received.value,
                idempotency_key: generateRequestId(),
                items: JSON.stringify(cart.map(function (item) {
                    return {
                        product_id: item.product.id, quantity: item.quantity, note: item.note,
                        own_cup_quantity: item.own_cup_quantity,
                        modifier_option_ids: (item.selectedModifiers || []).map(function (m) { return m.id; }),
                    };
                })),
            };
            try {
                await apiCall(app.dataset.walkinUrl, { body: payload });
                cart = []; form.reset(); setModalOpen(walkinModal, false);
                showToast(t("walkinSent")); await refresh(true);
            } catch (error) { errorBox.hidden = false; errorBox.textContent = error.message; }
            finally { submit.disabled = false; }
        }

        async function submitPayment(form) {
            if (!paymentOrder) return;
            const submit = form.querySelector(".fo-charge-button"); submit.disabled = true;
            const errorBox = form.querySelector("[data-payment-error]"); errorBox.hidden = true;
            try {
                await apiCall(app.dataset.actionBase + "/" + paymentOrder.id + "/payment", {
                    body: {
                        amount_received: form.amount_received.value,
                        request_id: generateRequestId(), expected_version: paymentOrder.state_version,
                    },
                });
                setModalOpen(paymentModal, false); showToast(t("paymentCollected")); await refresh(true);
            } catch (error) {
                if (error.errorCode === "stale_version") { errorBox.hidden = false; errorBox.textContent = t("staleVersion"); await refresh(true); }
                else { errorBox.hidden = false; errorBox.textContent = error.message; }
            } finally { submit.disabled = false; }
        }

        async function submitRefund(form) {
            if (!refundContext) return;
            const submit = form.querySelector("[data-refund-submit]"); submit.disabled = true;
            const errorBox = form.querySelector("[data-refund-error]"); errorBox.hidden = true;
            const order = refundContext.order;
            try {
                if (refundContext.kind === "refund") {
                    await apiCall(app.dataset.actionBase + "/" + order.id + "/refund", {
                        body: {
                            amount: form.amount.value, reason: form.reason.value, manager_pin: form.manager_pin ? form.manager_pin.value : "",
                            expected_version: order.state_version,
                        },
                    });
                } else {
                    await apiCall(app.dataset.actionBase + "/" + order.id + "/cancel", {
                        body: {
                            reason: form.reason.value, manager_pin: form.manager_pin ? form.manager_pin.value : "",
                            request_id: generateRequestId(), expected_version: order.state_version,
                        },
                    });
                }
                setModalOpen(refundModal, false); showToast(t("orderUpdated")); await refresh(true);
            } catch (error) {
                if (error.errorCode === "stale_version") { errorBox.hidden = false; errorBox.textContent = t("staleVersion"); await refresh(true); }
                else { errorBox.hidden = false; errorBox.textContent = error.message; }
            } finally { submit.disabled = false; }
        }

        async function submitSessionOpen(form) {
            const errorBox = form.querySelector("[data-session-error]"); errorBox.hidden = true;
            try {
                await apiCall(app.dataset.sessionBase + "/open", { body: { opening_cash: form.opening_cash.value } });
                showToast(t("shiftOpened")); await refreshSession();
            } catch (error) { errorBox.hidden = false; errorBox.textContent = error.message; }
        }
        async function submitMovement(movementType) {
            const form = sessionModal.querySelector("[data-session-movement-form]");
            try {
                await apiCall(app.dataset.sessionBase + "/movement", {
                    body: { movement_type: movementType, amount: form.amount.value, reason: form.reason.value },
                });
                form.reset(); showToast(t("cashRecorded")); await refreshSession();
            } catch (error) { showToast(error.message); }
        }
        async function submitSessionClose(form) {
            const errorBox = form.querySelector("[data-session-close-error]"); errorBox.hidden = true;
            try {
                await apiCall(app.dataset.sessionBase + "/close", { body: { closing_cash_actual: form.closing_cash_actual.value } });
                showToast(t("shiftClosed")); setModalOpen(sessionModal, false); await refreshSession();
            } catch (error) { errorBox.hidden = false; errorBox.textContent = error.message; }
        }

        function updateClock() {
            const now = new Date();
            app.querySelector("[data-staff-clock]").textContent = now.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
            app.querySelector("[data-staff-date]").textContent = now.toLocaleDateString([], { weekday: "short", day: "numeric", month: "short" });
        }
        updateClock(); setInterval(updateClock, 1000);
        refresh(true); refreshPrinterState(); if (role === "cashier") refreshSession();
        schedulePoll();
        setInterval(refreshPrinterState, 15000);
    });
})();
