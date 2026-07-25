(function () {
    "use strict";

    function onReady(callback) {
        if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", callback);
        else callback();
    }

    onReady(function () {
        const app = document.querySelector(".fo-app");
        if (!app) return;
        document.body.classList.add("fo-site");

        const node = function (tag, className, text) {
            const element = document.createElement(tag);
            if (className) element.className = className;
            if (text !== undefined) element.textContent = text;
            return element;
        };
        const drawer = app.querySelector("[data-cart-drawer]");
        const backdrop = app.querySelector(".fo-cart-backdrop");
        const linesBox = app.querySelector("[data-cart-lines]");
        const basketStep = app.querySelector('[data-cart-step="basket"]');
        const detailsStep = app.querySelector('[data-cart-step="details"]');
        const cartBack = app.querySelector("[data-checkout-collapse]");
        const cartTitle = app.querySelector("[data-cart-title]");
        const cartKicker = app.querySelector("[data-cart-kicker]");
        const toast = app.querySelector("[data-toast]");
        const productDialog = app.querySelector("[data-product-dialog]");
        const productDialogBackdrop = app.querySelector("[data-product-dialog-close].fo-product-dialog-backdrop");
        let selectedProduct = null;
        let selectedQuantity = 1;
        let selectedCupQuantity = 0;
        let toastTimer;

        function showToast(message) {
            if (!toast) return;
            toast.textContent = message;
            toast.classList.add("is-visible");
            clearTimeout(toastTimer);
            toastTimer = setTimeout(function () { toast.classList.remove("is-visible"); }, 1800);
        }
        function setCartStep(step) {
            const details = step === "details";
            if (basketStep) basketStep.hidden = details;
            if (detailsStep) detailsStep.hidden = !details;
            if (cartBack) cartBack.hidden = !details;
            if (cartTitle) cartTitle.textContent = details ? "Delivery details" : "Your basket";
            if (cartKicker) cartKicker.textContent = details ? "Step 2 of 2" : "Step 1 of 2";
            app.querySelectorAll("[data-cart-progress]").forEach(function (item) {
                item.classList.toggle("is-active", item.dataset.cartProgress === (details ? "details" : "basket"));
            });
        }
        function openCart(step) {
            if (!drawer) return;
            setCartStep(step || "basket");
            backdrop.hidden = false;
            requestAnimationFrame(function () { backdrop.classList.add("is-visible"); drawer.classList.add("is-open"); });
            drawer.setAttribute("aria-hidden", "false");
            document.body.classList.add("fo-cart-open");
        }
        function closeCart() {
            if (!drawer) return;
            drawer.classList.remove("is-open"); backdrop.classList.remove("is-visible");
            drawer.setAttribute("aria-hidden", "true"); document.body.classList.remove("fo-cart-open");
            setTimeout(function () { backdrop.hidden = true; }, 260);
        }
        app.querySelectorAll("[data-cart-open]").forEach(function (button) { button.addEventListener("click", openCart); });
        app.querySelectorAll("[data-cart-close]").forEach(function (button) { button.addEventListener("click", closeCart); });
        function money(amount) {
            return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(amount) + " " + (app.dataset.currency || "");
        }
        function renderProductDialog() {
            if (!productDialog || !selectedProduct) return;
            productDialog.querySelector("[data-product-quantity]").textContent = selectedQuantity;
            productDialog.querySelector("[data-product-cup-quantity]").textContent = selectedCupQuantity;
            productDialog.querySelector("[data-product-cup-limit]").textContent = selectedQuantity;
            productDialog.querySelector("[data-product-dialog-total]").textContent = money(selectedProduct.price * selectedQuantity - Number(app.dataset.discount || 0) * selectedCupQuantity);
            productDialog.querySelector('[data-product-quantity-delta="-1"]').disabled = selectedQuantity <= 1;
            productDialog.querySelector('[data-product-quantity-delta="1"]').disabled = selectedQuantity >= 20;
            productDialog.querySelector('[data-product-cup-delta="-1"]').disabled = selectedCupQuantity <= 0;
            productDialog.querySelector('[data-product-cup-delta="1"]').disabled = selectedCupQuantity >= selectedQuantity;
        }
        function openProductDialog(button) {
            if (!productDialog || !productDialogBackdrop) return;
            const card = button.closest("[data-product-card]");
            const image = card.querySelector(".fo-product-media img");
            const name = card.querySelector(".fo-product-title-row h3").textContent.trim();
            const description = card.querySelector(".fo-product-body > p").textContent.trim();
            const priceLabel = card.querySelector(".fo-product-title-row > strong").textContent.trim();
            selectedProduct = { id: button.dataset.productId, price: Number(priceLabel.replace(/[^0-9.]/g, "")) || 0, ownCupEligible: button.dataset.ownCupEligible === "1" };
            selectedQuantity = 1; selectedCupQuantity = 0;
            productDialog.querySelector("[data-product-dialog-name]").textContent = name;
            productDialog.querySelector("[data-product-dialog-description]").textContent = description;
            productDialog.querySelector("[data-product-dialog-price]").textContent = priceLabel;
            productDialog.querySelector("[data-product-dialog-image]").src = image ? image.src : "";
            productDialog.querySelector("[data-product-dialog-image]").alt = name;
            productDialog.querySelector("[data-product-own-cup-row]").hidden = !selectedProduct.ownCupEligible;
            productDialog.querySelector("[data-product-note]").value = "";
            renderProductDialog();
            productDialogBackdrop.hidden = false;
            requestAnimationFrame(function () { productDialogBackdrop.classList.add("is-visible"); productDialog.classList.add("is-open"); });
            productDialog.setAttribute("aria-hidden", "false"); document.body.classList.add("fo-product-open");
            productDialog.querySelector("[data-product-dialog-close]").focus();
        }
        function closeProductDialog() {
            if (!productDialog || !productDialogBackdrop) return;
            productDialog.classList.remove("is-open"); productDialogBackdrop.classList.remove("is-visible");
            productDialog.setAttribute("aria-hidden", "true"); document.body.classList.remove("fo-product-open");
            selectedProduct = null; setTimeout(function () { productDialogBackdrop.hidden = true; }, 220);
        }
        app.querySelectorAll("[data-product-dialog-close]").forEach(function (button) { button.addEventListener("click", closeProductDialog); });
        document.addEventListener("keydown", function (event) { if (event.key === "Escape") { closeProductDialog(); closeCart(); } });

        async function updateCart(values) {
            const form = new FormData();
            form.append("csrf_token", app.dataset.csrf || "");
            Object.keys(values).forEach(function (key) { form.append(key, values[key]); });
            const response = await fetch(app.dataset.cartEndpoint, { method: "POST", body: form, credentials: "same-origin" });
            const result = await response.json();
            if (!response.ok) throw new Error(result.error || "Could not update the order.");
            renderCart(result); return result;
        }

        function lineSettings(line) {
            const fragment = document.createDocumentFragment();
            const actions = node("div", "fo-line-actions");
            if (line.own_cup_eligible) {
                const cupControl = node("div", "fo-cart-cup-qty");
                const cupText = node("span", "", "Own cups"); cupText.append(node("small", "", "Save " + app.dataset.discount + " each"));
                const stepper = node("div", "fo-cup-stepper");
                const minus = node("button", "", "−"); minus.type = "button"; minus.dataset.ownCupDelta = "-1"; minus.disabled = !line.own_cup_quantity;
                const plus = node("button", "", "+"); plus.type = "button"; plus.dataset.ownCupDelta = "1"; plus.disabled = line.own_cup_quantity >= line.quantity;
                stepper.append(minus, node("b", "", line.own_cup_quantity + " / " + line.quantity), plus);
                cupControl.append(cupText, stepper); actions.append(cupControl);
            }
            const noteToggle = node("button", "fo-note-toggle"); noteToggle.type = "button"; noteToggle.dataset.noteToggle = "";
            const noteIcon = node("i", "fa fa-pencil"); noteIcon.setAttribute("aria-hidden", "true");
            noteToggle.append(noteIcon, node("span", "", line.note ? "Edit note" : "Add note")); actions.append(noteToggle);
            const noteLabel = node("label", "fo-item-note");
            if (!line.note) noteLabel.hidden = true;
            const noteTitle = node("span", "", "Item note"); noteTitle.append(node("small", "", " Optional")); noteLabel.append(noteTitle);
            const textarea = node("textarea"); textarea.rows = 2; textarea.maxLength = 300; textarea.dataset.lineNote = ""; textarea.placeholder = "e.g. no ice, less spicy…"; textarea.value = line.note || "";
            noteLabel.append(textarea); fragment.append(actions, noteLabel);
            return fragment;
        }

        function renderCart(data) {
            app.querySelectorAll("[data-cart-count]").forEach(function (el) { el.textContent = data.count; });
            const deliveryPill = app.querySelector("[data-delivery-pill]");
            if (deliveryPill) deliveryPill.hidden = !data.count;
            app.querySelectorAll("[data-cart-subtotal]").forEach(function (el) { el.textContent = data.subtotal_formatted; });
            app.querySelectorAll("[data-cart-total]").forEach(function (el) { el.textContent = data.total_formatted; });
            app.querySelectorAll("[data-cart-discount]").forEach(function (el) { el.textContent = "-" + data.discount_formatted; if (el.parentElement) el.parentElement.hidden = !data.discount; });
            const checkout = app.querySelector("[data-checkout-toggle]");
            if (checkout) { checkout.classList.toggle("is-disabled", !data.count); checkout.disabled = !data.count; }
            if (!linesBox) return;
            linesBox.replaceChildren();
            if (!data.lines.length) {
                const empty = node("div", "fo-empty-cart");
                empty.append(node("span", "", "○"), node("h3", "", "Your basket is waiting"), node("p", "", "Add something delicious from the menu."));
                const emptyIcon = node("i", "fa fa-shopping-bag"); emptyIcon.setAttribute("aria-hidden", "true");
                const browse = node("button", "", "Browse the menu"); browse.type = "button"; browse.dataset.cartClose = "";
                empty.replaceChildren(emptyIcon, node("h3", "", "Your basket is empty"), node("p", "", "Add a meal or drink to get started."), browse);
                linesBox.append(empty); setCartStep("basket"); return;
            }
            data.lines.forEach(function (line) {
                const row = node("div", "fo-cart-line"); row.dataset.lineId = line.line_key;
                const image = node("img"); image.src = line.image_url; image.alt = line.name;
                const info = node("div", "fo-cart-line-info");
                const stepper = node("div", "fo-stepper");
                const minus = node("button", "", "−"); minus.type = "button"; minus.dataset.cartDelta = "-1";
                const plus = node("button", "", "+"); plus.type = "button"; plus.dataset.cartDelta = "1";
                stepper.append(minus, node("span", "", line.quantity), plus);
                info.append(node("strong", "", line.name), node("small", "", line.price_formatted), stepper);
                const end = node("div", "fo-cart-line-end");
                const remove = node("button"); remove.type = "button"; remove.dataset.cartRemove = ""; remove.setAttribute("aria-label", "Remove item");
                const removeIcon = node("i", "fa fa-trash-o"); removeIcon.setAttribute("aria-hidden", "true"); remove.append(removeIcon);
                end.append(node("strong", "", line.line_total_formatted), remove);
                row.append(image, info, end, lineSettings(line)); linesBox.append(row);
            });
        }

        app.querySelectorAll("[data-quick-add]").forEach(function (button) {
            button.addEventListener("click", function () { openProductDialog(button); });
        });
        if (productDialog) {
            productDialog.addEventListener("click", function (event) {
                const button = event.target.closest("button");
                if (!button || !selectedProduct) return;
                if (button.hasAttribute("data-product-quantity-delta")) {
                    selectedQuantity = Math.max(1, Math.min(20, selectedQuantity + Number(button.dataset.productQuantityDelta)));
                    selectedCupQuantity = Math.min(selectedCupQuantity, selectedQuantity); renderProductDialog(); return;
                }
                if (button.hasAttribute("data-product-cup-delta")) {
                    selectedCupQuantity = Math.max(0, Math.min(selectedQuantity, selectedCupQuantity + Number(button.dataset.productCupDelta)));
                    renderProductDialog(); return;
                }
                if (button.hasAttribute("data-product-dialog-submit")) {
                    button.disabled = true;
                    updateCart({ product_id: selectedProduct.id, quantity: selectedQuantity, own_cup_quantity: selectedCupQuantity, note: productDialog.querySelector("[data-product-note]").value })
                        .then(function () { closeProductDialog(); showToast("Added to your basket"); })
                        .catch(function (error) { showToast(error.message); })
                        .finally(function () { button.disabled = false; });
                }
            });
        }
        if (linesBox) {
            linesBox.addEventListener("click", async function (event) {
                const button = event.target.closest("button"); const row = event.target.closest("[data-line-id]");
                if (button && button.hasAttribute("data-cart-close")) { closeCart(); return; }
                if (!button || !row) return;
                if (button.hasAttribute("data-note-toggle")) {
                    const note = row.querySelector(".fo-item-note");
                    if (note) { note.hidden = !note.hidden; if (!note.hidden) note.querySelector("textarea").focus(); }
                    return;
                }
                try {
                    if (button.hasAttribute("data-own-cup-delta")) {
                        const current = Number(row.querySelector(".fo-cup-stepper b").textContent.split("/")[0]);
                        await updateCart({line_key: row.dataset.lineId, own_cup_quantity: current + Number(button.dataset.ownCupDelta)});
                        showToast("Own-cup quantity saved");
                    } else if (button.hasAttribute("data-cart-remove")) await updateCart({ line_key: row.dataset.lineId, quantity: 0 });
                    else if (button.hasAttribute("data-cart-delta")) await updateCart({ line_key: row.dataset.lineId, delta: button.dataset.cartDelta });
                } catch (error) { showToast(error.message); }
            });
            linesBox.addEventListener("focusout", async function (event) {
                const row = event.target.closest("[data-line-id]");
                if (!row || !event.target.hasAttribute("data-line-note")) return;
                try { await updateCart({ line_key: row.dataset.lineId, note: event.target.value }); showToast("Item note saved"); }
                catch (error) { showToast(error.message); }
            });
        }

        const checkoutToggle = app.querySelector("[data-checkout-toggle]");
        if (checkoutToggle) checkoutToggle.addEventListener("click", function () { setCartStep("details"); });
        if (cartBack) cartBack.addEventListener("click", function () { setCartStep("basket"); });

        const search = app.querySelector("[data-menu-search]");
        const filterButtons = Array.from(app.querySelectorAll("[data-filter]"));
        const cards = Array.from(app.querySelectorAll("[data-product-card]"));
        let category = "all";
        function filterMenu() {
            const query = search ? search.value.trim().toLowerCase() : ""; let visible = 0;
            cards.forEach(function (card) { const match = (category === "all" || card.dataset.category === category) && (!query || card.dataset.name.includes(query)); card.hidden = !match; if (match) visible += 1; });
            const empty = app.querySelector("[data-no-results]"); if (empty) empty.hidden = visible !== 0;
        }
        filterButtons.forEach(function (button) { button.addEventListener("click", function () { category = button.dataset.filter; filterButtons.forEach(function (item) { item.classList.toggle("is-active", item === button); }); filterMenu(); }); });
        if (search) search.addEventListener("input", filterMenu);

        const params = new URLSearchParams(window.location.search);
        if (params.get("checkout") === "1" || params.has("error")) openCart("details");
    });
})();
