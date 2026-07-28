(function () {
    "use strict";

    // ------------------------------------------------------------------
    // Minimal translation-ready strings. Server-rendered text already goes
    // through Odoo's own QWeb translation mechanism; this covers the
    // strings generated dynamically in JS so nothing customer-facing is
    // hardcoded outside a translatable resource.
    // ------------------------------------------------------------------
    const STRINGS = {
        en: {
            addedToBasket: "Added to your basket",
            ownCupSaved: "Own-cup quantity saved",
            itemNoteSaved: "Item note saved",
            basketEmptyTitle: "Your basket is empty",
            basketEmptyBody: "Add a meal or drink to get started.",
            browseMenu: "Browse the menu",
            addNote: "Add note",
            editNote: "Edit note",
            removeItem: "Remove item",
            itemRemoved: "Item removed",
            undo: "Undo",
            selectAtLeast: "Please select at least {min} option(s) for “{group}”.",
            selectAtMost: "Please select at most {max} option(s) for “{group}”.",
            offline: "You're offline. Some actions are unavailable until you're back online.",
            online: "Back online.",
            submitting: "Placing your order…",
            submitFailed: "Something went wrong. Please try again.",
            submitOffline: "You're offline. Connect to the internet to place your order.",
            placeOrder: "Place my order",
            statusReceived: "Received",
            statusUpdated: "Updated just now",
        },
        my: {
            addedToBasket: "ခြင်းတောင်းထဲ ထည့်ပြီးပါပြီ",
            ownCupSaved: "ကိုယ်ပိုင်ခွက် အရေအတွက် သိမ်းပြီးပါပြီ",
            itemNoteSaved: "မှတ်ချက် သိမ်းပြီးပါပြီ",
            basketEmptyTitle: "ခြင်းတောင်း ဗလာဖြစ်နေပါသည်",
            basketEmptyBody: "စတင်ရန် အစားအစာ သို့မဟုတ် အဖျော်ယမကာ ထည့်ပါ။",
            browseMenu: "မီနူးကြည့်ရန်",
            addNote: "မှတ်ချက်ထည့်ရန်",
            editNote: "မှတ်ချက်ပြင်ရန်",
            removeItem: "ဖယ်ရှားရန်",
            itemRemoved: "ဖယ်ရှားပြီးပါပြီ",
            undo: "ပြန်ဖြည့်ရန်",
            selectAtLeast: "“{group}” အတွက် အနည်းဆုံး {min} ခု ရွေးပါ။",
            selectAtMost: "“{group}” အတွက် အများဆုံး {max} ခု ရွေးပါ။",
            offline: "အင်တာနက်မရှိပါ။ အွန်လိုင်းပြန်ရောက်မှ လုပ်ဆောင်နိုင်ပါမည်။",
            online: "အင်တာနက် ပြန်လည်ရရှိပါပြီ။",
            submitting: "အော်ဒါတင်နေသည်…",
            submitFailed: "အမှားတစ်ခုဖြစ်သွားပါသည်။ ထပ်စမ်းကြည့်ပါ။",
            submitOffline: "အင်တာနက်မရှိပါ။ အော်ဒါတင်ရန် ချိတ်ဆက်ပါ။",
            placeOrder: "အော်ဒါတင်မည်",
            statusReceived: "လက်ခံရရှိပြီး",
            statusUpdated: "ယခုလေးတင် အပ်ဒိတ်လုပ်ခဲ့သည်",
        },
    };
    const LANG = (document.documentElement.getAttribute("lang") || "").toLowerCase().startsWith("my") ? "my" : "en";
    function t(key, vars) {
        let text = (STRINGS[LANG] && STRINGS[LANG][key]) || STRINGS.en[key] || key;
        if (vars) Object.keys(vars).forEach(function (k) { text = text.replace("{" + k + "}", vars[k]); });
        return text;
    }

    function onReady(callback) {
        if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", callback);
        else callback();
    }

    // ------------------------------------------------------------------
    // Offline banner + status: shared across the catalog and tracking
    // pages. Reflects navigator.onLine and is nudged by failed fetches.
    // ------------------------------------------------------------------
    function setupOfflineBanner(root) {
        const banner = root.querySelector("[data-offline-banner]");
        function apply(online) {
            if (banner) banner.hidden = online;
            root.classList.toggle("fo-is-offline", !online);
        }
        apply(navigator.onLine);
        window.addEventListener("online", function () { apply(true); });
        window.addEventListener("offline", function () { apply(false); });
        return { isOnline: function () { return navigator.onLine; } };
    }

    onReady(function () {
        const app = document.querySelector(".fo-app");
        if (!app) return;
        document.body.classList.add("fo-site");
        const offlineState = setupOfflineBanner(app);

        // Decode the server-embedded catalog/modifier data (base64 avoids
        // any HTML-escaping pitfalls of embedding raw JSON in a script tag).
        let CATALOG = { products: {}, promotion: null };
        const catalogDataEl = document.getElementById("fo-catalog-data");
        if (catalogDataEl && catalogDataEl.textContent.trim()) {
            try { CATALOG = JSON.parse(atob(catalogDataEl.textContent.trim())); } catch (e) { /* keep default */ }
        }

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
        let selectedModifiers = {}; // groupId -> [optionId, ...]
        let toastTimer;
        let lastFocusedBeforeDialog = null;

        function showToast(message, actionLabel, actionFn) {
            if (!toast) return;
            toast.replaceChildren();
            toast.append(node("span", "", message));
            if (actionLabel && actionFn) {
                const action = node("button", "fo-toast-action", actionLabel);
                action.type = "button";
                action.addEventListener("click", function () { clearTimeout(toastTimer); toast.classList.remove("is-visible"); actionFn(); });
                toast.append(action);
            }
            toast.classList.add("is-visible");
            clearTimeout(toastTimer);
            toastTimer = setTimeout(function () { toast.classList.remove("is-visible"); }, actionLabel ? 5000 : 1800);
        }

        // ------------------------------------------------------------------
        // Focus trap helper for dialogs (product sheet + cart drawer).
        // ------------------------------------------------------------------
        function trapFocus(container, event) {
            if (event.key !== "Tab") return;
            const focusable = Array.from(container.querySelectorAll(
                'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])'
            )).filter(function (el) { return el.offsetParent !== null; });
            if (!focusable.length) return;
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && document.activeElement === first) { last.focus(); event.preventDefault(); }
            else if (!event.shiftKey && document.activeElement === last) { first.focus(); event.preventDefault(); }
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
            if (details) ensureIdempotencyKey();
        }
        function openCart(step) {
            if (!drawer) return;
            lastFocusedBeforeDialog = document.activeElement;
            setCartStep(step || "basket");
            backdrop.hidden = false;
            requestAnimationFrame(function () { backdrop.classList.add("is-visible"); drawer.classList.add("is-open"); });
            drawer.setAttribute("aria-hidden", "false");
            document.body.classList.add("fo-cart-open");
            const closeBtn = drawer.querySelector(".fo-cart-close");
            if (closeBtn) closeBtn.focus();
        }
        function closeCart() {
            if (!drawer) return;
            drawer.classList.remove("is-open"); backdrop.classList.remove("is-visible");
            drawer.setAttribute("aria-hidden", "true"); document.body.classList.remove("fo-cart-open");
            setTimeout(function () { backdrop.hidden = true; }, 260);
            if (lastFocusedBeforeDialog && lastFocusedBeforeDialog.focus) lastFocusedBeforeDialog.focus();
        }
        app.querySelectorAll("[data-cart-open]").forEach(function (button) { button.addEventListener("click", openCart); });
        app.querySelectorAll("[data-cart-close]").forEach(function (button) { button.addEventListener("click", closeCart); });
        if (drawer) drawer.addEventListener("keydown", function (event) { trapFocus(drawer, event); });

        function money(amount) {
            return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(amount) + " " + (app.dataset.currency || "");
        }

        // ------------------------------------------------------------------
        // Modifier groups inside the product dialog.
        // ------------------------------------------------------------------
        function modifierExtraTotal() {
            let total = 0;
            if (!selectedProduct || !selectedProduct.modifierGroups) return 0;
            selectedProduct.modifierGroups.forEach(function (group) {
                (selectedModifiers[group.id] || []).forEach(function (optionId) {
                    const option = group.options.find(function (o) { return o.id === optionId; });
                    if (option) total += option.price_extra || 0;
                });
            });
            return total;
        }
        function validateModifiers() {
            if (!selectedProduct || !selectedProduct.modifierGroups) return null;
            for (const group of selectedProduct.modifierGroups) {
                const count = (selectedModifiers[group.id] || []).length;
                if (count < group.min_selection) return t("selectAtLeast", { min: group.min_selection, group: group.name });
                if (group.max_selection && count > group.max_selection) return t("selectAtMost", { max: group.max_selection, group: group.name });
            }
            return null;
        }
        function renderModifierGroups() {
            const container = productDialog && productDialog.querySelector("[data-product-modifiers]");
            if (!container) return;
            container.replaceChildren();
            if (!selectedProduct || !selectedProduct.modifierGroups || !selectedProduct.modifierGroups.length) return;
            selectedProduct.modifierGroups.forEach(function (group) {
                const fieldset = node("fieldset", "fo-modifier-group");
                const legend = node("legend");
                legend.append(node("span", "", group.name));
                if (group.required || group.min_selection) legend.append(node("small", "", "Required"));
                fieldset.append(legend);
                const inputType = group.selection_type === "multiple" ? "checkbox" : "radio";
                group.options.forEach(function (option) {
                    const row = node("label", "fo-option-row");
                    const input = document.createElement("input");
                    input.type = inputType;
                    input.name = "fo-modifier-group-" + group.id;
                    input.value = option.id;
                    input.checked = (selectedModifiers[group.id] || []).includes(option.id);
                    input.addEventListener("change", function () {
                        const current = selectedModifiers[group.id] || [];
                        if (inputType === "radio") selectedModifiers[group.id] = [option.id];
                        else selectedModifiers[group.id] = input.checked
                            ? current.concat([option.id])
                            : current.filter(function (id) { return id !== option.id; });
                        renderProductDialog();
                    });
                    const check = node("span", "fo-option-check");
                    const textWrap = node("span");
                    textWrap.append(node("strong", "", option.name));
                    if (option.price_extra) textWrap.append(node("small", "", "+" + money(option.price_extra)));
                    row.append(input, check, textWrap);
                    fieldset.append(row);
                });
                container.append(fieldset);
            });
        }

        function renderProductDialog() {
            if (!productDialog || !selectedProduct) return;
            productDialog.querySelector("[data-product-quantity]").textContent = selectedQuantity;
            productDialog.querySelector("[data-product-cup-quantity]").textContent = selectedCupQuantity;
            productDialog.querySelector("[data-product-cup-limit]").textContent = selectedQuantity;
            const unitPrice = selectedProduct.price + modifierExtraTotal();
            const total = unitPrice * selectedQuantity - Number(app.dataset.discount || 0) * selectedCupQuantity;
            productDialog.querySelector("[data-product-dialog-total]").textContent = money(Math.max(0, total));
            productDialog.querySelector('[data-product-quantity-delta="-1"]').disabled = selectedQuantity <= 1;
            productDialog.querySelector('[data-product-quantity-delta="1"]').disabled = selectedQuantity >= 20;
            productDialog.querySelector('[data-product-cup-delta="-1"]').disabled = selectedCupQuantity <= 0;
            productDialog.querySelector('[data-product-cup-delta="1"]').disabled = selectedCupQuantity >= selectedQuantity;
            const errorBox = productDialog.querySelector("[data-product-modifier-error]");
            const submitBtn = productDialog.querySelector("[data-product-dialog-submit]");
            const error = validateModifiers();
            if (errorBox) { errorBox.textContent = error || ""; errorBox.hidden = !error; }
            if (submitBtn) submitBtn.disabled = !!error;
        }
        function openProductDialog(button) {
            if (!productDialog || !productDialogBackdrop) return;
            const card = button.closest("[data-product-card]");
            const image = card.querySelector(".fo-product-media img");
            const name = card.querySelector(".fo-product-title-row h3").textContent.trim();
            const description = card.querySelector(".fo-product-body > p").textContent.trim();
            const priceLabel = card.querySelector(".fo-product-title-row > strong").textContent.trim();
            const tmplId = button.dataset.productTmplId;
            const catalogEntry = CATALOG.products[tmplId] || {};
            selectedProduct = {
                id: button.dataset.productId,
                price: Number(priceLabel.replace(/[^0-9.]/g, "")) || 0,
                ownCupEligible: button.dataset.ownCupEligible === "1",
                modifierGroups: catalogEntry.modifierGroups || [],
            };
            selectedQuantity = 1; selectedCupQuantity = 0; selectedModifiers = {};
            // Pre-select single-choice groups' first option so a required
            // group isn't shown in an invalid state by default.
            selectedProduct.modifierGroups.forEach(function (group) {
                if (group.selection_type === "single" && group.options.length && (group.required || group.min_selection)) {
                    selectedModifiers[group.id] = [group.options[0].id];
                }
            });
            lastFocusedBeforeDialog = document.activeElement;
            productDialog.querySelector("[data-product-dialog-name]").textContent = name;
            productDialog.querySelector("[data-product-dialog-description]").textContent = description;
            productDialog.querySelector("[data-product-dialog-price]").textContent = priceLabel;
            productDialog.querySelector("[data-product-dialog-image]").src = image ? image.src : "";
            productDialog.querySelector("[data-product-dialog-image]").alt = name;
            productDialog.querySelector("[data-product-own-cup-row]").hidden = !selectedProduct.ownCupEligible;
            productDialog.querySelector("[data-product-note]").value = "";
            renderModifierGroups();
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
            if (lastFocusedBeforeDialog && lastFocusedBeforeDialog.focus) lastFocusedBeforeDialog.focus();
        }
        app.querySelectorAll("[data-product-dialog-close]").forEach(function (button) { button.addEventListener("click", closeProductDialog); });
        document.addEventListener("keydown", function (event) { if (event.key === "Escape") { closeProductDialog(); closeCart(); } });
        if (productDialog) productDialog.addEventListener("keydown", function (event) { trapFocus(productDialog, event); });

        // ------------------------------------------------------------------
        // Cart persistence to localStorage — a non-sensitive mirror (product
        // ids/quantities/modifiers/notes only, no tokens) purely so the UI
        // has something to show immediately if a request is in flight or
        // fails; the server session remains the source of truth.
        // ------------------------------------------------------------------
        const CART_CACHE_KEY = "fo_cart_cache_v1";
        function cacheCart(data) {
            try { localStorage.setItem(CART_CACHE_KEY, JSON.stringify({ savedAt: Date.now(), data: data })); } catch (e) { /* ignore quota/private mode */ }
        }

        async function updateCart(values) {
            const form = new FormData();
            form.append("csrf_token", app.dataset.csrf || "");
            Object.keys(values).forEach(function (key) {
                const value = values[key];
                if (Array.isArray(value)) value.forEach(function (v) { form.append(key, v); });
                else form.append(key, value);
            });
            let response;
            try {
                response = await fetch(app.dataset.cartEndpoint, { method: "POST", body: form, credentials: "same-origin" });
            } catch (networkError) {
                throw new Error(offlineState.isOnline() ? "Could not update the order." : t("offline"));
            }
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
                minus.setAttribute("aria-label", "Decrease own-cup quantity");
                const plus = node("button", "", "+"); plus.type = "button"; plus.dataset.ownCupDelta = "1"; plus.disabled = line.own_cup_quantity >= line.quantity;
                plus.setAttribute("aria-label", "Increase own-cup quantity");
                stepper.append(minus, node("b", "", line.own_cup_quantity + " / " + line.quantity), plus);
                cupControl.append(cupText, stepper); actions.append(cupControl);
            }
            const noteToggle = node("button", "fo-note-toggle"); noteToggle.type = "button"; noteToggle.dataset.noteToggle = "";
            const noteIcon = node("i", "fa fa-pencil"); noteIcon.setAttribute("aria-hidden", "true");
            noteToggle.append(noteIcon, node("span", "", line.note ? t("editNote") : t("addNote"))); actions.append(noteToggle);
            const noteLabel = node("label", "fo-item-note");
            if (!line.note) noteLabel.hidden = true;
            const noteTitle = node("span", "", "Item note"); noteTitle.append(node("small", "", " Optional")); noteLabel.append(noteTitle);
            const textarea = node("textarea"); textarea.rows = 2; textarea.maxLength = 300; textarea.dataset.lineNote = ""; textarea.placeholder = "e.g. no ice, less spicy…"; textarea.value = line.note || "";
            noteLabel.append(textarea); fragment.append(actions, noteLabel);
            return fragment;
        }

        function renderCart(data) {
            cacheCart(data);
            app.querySelectorAll("[data-cart-count]").forEach(function (el) { el.textContent = data.count; });
            const deliveryPill = app.querySelector("[data-delivery-pill]");
            if (deliveryPill) deliveryPill.hidden = !data.count;
            app.querySelectorAll("[data-cart-subtotal]").forEach(function (el) { el.textContent = data.subtotal_formatted; });
            app.querySelectorAll("[data-cart-total]").forEach(function (el) { el.textContent = data.total_formatted; });
            app.querySelectorAll("[data-cart-discount]").forEach(function (el) { el.textContent = "-" + data.discount_formatted; if (el.parentElement) el.parentElement.hidden = !data.discount; });
            app.querySelectorAll("[data-cart-modifier-total]").forEach(function (el) { el.textContent = data.modifier_total_formatted; if (el.parentElement) el.parentElement.hidden = !data.modifier_total; });
            app.querySelectorAll("[data-cart-promotion-discount]").forEach(function (el) { el.textContent = "-" + data.promotion_discount_formatted; if (el.parentElement) el.parentElement.hidden = !data.promotion_discount; });
            const checkout = app.querySelector("[data-checkout-toggle]");
            if (checkout) { checkout.classList.toggle("is-disabled", !data.count); checkout.disabled = !data.count; }
            if (!linesBox) return;
            linesBox.replaceChildren();
            if (!data.lines.length) {
                const empty = node("div", "fo-empty-cart");
                const emptyIcon = node("i", "fa fa-shopping-bag"); emptyIcon.setAttribute("aria-hidden", "true");
                const browse = node("button", "", t("browseMenu")); browse.type = "button"; browse.dataset.cartClose = "";
                empty.replaceChildren(emptyIcon, node("h3", "", t("basketEmptyTitle")), node("p", "", t("basketEmptyBody")), browse);
                linesBox.append(empty); setCartStep("basket"); return;
            }
            data.lines.forEach(function (line) {
                const row = node("div", "fo-cart-line"); row.dataset.lineId = line.line_key;
                const image = node("img"); image.src = line.image_url; image.alt = line.name;
                const info = node("div", "fo-cart-line-info");
                const stepper = node("div", "fo-stepper");
                const minus = node("button", "", "−"); minus.type = "button"; minus.dataset.cartDelta = "-1"; minus.setAttribute("aria-label", "Decrease quantity");
                const plus = node("button", "", "+"); plus.type = "button"; plus.dataset.cartDelta = "1"; plus.setAttribute("aria-label", "Increase quantity");
                stepper.append(minus, node("span", "", line.quantity), plus);
                info.append(node("strong", "", line.name));
                if (line.modifier_summary) info.append(node("small", "fo-modifier-summary", line.modifier_summary));
                info.append(node("small", "", line.price_formatted), stepper);
                const end = node("div", "fo-cart-line-end");
                const remove = node("button"); remove.type = "button"; remove.dataset.cartRemove = ""; remove.setAttribute("aria-label", t("removeItem"));
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
                    if (validateModifiers()) return;
                    button.disabled = true;
                    const modifierIds = [];
                    Object.values(selectedModifiers).forEach(function (ids) { modifierIds.push.apply(modifierIds, ids); });
                    updateCart({
                        product_id: selectedProduct.id, quantity: selectedQuantity, own_cup_quantity: selectedCupQuantity,
                        note: productDialog.querySelector("[data-product-note]").value, modifier_option_ids: modifierIds,
                    })
                        .then(function () { closeProductDialog(); showToast(t("addedToBasket")); })
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
                        await updateCart({ line_key: row.dataset.lineId, own_cup_quantity: current + Number(button.dataset.ownCupDelta) });
                        showToast(t("ownCupSaved"));
                    } else if (button.hasAttribute("data-cart-remove")) {
                        // Undo window: keep the removed line's data so the
                        // toast action can restore it via a fresh add.
                        const cachedRaw = localStorage.getItem(CART_CACHE_KEY);
                        let removedSnapshot = null;
                        try {
                            const cached = cachedRaw && JSON.parse(cachedRaw).data;
                            removedSnapshot = cached && cached.lines.find(function (l) { return l.line_key === row.dataset.lineId; });
                        } catch (e) { /* ignore */ }
                        await updateCart({ line_key: row.dataset.lineId, quantity: 0 });
                        if (removedSnapshot) {
                            showToast(t("itemRemoved"), t("undo"), function () {
                                updateCart({
                                    product_id: removedSnapshot.id, quantity: removedSnapshot.quantity,
                                    modifier_option_ids: removedSnapshot.modifier_option_ids || [],
                                    note: removedSnapshot.note || "",
                                }).catch(function (error) { showToast(error.message); });
                            });
                        }
                    } else if (button.hasAttribute("data-cart-delta")) await updateCart({ line_key: row.dataset.lineId, delta: button.dataset.cartDelta });
                } catch (error) { showToast(error.message); }
            });
            linesBox.addEventListener("focusout", async function (event) {
                const row = event.target.closest("[data-line-id]");
                if (!row || !event.target.hasAttribute("data-line-note")) return;
                try { await updateCart({ line_key: row.dataset.lineId, note: event.target.value }); showToast(t("itemNoteSaved")); }
                catch (error) { showToast(error.message); }
            });
        }

        const checkoutToggle = app.querySelector("[data-checkout-toggle]");
        if (checkoutToggle) checkoutToggle.addEventListener("click", function () { setCartStep("details"); });
        if (cartBack) cartBack.addEventListener("click", function () { setCartStep("basket"); });

        // ------------------------------------------------------------------
        // Checkout: idempotency key + async submit with progress, inline
        // errors that preserve entered field values, and duplicate-submit
        // prevention.
        // ------------------------------------------------------------------
        function ensureIdempotencyKey() {
            const field = app.querySelector("[data-idempotency-key]");
            if (field && !field.value) {
                field.value = (window.crypto && crypto.randomUUID) ? crypto.randomUUID()
                    : "fo-" + Date.now() + "-" + Math.random().toString(16).slice(2);
            }
        }
        const checkoutForm = app.querySelector("[data-checkout-form]");
        if (checkoutForm) {
            checkoutForm.addEventListener("submit", async function (event) {
                event.preventDefault();
                const submitButton = checkoutForm.querySelector("[data-submit-button]");
                const submitLabel = checkoutForm.querySelector("[data-submit-label]");
                const errorBox = app.querySelector("[data-submit-error]");
                if (submitButton && submitButton.disabled) return; // already submitting
                if (!offlineState.isOnline()) {
                    if (errorBox) { errorBox.hidden = false; errorBox.querySelector("span").textContent = t("submitOffline"); }
                    return;
                }
                if (errorBox) errorBox.hidden = true;
                if (submitButton) submitButton.disabled = true;
                if (submitLabel) submitLabel.textContent = t("submitting");
                try {
                    const formData = new FormData(checkoutForm);
                    const response = await fetch(checkoutForm.action, {
                        method: "POST", body: formData, credentials: "same-origin",
                        headers: { "X-Requested-With": "fetch" },
                    });
                    const result = await response.json().catch(function () { return {}; });
                    if (!response.ok) {
                        if (errorBox) { errorBox.hidden = false; errorBox.querySelector("span").textContent = t("submitFailed"); }
                        // Entered values are untouched — nothing is reset on failure.
                        return;
                    }
                    window.location.href = result.status_url || "/food";
                } catch (networkError) {
                    if (errorBox) { errorBox.hidden = false; errorBox.querySelector("span").textContent = t("submitFailed"); }
                } finally {
                    if (submitButton) submitButton.disabled = false;
                    if (submitLabel) submitLabel.textContent = t("placeOrder");
                }
            });
        }

        const search = app.querySelector("[data-menu-search]");
        const filterButtons = Array.from(app.querySelectorAll("[data-filter]"));
        const cards = Array.from(app.querySelectorAll("[data-product-card]"));
        let category = "all";
        function filterMenu() {
            const query = search ? search.value.trim().toLowerCase() : ""; let visible = 0;
            cards.forEach(function (card) { const match = (category === "all" || card.dataset.category === category) && (!query || card.dataset.name.includes(query)); card.hidden = !match; if (match) visible += 1; });
            const empty = app.querySelector("[data-no-results]"); if (empty) empty.hidden = visible !== 0 || !cards.length;
        }
        filterButtons.forEach(function (button) { button.addEventListener("click", function () { category = button.dataset.filter; filterButtons.forEach(function (item) { item.classList.toggle("is-active", item === button); }); filterMenu(); }); });
        if (search) search.addEventListener("input", filterMenu);

        const params = new URLSearchParams(window.location.search);
        if (params.get("checkout") === "1" || params.has("error")) openCart("details");

        if ("serviceWorker" in navigator) {
            navigator.serviceWorker.register("/food/service-worker.js", { scope: "/food" }).catch(function () {});
        }
        let deferredInstallPrompt = null;
        const installButtons = Array.from(app.querySelectorAll("[data-install-app]"));
        window.addEventListener("beforeinstallprompt", function (event) {
            event.preventDefault();
            deferredInstallPrompt = event;
            installButtons.forEach(function (button) { button.hidden = false; });
        });
        installButtons.forEach(function (button) {
            button.addEventListener("click", function () {
                if (!deferredInstallPrompt) return;
                deferredInstallPrompt.prompt();
                deferredInstallPrompt = null;
                installButtons.forEach(function (item) { item.hidden = true; });
            });
        });
        window.addEventListener("appinstalled", function () {
            installButtons.forEach(function (button) { button.hidden = true; });
        });
    });

    // ------------------------------------------------------------------
    // Order tracking page: secure-token polling with adaptive backoff.
    // Independent of the catalog app above (different page/root element).
    // ------------------------------------------------------------------
    onReady(function () {
        const root = document.querySelector("[data-order-tracker]");
        if (!root) return;
        const offlineState = setupOfflineBanner(root);
        const endpoint = root.dataset.pollEndpoint;
        const STATUS_LABELS = { pending: "Pending Cashier Acceptance", accepted: "Accepted", preparing: "Preparing", ready: "Ready for Collection", completed: "Completed", cancelled: "Cancelled" };
        const STATUS_INDEX = { pending: 0, accepted: 1, preparing: 2, ready: 3, completed: 4 };
        const TERMINAL = ["completed", "cancelled"];
        let currentStatus = root.dataset.orderStatus;
        let pollDelay = 5000;
        let timer = null;

        function renderStatus(status) {
            const label = root.querySelector("[data-status-label]");
            if (label) label.textContent = STATUS_LABELS[status] || status;
            const timeline = root.querySelector("[data-timeline]");
            if (timeline) {
                timeline.hidden = status === "cancelled";
                const index = STATUS_INDEX[status] || 0;
                timeline.querySelectorAll("[data-step]").forEach(function (stepEl) {
                    stepEl.classList.toggle("is-complete", index >= Number(stepEl.dataset.step));
                });
            }
            const pulse = root.querySelector("[data-status-pulse]");
            if (pulse) pulse.classList.toggle("is-idle", TERMINAL.includes(status));
            const lastUpdated = root.querySelector("[data-last-updated]");
            if (lastUpdated) lastUpdated.textContent = new Date().toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
        }
        renderStatus(currentStatus);

        async function poll() {
            if (!endpoint || TERMINAL.includes(currentStatus) || !offlineState.isOnline()) {
                scheduleNext();
                return;
            }
            try {
                const response = await fetch(endpoint, { credentials: "same-origin" });
                if (response.ok) {
                    const body = await response.json();
                    const newStatus = body.order && body.order.status;
                    if (newStatus && newStatus !== currentStatus) { currentStatus = newStatus; }
                    renderStatus(currentStatus);
                    pollDelay = TERMINAL.includes(currentStatus) ? null : Math.min(pollDelay * 1.15, 20000);
                }
            } catch (e) { /* stay on last known state; offline banner already reflects connectivity */ }
            scheduleNext();
        }
        function scheduleNext() {
            clearTimeout(timer);
            if (pollDelay === null || TERMINAL.includes(currentStatus)) return; // stop polling after a terminal state
            timer = setTimeout(poll, pollDelay);
        }
        const refreshButton = root.querySelector("[data-refresh-status]");
        if (refreshButton) refreshButton.addEventListener("click", function () { pollDelay = 5000; poll(); });
        window.addEventListener("online", function () { pollDelay = 5000; poll(); });
        scheduleNext();
    });
})();
