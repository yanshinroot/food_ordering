(function () {
    "use strict";

    function ready(callback) {
        if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", callback);
        else callback();
    }

    ready(function () {
        const app = document.querySelector(".fo-staff-app");
        if (!app) return;
        document.body.classList.add("fo-staff-page");

        const role = app.dataset.role;
        const toast = app.querySelector("[data-staff-toast]");
        let orders = [];
        let soundEnabled = false;
        let lastQueueCount = 0;
        let toastTimer;
        let products = [];
        let cart = [];
        let ownCupDiscount = 500;
        let posCategory = "all";
        let paymentOrder = null;
        const walkinModal = app.querySelector("[data-walkin-modal]");
        const paymentModal = app.querySelector("[data-payment-modal]");

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
            toastTimer = setTimeout(function () { toast.classList.remove("is-visible"); }, 2200);
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
            } catch (_error) { /* Sound is optional. */ }
        }

        function columnFor(order) {
            if (role === "cashier") {
                if (order.status === "pending") return "queue";
                if (order.status === "accepted" || order.status === "preparing") return "preparing";
                if (order.status === "ready") return "ready";
            } else {
                if (order.status === "accepted") return "queue";
                if (order.status === "preparing") return "preparing";
                if (order.status === "ready") return "ready";
            }
            return "hidden";
        }

        function actionButton(label, action, primary, danger) {
            const button = element("button", primary ? "fo-primary-staff-action" : (danger ? "fo-danger-action" : ""), label);
            button.type = "button"; button.dataset.staffAction = action;
            if (danger) button.setAttribute("aria-label", "Cancel order");
            return button;
        }

        function formatMoney(amount, currency) {
            return new Intl.NumberFormat(undefined, {maximumFractionDigits: 0}).format(Number(amount || 0)) + " " + (currency || "MMK");
        }

        function orderCard(order) {
            const card = element("article", "fo-staff-order");
            card.dataset.orderId = order.id;

            const head = element("div", "fo-order-head");
            const number = element("div", "fo-order-number");
            number.append(element("small", "", "Order"), element("strong", "", order.number));
            const ageClass = order.elapsed_minutes >= 20 ? "fo-order-age is-late" : (order.elapsed_minutes >= 10 ? "fo-order-age is-warn" : "fo-order-age");
            const age = element("span", ageClass);
            const clockIcon = element("i", "fa fa-clock-o"); clockIcon.setAttribute("aria-hidden", "true");
            age.append(clockIcon, element("span", "", order.elapsed_minutes + " min")); head.append(number, age);

            const body = element("div", "fo-order-body");
            const customerRow = element("div", "fo-customer-row");
            const customer = element("div"); customer.append(element("strong", "", order.customer), element("small", "", order.phone));
            customerRow.append(customer, element("span", "fo-location", order.department + " · " + order.floor)); body.append(customerRow);

            const lines = element("div", "fo-order-lines");
            order.lines.forEach(function (line) {
                const row = element("div", "fo-order-line");
                row.append(element("b", "", String(line.quantity).replace(/\.0$/, "") + "×"), element("span", "", line.name));
                const detail = [line.own_cup_quantity ? "Own cup × " + line.own_cup_quantity : "", line.note].filter(Boolean).join(" · ");
                if (detail) row.append(element("small", "", detail));
                lines.append(row);
            });
            body.append(lines);
            if (order.note) {
                const note = element("div", "fo-order-note");
                const noteIcon = element("i", "fa fa-sticky-note-o"); noteIcon.setAttribute("aria-hidden", "true");
                note.append(noteIcon, element("span", "", order.note)); body.append(note);
            }
            const meta = element("div", "fo-order-meta");
            const leftMeta = element("div", "fo-order-meta-left");
            leftMeta.append(element("span", "fo-source-badge", order.source));
            if (role === "cashier") {
                const paymentLabel = order.payment_status === "paid" ? "Paid · " + order.payment_method : "Payment due";
                leftMeta.append(element("span", order.payment_status === "paid" ? "fo-payment-state is-paid" : "fo-payment-state", paymentLabel));
            }
            meta.append(leftMeta);
            if (role === "cashier") meta.append(element("strong", "fo-order-total", order.total));
            else {
                const printState = order.print_jobs.kitchen || "not queued";
                meta.append(element("span", printState === "printed" ? "fo-print-state is-printed" : "fo-print-state", "Print: " + printState));
            }
            body.append(meta); card.append(head, body);

            const actions = element("div", "fo-order-actions");
            if (role === "cashier" && order.status === "pending") {
                const cancel = actionButton("", "cancel", false, true);
                const trash = element("i", "fa fa-times"); trash.setAttribute("aria-hidden", "true"); cancel.append(trash);
                actions.append(cancel, actionButton("Accept & print", "accept", true));
            } else if (role === "cashier" && order.status === "ready") {
                if (order.payment_status !== "paid") actions.append(actionButton("Collect payment", "payment", true));
                else actions.append(actionButton("Complete order", "complete", true));
            } else if (role === "cashier" && order.payment_status !== "paid") {
                actions.append(actionButton("Collect payment", "payment", true));
            } else if (role === "kitchen" && order.status === "accepted") {
                actions.append(actionButton("Start preparing", "prepare", true));
            } else if (role === "kitchen" && order.status === "preparing") {
                actions.append(actionButton("Mark ready", "ready", true));
            }
            if (actions.children.length) card.append(actions);
            return card;
        }

        function render() {
            const buckets = {queue: [], preparing: [], ready: []};
            orders.forEach(function (order) {
                const column = columnFor(order);
                if (buckets[column]) buckets[column].push(order);
            });
            Object.keys(buckets).forEach(function (name) {
                const container = app.querySelector('[data-order-column="' + name + '"]');
                const count = buckets[name].length;
                app.querySelectorAll('[data-column-count="' + name + '"], [data-stat="' + name + '"]').forEach(function (item) { item.textContent = count; });
                container.replaceChildren();
                if (!count) {
                    const empty = element("div", "fo-empty-column");
                    const icon = element("i", "fa fa-check-circle-o"); icon.setAttribute("aria-hidden", "true");
                    empty.append(icon, element("strong", "", "All clear"), element("small", "", "New orders will appear automatically.")); container.append(empty);
                } else buckets[name].forEach(function (order) { container.append(orderCard(order)); });
            });
            const queueCount = buckets.queue.length;
            if (lastQueueCount && queueCount > lastQueueCount) beep();
            lastQueueCount = queueCount;
        }

        async function refresh(showError) {
            try {
                const response = await fetch(app.dataset.ordersUrl + "?role=" + encodeURIComponent(role), {credentials: "same-origin"});
                const data = await response.json();
                if (!response.ok) throw new Error(data.error || "Could not refresh orders.");
                orders = data.orders || []; render();
                app.querySelector("[data-last-sync]").textContent = "just now";
            } catch (error) { if (showError) showToast(error.message); }
        }

        async function refreshPrinterState() {
            const chip = app.querySelector("[data-printer-chip]");
            if (!chip || !app.dataset.printersUrl) return;
            try {
                const response = await fetch(app.dataset.printersUrl + "?role=" + encodeURIComponent(role), {credentials: "same-origin"});
                const data = await response.json();
                if (!response.ok) throw new Error(data.error || "Printer status unavailable");
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

        async function runAction(card, button) {
            if (button.dataset.staffAction === "payment") {
                openPayment(orders.find(function (order) { return String(order.id) === card.dataset.orderId; }));
                return;
            }
            card.querySelectorAll("button").forEach(function (item) { item.disabled = true; });
            const form = new FormData(); form.append("csrf_token", app.dataset.csrf || ""); form.append("role", role);
            try {
                const url = app.dataset.actionBase + "/" + card.dataset.orderId + "/" + button.dataset.staffAction;
                const response = await fetch(url, {method: "POST", body: form, credentials: "same-origin"});
                const data = await response.json();
                if (!response.ok) throw new Error(data.error || "Action failed.");
                showToast("Order updated"); await refresh(true);
            } catch (error) { showToast(error.message); card.querySelectorAll("button").forEach(function (item) { item.disabled = false; }); }
        }

        function setModalOpen(modal, open) {
            if (!modal) return;
            modal.classList.toggle("is-open", open);
            modal.setAttribute("aria-hidden", String(!open));
            document.body.classList.toggle("fo-modal-open", open);
        }

        function updateCashChange(form) {
            const received = Number(String(form.querySelector('[name="amount_received"]').value || "0").replace(/,/g, ""));
            const due = form.matches("[data-walkin-form]") ? cartTotal() : Number(paymentOrder && paymentOrder.amount_total || 0);
            form.querySelector("[data-cash-change] strong").textContent = formatMoney(Math.max(0, received - due), products[0] && products[0].currency || paymentOrder && paymentOrder.currency);
        }

        async function loadProducts() {
            if (products.length) return;
            const response = await fetch(app.dataset.productsUrl, {credentials: "same-origin"});
            const data = await response.json();
            if (!response.ok) throw new Error(data.error || "Could not load the menu.");
            products = data.products || [];
            ownCupDiscount = Number(data.own_cup_discount || 0);
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

        function cartTotal() {
            return cart.reduce(function (sum, item) {
                return sum + (item.product.price * item.quantity) - (ownCupDiscount * item.own_cup_quantity);
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
                const name = element("div"); name.append(element("strong", "", item.product.name), element("small", "", formatMoney(item.product.price * item.quantity, item.product.currency)));
                const quantity = element("div", "fo-cart-quantity");
                const minus = element("button", "", "−"); minus.type = "button"; minus.dataset.cartDelta = "-1";
                const plus = element("button", "", "+"); plus.type = "button"; plus.dataset.cartDelta = "1";
                quantity.append(minus, element("b", "", item.quantity), plus); top.append(thumb, name, quantity); row.append(top);
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

        async function openWalkin() {
            setModalOpen(walkinModal, true);
            try { await loadProducts(); renderCart(); }
            catch (error) { showToast(error.message); }
        }

        function openPayment(order) {
            if (!order || !paymentModal) return;
            paymentOrder = order;
            paymentModal.querySelector("[data-payment-order]").textContent = order.number;
            paymentModal.querySelector("[data-payment-due]").textContent = order.total;
            paymentModal.querySelector('[name="amount_received"]').value = Math.ceil(order.amount_total || 0);
            updateCashChange(paymentModal.querySelector("[data-payment-form]"));
            setModalOpen(paymentModal, true);
        }

        async function submitWalkin(form) {
            const submit = form.querySelector(".fo-charge-button"); submit.disabled = true;
            const data = new FormData(form); data.append("csrf_token", app.dataset.csrf || "");
            data.append("items", JSON.stringify(cart.map(function (item) { return {product_id: item.product.id, quantity: item.quantity, note: item.note, own_cup_quantity: item.own_cup_quantity}; })));
            try {
                const response = await fetch(app.dataset.walkinUrl, {method: "POST", body: data, credentials: "same-origin"});
                const result = await response.json(); if (!response.ok) throw new Error(result.error || "Could not create order.");
                cart = []; form.reset(); setModalOpen(walkinModal, false);
                showToast("Walk-in order paid and sent to kitchen"); await refresh(true);
            } catch (error) { showToast(error.message); submit.disabled = false; }
        }

        async function submitPayment(form) {
            if (!paymentOrder) return;
            const submit = form.querySelector(".fo-charge-button"); submit.disabled = true;
            const data = new FormData(form); data.append("csrf_token", app.dataset.csrf || "");
            try {
                const response = await fetch(app.dataset.actionBase + "/" + paymentOrder.id + "/payment", {method: "POST", body: data, credentials: "same-origin"});
                const result = await response.json(); if (!response.ok) throw new Error(result.error || "Payment failed.");
                setModalOpen(paymentModal, false); showToast("Payment collected"); await refresh(true);
            } catch (error) { showToast(error.message); submit.disabled = false; }
        }

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
            const openWalkinButton = event.target.closest("[data-open-walkin]");
            if (openWalkinButton) openWalkin();
            if (event.target.closest("[data-close-modal]")) setModalOpen(walkinModal, false);
            if (event.target.closest("[data-close-payment]")) setModalOpen(paymentModal, false);
            const category = event.target.closest("[data-pos-category]");
            if (category) {
                posCategory = category.dataset.posCategory;
                app.querySelectorAll("[data-pos-category]").forEach(function (button) { button.classList.toggle("is-active", button === category); });
                renderProducts(app.querySelector("[data-product-search]").value);
            }
            const add = event.target.closest("[data-add-product]");
            if (add) {
                const product = products.find(function (item) { return String(item.id) === add.dataset.addProduct; });
                const existing = cart.find(function (item) { return item.product.id === product.id && !item.note && !item.own_cup_quantity; });
                if (existing) existing.quantity += 1; else cart.push({product: product, quantity: 1, note: "", own_cup_quantity: 0});
                renderCart();
            }
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
        });

        app.addEventListener("input", function (event) {
            if (event.target.matches("[data-product-search]")) renderProducts(event.target.value);
            if (event.target.matches("[data-cart-note]")) cart[Number(event.target.closest("[data-cart-index]").dataset.cartIndex)].note = event.target.value;
            if (event.target.matches('[name="amount_received"]')) updateCashChange(event.target.closest("form"));
        });
        app.addEventListener("submit", function (event) {
            if (event.target.matches("[data-walkin-form]")) { event.preventDefault(); submitWalkin(event.target); }
            if (event.target.matches("[data-payment-form]")) { event.preventDefault(); submitPayment(event.target); }
        });
        document.addEventListener("keydown", function (event) {
            if (event.key === "Escape") { setModalOpen(walkinModal, false); setModalOpen(paymentModal, false); }
        });

        function updateClock() {
            const now = new Date();
            app.querySelector("[data-staff-clock]").textContent = now.toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"});
            app.querySelector("[data-staff-date]").textContent = now.toLocaleDateString([], {weekday: "short", day: "numeric", month: "short"});
        }
        updateClock(); setInterval(updateClock, 1000);
        refresh(true); refreshPrinterState();
        setInterval(function () { refresh(false); refreshPrinterState(); }, 5000);
    });
})();
