package com.gamemapper.services

/**
 * Handles login detection and credential injection for Club Penguin Private Servers (CPPSs).
 *
 * Research findings:
 * - CPJourney / Yukon-type (HTML5 Phaser): login is rendered ON the game canvas — no HTML <input>
 *   elements. User logs in by clicking/typing on the canvas. We detect post-login state via URL
 *   patterns and canvas presence.
 * - CPPS.app-type: separate /auth/login page with real HTML <input> fields ("PENGUIN NAME" +
 *   "PASSWORD"). We can detect and inject credentials here.
 * - Flash/Legacy (Houdini, Wand): Flash SWF embedded in HTML page, login inside Flash via TCP/XML.
 *   No HTML interaction possible; user logs in inside the SWF normally.
 * - Movement: CP uses click-to-move (click canvas to walk). The GameplayActivity handles
 *   gamepad → virtual cursor → click injection.
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
        /** Login rendered on HTML5 Canvas (Phaser/Pixi). No HTML <input> fields. */
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
            displayName = "Club Penguin Journey",
            usernameSelector = "",
            passwordSelector = ""
        ),
        CppsInfo(
            domain = "cpjourney.net",
            loginType = LoginType.CANVAS_BASED,
            loginPath = "/",
            gamePath = "/",
            displayName = "Club Penguin Journey"
        ),
        CppsInfo(
            domain = "cpps.app",
            loginType = LoginType.HTML_FORM,
            loginPath = "/auth/login",
            gamePath = "/play",
            displayName = "CPPS.app",
            usernameSelector = "input[name='username'], input[placeholder*='PENGUIN'], input[placeholder*='penguin'], input[name*='penguin'], input[id*='username'], input[id*='user']",
            passwordSelector = "input[type='password']",
            submitSelector = "button[type='submit'], input[type='submit'], .login-btn, button:contains('LOGIN'), #loginButton"
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
        CppsInfo(
            domain = "cplegacy.com",
            loginType = LoginType.CANVAS_BASED,
            loginPath = "/",
            gamePath = "/",
            displayName = "Club Penguin Legacy"
        ),
    )

    /** Detect if a URL belongs to a known CPPS and return its info. */
    fun detect(url: String): CppsInfo? {
        val lowerUrl = url.lowercase()
        return KNOWN_SERVERS.firstOrNull { lowerUrl.contains(it.domain) }
    }

    /** Generic CPPS detection — matches any common CP-related domain. */
    fun isCpps(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("clubpenguin") || lower.contains("cpjourney") ||
               lower.contains("cprewritten") || lower.contains("cplegacy") ||
               lower.contains("cpps") || lower.contains("icer.ink") ||
               lower.contains("penguin") && (lower.contains("play") || lower.contains("game"))
    }

    /**
     * JavaScript to inject after page load.
     * Detects the current login state of the page and returns a JSON summary.
     *
     * Returns: { hasHtmlForm, username_sel, password_sel, submit_sel, hasCanvas, isLoggedIn,
     *            loginType: "html_form"|"canvas"|"flash"|"unknown" }
     */
    val DETECT_LOGIN_STATE_JS = """
(function() {
    var result = {
        hasHtmlForm: false,
        hasCanvas: false,
        isLoggedIn: false,
        loginType: 'unknown',
        usernameSel: '',
        passwordSel: '',
        submitSel: '',
        pageTitle: document.title || location.hostname
    };

    /* ── 1. Check for HTML login form ──────────────────────────── */
    var pwField = document.querySelector("input[type='password']");
    var userField = document.querySelector(
        "input[type='text'][name*='user'], input[type='text'][name*='penguin'], " +
        "input[name*='username'], input[placeholder*='PENGUIN'], " +
        "input[placeholder*='name'], #username, #user, #penguinName, " +
        "input[type='text']:not([type='hidden'])"
    );

    if (pwField) {
        result.hasHtmlForm = true;
        result.loginType = 'html_form';

        /* Find best username field */
        if (userField) {
            var uid = userField.id ? '#' + userField.id :
                      (userField.name ? 'input[name="' + userField.name + '"]' : 'input[type="text"]');
            result.usernameSel = uid;
        }
        result.passwordSel = pwField.id ? '#' + pwField.id : 'input[type="password"]';

        /* Find submit button */
        var submitBtn = document.querySelector(
            "input[type='submit'], button[type='submit'], " +
            ".login-btn, #loginButton, #login-button, button.btn-primary"
        );
        if (!submitBtn) {
            /* Fall back to any button near the password field */
            submitBtn = document.querySelector('button, input[type="button"]');
        }
        if (submitBtn) {
            result.submitSel = submitBtn.id ? '#' + submitBtn.id :
                               (submitBtn.className ? '.' + submitBtn.className.trim().split(/\s+/)[0] : 'button');
        }
    }

    /* ── 2. Check for canvas (HTML5 game) ────────────────────────── */
    var canvases = document.querySelectorAll('canvas');
    var bigCanvas = null;
    canvases.forEach(function(c) {
        var r = c.getBoundingClientRect();
        if (r.width > 200 && r.height > 200) bigCanvas = c;
    });
    if (bigCanvas) {
        result.hasCanvas = true;
        if (!result.hasHtmlForm) result.loginType = 'canvas';
    }

    /* ── 3. Check for Flash (object/embed with SWF) ──────────────── */
    var flashEl = document.querySelector(
        'object[type*="flash"], embed[type*="flash"], object[data*=".swf"], embed[src*=".swf"]'
    );
    if (flashEl) {
        result.loginType = 'flash';
    }

    /* ── 4. Try to detect if already logged in ────────────────────── */
    /* Common indicators: logout link, avatar, username display */
    var logoutIndicators = document.querySelectorAll(
        'a[href*="logout"], a[href*="log-out"], a[href*="signout"], ' +
        '.logout, #logout, [class*="logout"], [id*="logout"]'
    );
    if (logoutIndicators.length > 0) result.isLoggedIn = true;

    /* Canvas-based: if canvas is big and no login form visible, probably in-game */
    if (result.hasCanvas && !result.hasHtmlForm) {
        result.isLoggedIn = true; /* May still be on login screen drawn on canvas */
    }

    return JSON.stringify(result);
})();
""".trimIndent()

    /**
     * Build a JS script that injects credentials into an HTML form and submits it.
     * Only for LoginType.HTML_FORM pages.
     */
    fun buildInjectCredentialsJS(username: String, password: String,
                                  userSel: String, passSel: String, submitSel: String): String {
        val escapedUser = username.replace("'", "\\'")
        val escapedPass = password.replace("'", "\\'")
        return """
(function() {
    function setValue(sel, val) {
        var el = document.querySelector(sel);
        if (!el) return false;
        /* React/Vue/Angular-aware value injection */
        var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
        if (nativeInputValueSetter) {
            nativeInputValueSetter.set.call(el, val);
        } else {
            el.value = val;
        }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return true;
    }

    var userSel = '$userSel';
    var passSel = '$passSel';
    var submitSel = '$submitSel';

    var userOk = setValue(userSel, '$escapedUser');
    var passOk = setValue(passSel, '$escapedPass');

    if (!userOk || !passOk) {
        /* Try broader fallback selectors */
        if (!userOk) {
            setValue("input[type='text']", '$escapedUser') ||
            setValue("input:not([type='password']):not([type='hidden'])", '$escapedUser');
        }
    }

    /* Submit after short delay so React state settles */
    setTimeout(function() {
        var btn = document.querySelector(submitSel);
        if (btn) {
            btn.click();
        } else {
            /* Try form.submit() */
            var form = document.querySelector('form');
            if (form) form.submit();
        }
    }, 300);

    return JSON.stringify({ injected: true, userOk: userOk, passOk: passOk });
})();
""".trimIndent()
    }

    /**
     * JavaScript for the virtual gamepad cursor — injected when the game is running.
     * Draws a semi-transparent red dot on top of the game canvas that moves with D-pad input.
     * Android calls moveCursor(dx, dy) and triggerClick() via evaluateJavascript.
     */
    val VIRTUAL_CURSOR_JS = """
(function() {
    if (window.__gm_cursor) return;
    window.__gm_cursor = { x: window.innerWidth / 2, y: window.innerHeight / 2, visible: true };

    /* Create cursor element */
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
        'top:' + window.__gm_cursor.y + 'px'
    ].join(';');
    document.body.appendChild(cur);

    /** Move cursor by dx/dy pixels (called from Android via evaluateJavascript). */
    window.gmMoveCursor = function(dx, dy) {
        var c = window.__gm_cursor;
        c.x = Math.max(0, Math.min(window.innerWidth,  c.x + dx));
        c.y = Math.max(0, Math.min(window.innerHeight, c.y + dy));
        var el = document.getElementById('__gm_cursor_el');
        if (el) { el.style.left = c.x + 'px'; el.style.top = c.y + 'px'; }
    };

    /** Fire a click at the current cursor position (for click-to-move CP games). */
    window.gmClick = function() {
        var c = window.__gm_cursor;
        var el = document.getElementById('__gm_cursor_el');
        if (el) {
            el.style.background = 'rgba(255,255,255,0.9)';
            setTimeout(function(){ el.style.background = 'rgba(233,69,96,0.85)'; }, 120);
        }
        /* Dispatch mouse events at cursor position */
        ['mousedown','mouseup','click'].forEach(function(type) {
            var evt = new MouseEvent(type, {
                bubbles: true, cancelable: true,
                clientX: c.x, clientY: c.y,
                screenX: c.x, screenY: c.y,
                button: 0, buttons: type === 'mousedown' ? 1 : 0
            });
            var target = document.elementFromPoint(c.x, c.y) || document.body;
            target.dispatchEvent(evt);
        });
    };

    /** Toggle cursor visibility. */
    window.gmToggleCursor = function(show) {
        var el = document.getElementById('__gm_cursor_el');
        if (el) el.style.display = show ? 'block' : 'none';
    };

    /** Fire a keyboard event at a specific keyCode. */
    window.gmKey = function(keyCode, type) {
        var evtType = type || 'keydown';
        var evt = new KeyboardEvent(evtType, {
            keyCode: keyCode, which: keyCode,
            bubbles: true, cancelable: true
        });
        document.dispatchEvent(evt);
        var focused = document.activeElement;
        if (focused && focused !== document.body) focused.dispatchEvent(evt);
        /* Also dispatch on canvas if present */
        var canvas = document.querySelector('canvas');
        if (canvas && canvas !== focused) canvas.dispatchEvent(evt);
    };

    console.log('[GameMapper] Virtual cursor injected');
})();
""".trimIndent()
}
