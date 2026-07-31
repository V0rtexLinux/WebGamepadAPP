package com.gamemapper.services

/**
 * Handles login detection and credential injection for Club Penguin Private Servers (CPPSs).
 *
 * Research findings (July 2026):
 * ─────────────────────────────
 * • CPJourney (play.cpjourney.net):
 *     Uses Phaser 4 + Ruffle (WebAssembly Flash emulator).
 *     The game SWF runs inside Ruffle's custom element <ruffle-player> which wraps its
 *     canvas in an OPEN shadow DOM.  Standard document.elementFromPoint() finds the
 *     <ruffle-player> host but NOT the canvas inside the shadow root, so plain MouseEvent
 *     dispatch never reaches the game.  Fix: pierce shadow DOM to dispatch events to the
 *     shadow-root canvas directly.
 *
 * • CP Legacy (play.cplegacy.com):
 *     Uses Phaser 3 + Ruffle (same architecture as CPJourney).
 *     Same shadow-DOM issue; same fix applies.
 *
 * • CPPS.app (cpps.app / play.cpps.app):
 *     Separate HTML login page (/auth/login) with real <input> fields.
 *     HTML-form injection works fine here.
 *
 * • Flash/Legacy (cprewritten, etc.):
 *     Flash SWF embedded; login inside the SWF via TCP/XML. No HTML interaction.
 *
 * Movement / cursor:
 *     CP uses click-to-move (click canvas to walk the penguin). The virtual cursor
 *     overlay + gmClick() handles this.  gmClick() now dispatches to the Ruffle
 *     shadow-DOM canvas when present.
 */
object CppsLoginHandler {

    /** Known CPPS domains and their login characteristics. */
    data class CppsInfo(
        val domain: String,
        val loginType: LoginType,
        val loginPath: String,          // URL path that shows the login page
        val gamePath: String,           // URL path that means "logged in / in-game"
        val displayName: String,
        val usernameSelector: String = "",
        val passwordSelector: String = "",
        val submitSelector: String = ""
    )

    enum class LoginType {
        /** Login rendered on HTML5 Canvas (Phaser/Ruffle). No HTML <input> fields. */
        CANVAS_BASED,
        /** Login is a real HTML form with <input> fields we can inject into. */
        HTML_FORM,
        /** Legacy Flash SWF. Login is inside the SWF. We can't interact with it. */
        FLASH_SWF,
        /** Unknown / generic web game. */
        GENERIC
    }

    val KNOWN_SERVERS = listOf(
        CppsInfo(
            domain = "play.cpjourney.net",
            loginType = LoginType.CANVAS_BASED,
            loginPath = "/",
            gamePath = "/",
            displayName = "Club Penguin Journey"
        ),
        CppsInfo(
            domain = "cpjourney.net",
            loginType = LoginType.CANVAS_BASED,
            loginPath = "/",
            gamePath = "/",
            displayName = "Club Penguin Journey"
        ),
        CppsInfo(
            domain = "play.cplegacy.com",
            loginType = LoginType.CANVAS_BASED,
            loginPath = "/",
            gamePath = "/",
            displayName = "Club Penguin Legacy"
        ),
        CppsInfo(
            domain = "cplegacy.com",
            loginType = LoginType.CANVAS_BASED,
            loginPath = "/",
            gamePath = "/",
            displayName = "Club Penguin Legacy"
        ),
        CppsInfo(
            domain = "cpps.app",
            loginType = LoginType.HTML_FORM,
            loginPath = "/auth/login",
            gamePath = "/play",
            displayName = "CPPS.app",
            usernameSelector = "input[name='username'], input[placeholder*='PENGUIN'], " +
                "input[placeholder*='penguin'], input[name*='penguin'], " +
                "input[id*='username'], input[id*='user']",
            passwordSelector = "input[type='password']",
            submitSelector = "button[type='submit'], input[type='submit'], " +
                ".login-btn, #loginButton"
        ),
        CppsInfo(
            domain = "play.cpps.app",
            loginType = LoginType.HTML_FORM,
            loginPath = "/penguin/login",
            gamePath = "/play",
            displayName = "CPPS.app",
            usernameSelector = "input[name='username'], #username, input[placeholder*='name']",
            passwordSelector = "input[type='password']",
            submitSelector = "input[type='submit'], button[type='submit']"
        ),
        CppsInfo(
            domain = "icer.ink",
            loginType = LoginType.CANVAS_BASED,
            loginPath = "/",
            gamePath = "/",
            displayName = "Icer Ink"
        ),
        CppsInfo(
            domain = "cprewritten.net",
            loginType = LoginType.HTML_FORM,
            loginPath = "/play",
            gamePath = "/play",
            displayName = "Club Penguin Rewritten",
            usernameSelector = "#name, input[name='username'], input[placeholder*='username']",
            passwordSelector = "input[type='password']",
            submitSelector = "#login-button, input[type='submit'], button[type='submit']"
        ),
    )

    // ── Detection ──────────────────────────────────────────────────────────────

    /** Detect if a URL belongs to a known CPPS and return its info. */
    fun detect(url: String): CppsInfo? {
        val lowerUrl = url.lowercase()
        return KNOWN_SERVERS.firstOrNull { lowerUrl.contains(it.domain) }
    }

    /** Generic CPPS detection — matches any common CP-related domain. */
    fun isCpps(url: String): Boolean {
        val lower = url.lowercase()
        return KNOWN_SERVERS.any { lower.contains(it.domain) } ||
            lower.contains("clubpenguin") || lower.contains("cpjourney") ||
            lower.contains("cprewritten") || lower.contains("cplegacy") ||
            lower.contains("cpps") || lower.contains("icer.ink") ||
            (lower.contains("penguin") && (lower.contains("play") || lower.contains("game")))
    }

    // ── JS: Login state detection ──────────────────────────────────────────────

    /**
     * JavaScript to inject after page load.
     * Detects the current login state of the page and returns a JSON summary.
     *
     * Returns: { hasHtmlForm, hasCanvas, hasRuffle, isLoggedIn,
     *            loginType: "html_form"|"canvas"|"ruffle"|"flash"|"unknown",
     *            usernameSel, passwordSel, submitSel }
     */
    val DETECT_LOGIN_STATE_JS = """
(function() {
    var result = {
        hasHtmlForm: false,
        hasCanvas:   false,
        hasRuffle:   false,
        isLoggedIn:  false,
        loginType:   'unknown',
        usernameSel: '',
        passwordSel: '',
        submitSel:   '',
        pageTitle:   document.title || location.hostname
    };

    /* ── 1. Check for HTML login form ─────────────────────────────── */
    var pwField   = document.querySelector("input[type='password']");
    var userField = document.querySelector(
        "input[type='text'][name*='user'], input[type='text'][name*='penguin'], " +
        "input[name*='username'], input[placeholder*='PENGUIN'], " +
        "input[placeholder*='name'], #username, #user, #penguinName, " +
        "input[type='text']:not([type='hidden'])"
    );

    if (pwField) {
        result.hasHtmlForm = true;
        result.loginType   = 'html_form';
        if (userField) {
            result.usernameSel = userField.id  ? '#' + userField.id :
                                 userField.name ? 'input[name="' + userField.name + '"]' :
                                                  'input[type="text"]';
        }
        result.passwordSel = pwField.id ? '#' + pwField.id : 'input[type="password"]';
        var submitBtn = document.querySelector(
            "input[type='submit'], button[type='submit'], " +
            ".login-btn, #loginButton, #login-button, button.btn-primary"
        ) || document.querySelector('button, input[type="button"]');
        if (submitBtn) {
            result.submitSel = submitBtn.id ? '#' + submitBtn.id :
                               submitBtn.className
                                   ? '.' + submitBtn.className.trim().split(/\s+/)[0]
                                   : 'button';
        }
    }

    /* ── 2. Check for Ruffle player (Flash-via-WebAssembly) ─────────── */
    var rufflePlayer = document.querySelector('ruffle-player');
    if (rufflePlayer) {
        result.hasRuffle  = true;
        result.hasCanvas  = true;
        if (!result.hasHtmlForm) result.loginType = 'ruffle';
    }

    /* ── 3. Check for plain HTML5 canvas ────────────────────────────── */
    if (!result.hasRuffle) {
        var canvases = document.querySelectorAll('canvas');
        canvases.forEach(function(c) {
            var r = c.getBoundingClientRect();
            if (r.width > 200 && r.height > 200) result.hasCanvas = true;
        });
        if (result.hasCanvas && !result.hasHtmlForm) result.loginType = 'canvas';
    }

    /* ── 4. Check for native Flash plugin (legacy) ───────────────────── */
    var flashEl = document.querySelector(
        'object[type*="flash"], embed[type*="flash"], object[data*=".swf"], embed[src*=".swf"]'
    );
    if (flashEl && !result.hasRuffle) result.loginType = 'flash';

    /* ── 5. Logged-in indicators ─────────────────────────────────────── */
    var logoutLinks = document.querySelectorAll(
        'a[href*="logout"], a[href*="log-out"], a[href*="signout"], ' +
        '.logout, #logout, [class*="logout"], [id*="logout"]'
    );
    if (logoutLinks.length > 0) result.isLoggedIn = true;
    if (result.hasCanvas && !result.hasHtmlForm) result.isLoggedIn = true;

    return JSON.stringify(result);
})();
""".trimIndent()

    // ── JS: Credential injection ───────────────────────────────────────────────

    /**
     * Build a JS script that injects credentials into an HTML form and submits it.
     * Only for LoginType.HTML_FORM pages.
     */
    fun buildInjectCredentialsJS(
        username: String, password: String,
        userSel: String, passSel: String, submitSel: String
    ): String {
        val escapedUser = username.replace("\\", "\\\\").replace("'", "\\'")
        val escapedPass = password.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function() {
    function setValue(sel, val) {
        var el = document.querySelector(sel);
        if (!el) return false;
        /* React/Vue/Angular-aware value setter */
        var proto = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
        if (proto) proto.set.call(el, val);
        else el.value = val;
        el.dispatchEvent(new Event('input',  { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return true;
    }

    var userOk = setValue('$userSel', '$escapedUser');
    var passOk = setValue('$passSel', '$escapedPass');

    if (!userOk) {
        userOk = setValue("input[type='text']", '$escapedUser') ||
                 setValue("input:not([type='password']):not([type='hidden'])", '$escapedUser');
    }

    setTimeout(function() {
        var btn = document.querySelector('$submitSel');
        if (btn) { btn.click(); }
        else { var f = document.querySelector('form'); if (f) f.submit(); }
    }, 350);

    return JSON.stringify({ injected: true, userOk: userOk, passOk: passOk });
})();
""".trimIndent()
    }

    // ── JS: Virtual cursor + Ruffle-aware click injection ─────────────────────

    /**
     * JavaScript for the virtual gamepad cursor — injected when the game is running.
     *
     * Key fixes vs. previous version:
     *  • gmClick()  now pierces Ruffle's shadow DOM:
     *      document.querySelector('ruffle-player').shadowRoot.querySelector('canvas')
     *    Events dispatched there are received by the Ruffle engine and forwarded
     *    to the running SWF as mouse input.
     *  • gmKey() also dispatches to the Ruffle shadow-root canvas.
     *  • Both functions dispatch pointerdown/pointerup in addition to mousedown/mouseup
     *    because Ruffle prefers Pointer Events.
     */
    val VIRTUAL_CURSOR_JS = """
(function() {
    if (window.__gm_cursor) return;
    window.__gm_cursor = { x: window.innerWidth / 2, y: window.innerHeight / 2 };

    /* ── Cursor overlay element ──────────────────────────────────── */
    var cur = document.createElement('div');
    cur.id = '__gm_cursor_el';
    cur.style.cssText = [
        'position:fixed',
        'width:22px',
        'height:22px',
        'border-radius:50%',
        'background:rgba(233,69,96,0.85)',
        'border:2px solid white',
        'box-shadow:0 0 6px rgba(0,0,0,0.7)',
        'pointer-events:none',
        'z-index:2147483647',
        'transform:translate(-50%,-50%)',
        'transition:left 0.05s,top 0.05s',
        'left:' + window.__gm_cursor.x + 'px',
        'top:'  + window.__gm_cursor.y + 'px'
    ].join(';');
    document.body.appendChild(cur);

    /* ── Helper: find the best click target ─────────────────────── */
    function findClickTarget(x, y) {
        /* 1. Ruffle shadow DOM canvas (highest priority for CP games). */
        var ruffle = document.querySelector('ruffle-player');
        if (ruffle && ruffle.shadowRoot) {
            var sc = ruffle.shadowRoot.querySelector('canvas');
            if (sc) return sc;
            return ruffle;   /* dispatch to host if no canvas found inside */
        }
        /* 2. Regular DOM element at pointer coordinates. */
        var el = document.elementFromPoint(x, y);
        if (el && el !== document.body) return el;
        /* 3. Any canvas. */
        var canvas = document.querySelector('canvas');
        if (canvas) return canvas;
        return document.body;
    }

    /* ── Helper: build a mouse/pointer event ────────────────────── */
    function makeEvt(type, x, y) {
        var isDown = type === 'pointerdown' || type === 'mousedown';
        return new MouseEvent(type, {
            bubbles: true, cancelable: true,
            clientX: x, clientY: y,
            screenX: x, screenY: y,
            button: 0, buttons: isDown ? 1 : 0,
            view: window
        });
    }

    /* ── gmMoveCursor: move the visible cursor dot ───────────────── */
    window.gmMoveCursor = function(dx, dy) {
        var c = window.__gm_cursor;
        c.x = Math.max(0, Math.min(window.innerWidth,  c.x + dx));
        c.y = Math.max(0, Math.min(window.innerHeight, c.y + dy));
        var el = document.getElementById('__gm_cursor_el');
        if (el) { el.style.left = c.x + 'px'; el.style.top = c.y + 'px'; }
    };

    /* ── gmClick: fire a click at the current cursor position ────── */
    window.gmClick = function() {
        var c   = window.__gm_cursor;
        var dot = document.getElementById('__gm_cursor_el');
        if (dot) {
            dot.style.background = 'rgba(255,255,255,0.9)';
            setTimeout(function() { dot.style.background = 'rgba(233,69,96,0.85)'; }, 120);
        }

        var target = findClickTarget(c.x, c.y);

        /* Dispatch Pointer Events first (Ruffle listens to these). */
        try { target.dispatchEvent(makeEvt('pointerdown', c.x, c.y)); } catch(e) {}
        try { target.dispatchEvent(makeEvt('mousedown',   c.x, c.y)); } catch(e) {}
        setTimeout(function() {
            try { target.dispatchEvent(makeEvt('pointerup', c.x, c.y)); } catch(e) {}
            try { target.dispatchEvent(makeEvt('mouseup',   c.x, c.y)); } catch(e) {}
            try { target.dispatchEvent(makeEvt('click',     c.x, c.y)); } catch(e) {}
        }, 40);
    };

    /* ── gmToggleCursor: show/hide the cursor dot ────────────────── */
    window.gmToggleCursor = function(show) {
        var el = document.getElementById('__gm_cursor_el');
        if (el) el.style.display = show ? 'block' : 'none';
    };

    /* ── gmKey: fire a keyboard event ─────────────────────────────── */
    window.gmKey = function(keyCode, type) {
        var evtType = type || 'keydown';
        function makeKey() {
            return new KeyboardEvent(evtType, {
                keyCode: keyCode, which: keyCode,
                bubbles: true, cancelable: true,
                view: window
            });
        }
        /* Dispatch to document (catches global listeners). */
        try { document.dispatchEvent(makeKey()); } catch(e) {}

        /* Dispatch to focused element (e.g. chat input). */
        var focused = document.activeElement;
        if (focused && focused !== document.body) {
            try { focused.dispatchEvent(makeKey()); } catch(e) {}
        }

        /* Dispatch to Ruffle shadow-root canvas. */
        var ruffle = document.querySelector('ruffle-player');
        if (ruffle && ruffle.shadowRoot) {
            var sc = ruffle.shadowRoot.querySelector('canvas');
            if (sc) { try { sc.dispatchEvent(makeKey()); } catch(e) {} }
        }

        /* Dispatch to any visible canvas (Phaser / plain HTML5 game). */
        document.querySelectorAll('canvas').forEach(function(c) {
            if (c !== focused) { try { c.dispatchEvent(makeKey()); } catch(e) {} }
        });
    };

    console.log('[GameMapper] Virtual cursor injected (Ruffle-aware)');
    return 'ok';
})();
""".trimIndent()
}
