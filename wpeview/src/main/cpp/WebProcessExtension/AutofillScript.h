/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#pragma once

namespace Autofill {

// The script that runs in the autofill script world of each main frame, apart from the page's own scripts. It finds
// the login fields, a visible password field and its user name field, with or without a form element, and tells
// WPEView about them through wpeviewPost(kind, json): "fields" when the set of fields changes, "focus" and "blur",
// "input" for each value the user types, "rects" after scrolling and zooming, and "submit" when the extension sees a
// form being sent. Positions are in pixels of the view.
//
// Every message carries a token of the document and its origin. Until WPEView gives the document a key, through
// wpeviewSetKey() in this world, which only the web process of the page gets, the document only tells of its fields;
// once it has the key, every message carries it, so that WPEView knows that they come from the web process of the
// page. WPEView fills fields by calling wpeviewFill() in this world, with the same token and key.
//
// The script speaks once the document is parsed: a message sent earlier could reach WPEView before it hears that the
// page moved to this document's web process, and be dropped.
constexpr const char* SCRIPT = R"JS(
(() => {
    if (location.protocol !== "http:" && location.protocol !== "https:")
        return;
    const post = wpeviewPost;
    const autoFill = wpeviewAutoFill;
    const newToken = () => Array.from(crypto.getRandomValues(new Uint32Array(4)), n => n.toString(16)).join("");
    let doc = newToken();
    let key = "";
    const send = (kind, message) => {
        if (key || kind === "fields")
            post(kind, JSON.stringify(Object.assign({ doc, key, origin: location.origin }, message)));
    };
    const whenParsed = callback => {
        if (document.readyState === "loading")
            document.addEventListener("DOMContentLoaded", callback, { once: true });
        else
            callback();
    };
    let started = false;

    let nextId = 1;
    const ids = new WeakMap();
    const elements = new Map();
    let tracked = new Set();
    const idOf = element => {
        let id = ids.get(element);
        if (!id) {
            id = nextId++;
            ids.set(element, id);
            elements.set(id, new WeakRef(element));
        }
        return id;
    };
    const elementOf = id => {
        const ref = elements.get(id);
        const element = ref ? ref.deref() : undefined;
        return element && element.isConnected ? element : null;
    };
    const forgetGoneElements = () => {
        for (const [id, ref] of elements) {
            if (!ref.deref())
                elements.delete(id);
        }
    };

    const visible = element => {
        if (element.disabled || element.readOnly)
            return false;
        const rect = element.getBoundingClientRect();
        if (rect.width < 1 || rect.height < 1)
            return false;
        const style = getComputedStyle(element);
        return style.visibility !== "hidden" && style.display !== "none";
    };
    // getBoundingClientRect() is relative to the layout viewport; the view shows the visual viewport, which can be
    // moved and zoomed within it.
    const rectOf = element => {
        const rect = element.getBoundingClientRect();
        const viewport = window.visualViewport;
        const left = viewport ? viewport.offsetLeft : 0;
        const top = viewport ? viewport.offsetTop : 0;
        const scale = (viewport ? viewport.scale : 1) * window.devicePixelRatio;
        return { x: Math.round((rect.left - left) * scale), y: Math.round((rect.top - top) * scale),
                 width: Math.round(rect.width * scale), height: Math.round(rect.height * scale) };
    };

    const textTypes = ["text", "email", "tel", ""];
    const userNameFor = (password, scope) => {
        const inputs = Array.from(scope.querySelectorAll("input"));
        const marked = inputs.find(input => input !== password && /\b(username|email)\b/.test(input.autocomplete || "")
                                            && visible(input));
        if (marked)
            return marked;
        let candidate = null;
        for (const input of inputs) {
            if (input === password)
                break;
            if (textTypes.includes((input.getAttribute("type") || "").toLowerCase()) && visible(input))
                candidate = input;
        }
        return candidate;
    };
    const describe = (element, role) => ({
        id: idOf(element), role, type: (element.getAttribute("type") || "").toLowerCase(), name: element.name || "",
        idAttribute: element.id || "", autocomplete: element.autocomplete || "", value: element.value || "",
        rect: rectOf(element)
    });

    let lastSignature = "";
    const scan = () => {
        const fields = [];
        const seen = new Set();
        const passwords = Array.from(document.querySelectorAll("input[type=password]")).filter(visible);
        for (const password of passwords) {
            const scope = password.form || document;
            const inScope = passwords.filter(other => (other.form || document) === scope);
            // A form with several password fields changes the password, except for the one marked as the current one.
            const autocomplete = password.autocomplete || "";
            const isNew = /\bnew-password\b/.test(autocomplete)
                          || (inScope.length > 1 && !/\bcurrent-password\b/.test(autocomplete));
            const userName = userNameFor(password, scope);
            if (userName && !seen.has(userName)) {
                seen.add(userName);
                fields.push(describe(userName, "username"));
            }
            if (!seen.has(password)) {
                seen.add(password);
                fields.push(describe(password, isNew ? "new-password" : "password"));
            }
        }
        tracked = new Set(fields.map(field => field.id));
        forgetGoneElements();
        const signature = JSON.stringify(fields.map(field => [field.id, field.role]));
        if (signature !== lastSignature) {
            lastSignature = signature;
            send("fields", { fields });
        }
    };

    // Android showing its list of accounts takes the focus from the window, which blurs the field too, though it stays
    // the focused element of the page. That is not leaving the field.
    let focusedId = 0;
    const enter = element => {
        const id = ids.get(element);
        if (!id || !tracked.has(id) || id === focusedId)
            return;
        focusedId = id;
        send("focus", { id, rect: rectOf(element) });
    };
    document.addEventListener("focusin", event => {
        if (!started || !(event.target instanceof HTMLInputElement))
            return;
        // WPEView may have dropped the first message, as when it came while the page waited for a load to be decided
        // on, so a document with no key yet asks again.
        if (!key)
            lastSignature = "";
        scan();
        enter(event.target);
    }, true);
    document.addEventListener("focusout", event => {
        if (!started)
            return;
        const target = event.target;
        const id = ids.get(target);
        if (!id || id !== focusedId)
            return;
        setTimeout(() => {
            if (document.activeElement === target || focusedId !== id)
                return;
            focusedId = 0;
            send("blur", { id });
        }, 0);
    }, true);
    document.addEventListener("input", event => {
        if (!started)
            return;
        const id = ids.get(event.target);
        if (id && tracked.has(id))
            send("input", { id, value: event.target.value || "" });
    }, true);

    let rectsTimer = 0;
    const sendRects = () => {
        rectsTimer = 0;
        const fields = [];
        for (const id of tracked) {
            const element = elementOf(id);
            if (element)
                fields.push({ id, rect: rectOf(element) });
        }
        if (fields.length)
            send("rects", { fields });
    };
    const queueRects = () => {
        if (started && !rectsTimer)
            rectsTimer = setTimeout(sendRects, 100);
    };
    window.addEventListener("scroll", queueRects, true);
    window.addEventListener("resize", queueRects);
    if (window.visualViewport) {
        window.visualViewport.addEventListener("scroll", queueRects);
        window.visualViewport.addEventListener("resize", queueRects);
    }

    let scanTimer = 0;
    const queueScan = () => {
        if (!scanTimer)
            scanTimer = setTimeout(() => { scanTimer = 0; scan(); }, 200);
    };
    whenParsed(() => {
        started = true;
        new MutationObserver(queueScan).observe(document, {
            subtree: true, childList: true, attributes: true,
            attributeFilter: ["type", "style", "class", "hidden", "disabled", "readonly"] });
        scan();
    });

    // Tells of the fields and the focused one again, as WPEView took them as those of a new document.
    const tellAgain = () => {
        lastSignature = "";
        focusedId = 0;
        scan();
        // A field that the page focused on its own, as with autofocus, or before the key came.
        if (document.activeElement instanceof HTMLInputElement)
            enter(document.activeElement);
    };
    window.wpeviewSetKey = (token, newKey) => {
        if (token !== doc || key)
            return false;
        key = newKey;
        if (started)
            tellAgain();
        return true;
    };
    // A page shown again from the back and forward cache is a new document for WPEView, which has replaced its fields.
    window.addEventListener("pageshow", event => {
        if (!event.persisted)
            return;
        doc = newToken();
        key = "";
        tellAgain();
    });

    // Sending a form gives both the submit event and the form being sent, so the second one is ignored.
    const submitted = new WeakSet();
    window.wpeviewSubmit = form => {
        if (!started || submitted.has(form))
            return;
        submitted.add(form);
        setTimeout(() => submitted.delete(form), 0);
        scan();
        const fields = [];
        for (const id of tracked) {
            const element = elementOf(id);
            if (element && element.form === form)
                fields.push({ id, value: element.value || "" });
        }
        if (fields.length)
            send("submit", { fields });
    };

    // The values come by field id. The token, key and origin tell that the document is still the one whose fields
    // WPEView gives them for.
    window.wpeviewFill = (token, fillKey, origin, values) => {
        if (!key || token !== doc || fillKey !== key || origin !== location.origin)
            return 0;
        let filled = 0;
        for (const [fieldId, value] of Object.entries(values)) {
            const id = Number(fieldId);
            const element = elementOf(id);
            if (element && tracked.has(id) && typeof value === "string") {
                autoFill(element, value);
                filled++;
            }
        }
        return filled;
    };
})();
)JS";

} // namespace Autofill
