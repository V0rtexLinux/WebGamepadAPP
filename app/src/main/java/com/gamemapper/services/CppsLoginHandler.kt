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

    /* ── Key code → modern KeyboardEvent properties ────────────────── */
    var KEY_MAP = {
        8:  { key:'Backspace',  code:'Backspace'  },
        9:  { key:'Tab',        code:'Tab'        },
        13: { key:'Enter',      code:'Enter'      },
        16: { key:'Shift',      code:'ShiftLeft'  },
        17: { key:'Control',    code:'ControlLeft'},
        27: { key:'Escape',     code:'Escape'     },
        32: { key:' ',          code:'Space'      },
        37: { key:'ArrowLeft',  code:'ArrowLeft'  },
        38: { key:'ArrowUp',    code:'ArrowUp'    },
        39: { key:'ArrowRight', code:'ArrowRight' },
        40: { key:'ArrowDown',  code:'ArrowDown'  },
        69: { key:'e',          code:'KeyE'       },
        73: { key:'i',          code:'KeyI'       },
        77: { key:'m',          code:'KeyM'       },
        84: { key:'t',          code:'KeyT'       }
    };

    /* ── gmKey: fire a keyboard event with full key/code/keyCode ────── */
    window.gmKey = function(keyCode, type) {
        var evtType = type || 'keydown';
        var km = KEY_MAP[keyCode] || { key: String.fromCharCode(keyCode), code: 'Key' + String.fromCharCode(keyCode) };
        var init = {
            key: km.key, code: km.code,
            keyCode: keyCode, which: keyCode,
            bubbles: true, cancelable: true,
            view: window
        };

        function dispatch(target) {
            try { target.dispatchEvent(new KeyboardEvent(evtType, init)); } catch(e) {}
        }

        /* 1. Ruffle shadow-root canvas — highest priority for Flash games. */
        var ruffle = document.querySelector('ruffle-player');
        if (ruffle && ruffle.shadowRoot) {
            var sc = ruffle.shadowRoot.querySelector('canvas');
            if (sc) dispatch(sc);
        }

        /* 2. Document (global listeners) and focused element. */
        dispatch(document);
        var focused = document.activeElement;
        if (focused && focused !== document.body) dispatch(focused);

        /* 3. Any visible canvas (Phaser / plain HTML5). */
        document.querySelectorAll('canvas').forEach(function(c) {
            if (c !== focused) dispatch(c);
        });
    };

    console.log('[GameMapper] Virtual cursor injected (Ruffle-aware)');
    return 'ok';
})();
""".trimIndent()

    // ── Cart Surfer AFK farmer ────────────────────────────────────────────────
    /**
     * Toggles the Cart Surfer AFK farm loop.
     *  • First call  → starts the loop, returns "started"
     *  • Second call → stops  the loop, returns "stopped"
     *
     * v2 — REACTIVE CURVES:
     *   Cart Surfer's track only turns where warning signs appear (several
     *   scroll into view before the actual curve; missing the turn = crash).
     *   The old version blindly pressed a Turn key on a fixed timer, which
     *   is guesswork and eventually drifts out of sync and crashes the cart.
     *
     *   This version samples the Ruffle/Phaser canvas pixels each tick,
     *   looks for the yellow/gold chevron sign on the tunnel wall, and
     *   reads its shape (wide base narrowing to a point) to tell which way
     *   the upcoming curve bends. It waits for the sign to grow large/close
     *   enough (see gmSetSignTriggerSize) before pressing LEFT/RIGHT, since
     *   several signs are visible before the real bend.
     *
     *   CALIBRATED FROM REAL SCREENSHOTS: the default sign color and band
     *   position below were sampled directly from real gameplay captures
     *   of this game (straight track vs. curve-with-sign), not guessed.
     *   If a different server/skin uses different sign art, call
     *   `gmCalibrateSign()` in the devtools console while playing — it
     *   logs the detected bounding box, size, and inferred direction once
     *   a second so you can compare against what you see and adjust with:
     *     gmSetSignColor(rMin, rMax, gMin, gMax, bMin, bMax)
     *     gmSetSignTriggerSize(fraction)   // 0–1, how close before turning
     *   Call `gmCalibrateSign()` again to stop logging.
     *
     *   FALLBACK: if the canvas can't be read (e.g. it's tainted because
     *   game assets are cross-origin without CORS headers), pixel reading
     *   throws a SecurityError. This is detected once at startup and the
     *   script automatically falls back to the old fixed-timer Turn
     *   behaviour so the farm still runs, just without reactive curves.
     *
     * Non-turn trick rotation (max coins per cycle):
     *   Flip (100) → Run on Tracks (80) → Flip (100) → Handstand (80)
     *   → Flip (100) → Spin (80)                          = 540 pts / cycle
     *   (Turns are now event-driven, not part of the fixed rotation.)
     *
     * Requires VIRTUAL_CURSOR_JS (window.gmKey) to be injected first.
     *
     * Key codes used:
     *   32 = Space  37 = ←Left  38 = ↑Up  39 = →Right  40 = ↓Down
     */
    val CART_SURFER_FARM_TOGGLE_JS = """
(function() {
    /* ── Stop if already running ────────────────────────────────────── */
    if (window.__gm_farm_interval) {
        clearInterval(window.__gm_farm_interval);
        window.__gm_farm_interval = null;
        if (window.__gm_curve_watcher) {
            clearInterval(window.__gm_curve_watcher);
            window.__gm_curve_watcher = null;
        }
        window.__gm_calibrate_watcher && clearInterval(window.__gm_calibrate_watcher);
        var old = document.getElementById('__gm_farm_hud');
        if (old) old.remove();
        return 'stopped';
    }

    /* ── Key definitions ────────────────────────────────────────────── */
    /* Each entry has the modern KeyboardEvent fields that Ruffle uses to
       convert JS events into ActionScript Key.isDown() calls.
       keyCode/which are kept for any legacy listeners in the page.     */
    var KEYS = {
        SPACE: { key:' ',          code:'Space',      keyCode:32, which:32 },
        LEFT:  { key:'ArrowLeft',  code:'ArrowLeft',  keyCode:37, which:37 },
        UP:    { key:'ArrowUp',    code:'ArrowUp',    keyCode:38, which:38 },
        RIGHT: { key:'ArrowRight', code:'ArrowRight', keyCode:39, which:39 },
        DOWN:  { key:'ArrowDown',  code:'ArrowDown',  keyCode:40, which:40 }
    };

    /* ── Find the game keyboard target ──────────────────────────────── */
    function getTarget() {
        /* Ruffle shadow-DOM canvas — Flash key events must land here. */
        var r = document.querySelector('ruffle-player');
        if (r && r.shadowRoot) {
            var sc = r.shadowRoot.querySelector('canvas');
            if (sc) return sc;
            return r;
        }
        /* Phaser / plain HTML5 canvas fallback. */
        var c = document.querySelector('canvas');
        return c || document.documentElement;
    }

    /* ── Send a real keydown + keyup pair to the game ───────────────── */
    /* This does NOT call any game function — it dispatches standard
       DOM KeyboardEvent objects with key/code/keyCode so Ruffle's
       internal listener translates them to ActionScript key events,
       exactly like a physical keyboard press would.                    */
    function pressKey(keyDef, afterRelease) {
        var target = getTarget();
        var init = {
            key:        keyDef.key,
            code:       keyDef.code,
            keyCode:    keyDef.keyCode,
            which:      keyDef.which,
            bubbles:    true,
            cancelable: true,
            composed:   true,   /* crosses shadow-DOM boundary */
            view:       window
        };
        /* keydown → game registers "key pressed" */
        try { target.dispatchEvent(new KeyboardEvent('keydown', init)); } catch(e) {}
        try { document.dispatchEvent(new KeyboardEvent('keydown', init)); } catch(e) {}

        /* keyup 130 ms later → game registers "key released" */
        setTimeout(function() {
            try { target.dispatchEvent(new KeyboardEvent('keyup', init)); } catch(e) {}
            try { document.dispatchEvent(new KeyboardEvent('keyup', init)); } catch(e) {}
            if (afterRelease) afterRelease();
        }, 130);
    }

    /* ── Curve detection (canvas pixel analysis) ─────────────────────── */
    /* Cart Surfer's track shows a yellow/gold chevron warning sign on the
       tunnel wall before every curve (several appear in sequence as you
       approach). Calibrated directly from real gameplay screenshots:
       sign fill color ≈ rgb(190,155,0), sign band sits roughly 25%-58%
       down the canvas, and the chevron's shape narrows from a wide base
       toward a point — the point is which way the curve bends.
       No sign visible = straight track = no turn.                       */

    /* Tunable via gmSetSignColor(rMin,rMax,gMin,gMax,bMin,bMax) if the
       server uses a different art skin. Default values were sampled
       directly from real screenshots of this game's Cart Surfer curve
       sign vs. its straight-track frame (see gmCalibrateSign() to
       re-sample against your own game if this ever stops matching). */
    window.__gm_sign_color = window.__gm_sign_color || {
        rMin: 140, rMax: 255,
        gMin: 100, gMax: 200,
        bMin: 0,   bMax: 45
    };

    window.gmSetSignColor = function(rMin, rMax, gMin, gMax, bMin, bMax) {
        window.__gm_sign_color = { rMin:rMin, rMax:rMax, gMin:gMin, gMax:gMax, bMin:bMin, bMax:bMax };
        console.log('[GameMapper] sign color range updated:', window.__gm_sign_color);
    };

    /* How "big" (close) the sign must look before we actually press the
       turn — 0 to 1, fraction of the sampled band height covered by the
       tallest matching column. Raise this to turn later/closer to the
       bend, lower it to turn earlier. Tune with gmSetSignTriggerSize(). */
    window.__gm_sign_trigger_size = window.__gm_sign_trigger_size || 0.45;
    window.gmSetSignTriggerSize = function(fraction) {
        window.__gm_sign_trigger_size = fraction;
        console.log('[GameMapper] sign trigger size set to', fraction);
    };

    /* Toggle a console logger that prints the sign-colored pixel bounding
       box + peak column height once a second, so you can watch the real
       game and see the numbers as signs approach/pass — useful for
       tuning gmSetSignColor() and gmSetSignTriggerSize(). Call again to
       stop. */
    window.gmCalibrateSign = function() {
        if (window.__gm_calibrate_watcher) {
            clearInterval(window.__gm_calibrate_watcher);
            window.__gm_calibrate_watcher = null;
            console.log('[GameMapper] calibration logging stopped');
            return 'calibration off';
        }
        window.__gm_calibrate_watcher = setInterval(function() {
            var r = scanSignBand();
            if (!r) { console.log('[GameMapper] calibrate: canvas not ready / not readable'); return; }
            console.log('[GameMapper] sign scan -> found=' + r.found
                + ' bbox=[' + r.minX + ',' + r.maxX + ']'
                + ' peakHeightFrac=' + r.peakFrac.toFixed(2)
                + ' dir=' + r.dir);
        }, 1000);
        console.log('[GameMapper] calibration logging started — watch the console while playing');
        return 'calibration on';
    };

    /* Draw whatever canvas the game is using onto a hidden 2D canvas so we
       can read pixels back out. Returns a 2D context, or null if there is
       no usable canvas yet. */
    function getCanvasSnapshot() {
        var source = getTarget();
        if (!source || source.tagName !== 'CANVAS' || !source.width || !source.height) return null;
        var snap = window.__gm_snap_canvas || (window.__gm_snap_canvas = document.createElement('canvas'));
        snap.width = source.width;
        snap.height = source.height;
        var sctx = snap.getContext('2d', { willReadFrequently: true });
        sctx.drawImage(source, 0, 0);
        return sctx;
    }

    /* Scans a horizontal band of the canvas for sign-colored pixels and
       returns { found, minX, maxX, peakFrac, dir } — dir is 'LEFT',
       'RIGHT', or null if a sign is visible but not yet clearly one way
       or the other (still too far / too faint to call). Returns null
       only if the canvas itself couldn't be read at all.                */
    function scanSignBand() {
        var sctx = getCanvasSnapshot();
        if (!sctx) return null;
        var w = sctx.canvas.width, h = sctx.canvas.height;
        var bandY0 = Math.floor(h * 0.25);
        var bandY1 = Math.floor(h * 0.58);
        var bandH  = bandY1 - bandY0;
        var data;
        try { data = sctx.getImageData(0, bandY0, w, bandH).data; }
        catch (e) { return null; }

        var c = window.__gm_sign_color;
        var rows = 12;                                  /* sampled rows within the band */
        var yStep = Math.max(1, Math.floor(bandH / rows));
        var xStep = Math.max(1, Math.floor(w / 300));    /* ~300 sampled columns across width */
        var bucketW = xStep;
        var buckets = {};                                /* xBucket -> hit count across sampled rows */

        for (var y = 0; y < bandH; y += yStep) {
            for (var x = 0; x < w; x += xStep) {
                var i = (y * w + x) * 4;
                var r = data[i], g = data[i+1], b = data[i+2];
                var diff = r - g;
                if (r >= c.rMin && r <= c.rMax && g >= c.gMin && g <= c.gMax &&
                    b >= c.bMin && b <= c.bMax && diff > 10 && diff < 80) {
                    var bx = Math.floor(x / bucketW);
                    buckets[bx] = (buckets[bx] || 0) + 1;
                }
            }
        }

        var bucketKeys = Object.keys(buckets).map(Number).sort(function(a, b2) { return a - b2; });
        if (!bucketKeys.length) return { found:false, minX:0, maxX:0, peakFrac:0, dir:null };

        var minB = bucketKeys[0], maxB = bucketKeys[bucketKeys.length - 1];
        var peak = 0;
        for (var k = 0; k < bucketKeys.length; k++) {
            if (buckets[bucketKeys[k]] > peak) peak = buckets[bucketKeys[k]];
        }
        var sampledRows = Math.ceil(bandH / yStep);
        var peakFrac = peak / sampledRows;

        /* Not enough of a cluster yet (noise / distant speck) to call it a sign. */
        if ((maxB - minB) < 2 || peakFrac < 0.15) {
            return { found:false, minX:minB * bucketW, maxX:maxB * bucketW, peakFrac:peakFrac, dir:null };
        }

        /* Chevron shape: wide base, narrowing to a point. Compare average
           hit-count on the left third of the cluster vs the right third —
           the point (tip) is the side with less coverage, and the curve
           bends toward the tip. */
        var span = maxB - minB + 1;
        var thirdW = Math.max(1, Math.floor(span / 3));
        var leftSum = 0, leftN = 0, rightSum = 0, rightN = 0;
        for (var kk = 0; kk < bucketKeys.length; kk++) {
            var bkx = bucketKeys[kk];
            var v = buckets[bkx];
            if (bkx <= minB + thirdW) { leftSum += v; leftN++; }
            else if (bkx >= maxB - thirdW) { rightSum += v; rightN++; }
        }
        var leftAvg = leftN ? leftSum / leftN : 0;
        var rightAvg = rightN ? rightSum / rightN : 0;

        var dir = null;
        if (leftAvg > rightAvg * 1.25) dir = 'RIGHT';       /* base on the left, tip on the right */
        else if (rightAvg > leftAvg * 1.25) dir = 'LEFT';   /* base on the right, tip on the left */

        return { found:true, minX:minB * bucketW, maxX:maxB * bucketW, peakFrac:peakFrac, dir:dir };
    }

    /* Probe once whether the canvas is readable at all. Cross-origin game
       assets without CORS headers taint the canvas and getImageData()
       throws — in that case we can't do reactive detection and fall back
       to the old fixed-timer Turn behaviour instead of silently doing
       nothing. */
    var curveDetectionEnabled = false;
    (function probeCanvasReadability() {
        var sctx = getCanvasSnapshot();
        if (!sctx) { console.warn('[GameMapper] canvas not ready yet — will retry curve detection shortly'); }
        try {
            if (sctx) sctx.getImageData(0, 0, 1, 1);
            curveDetectionEnabled = true;
            console.log('[GameMapper] canvas readable — reactive curve detection ENABLED. Run gmCalibrateSign() to watch it work / tune it.');
        } catch (e) {
            curveDetectionEnabled = false;
            console.warn('[GameMapper] canvas is tainted (cross-origin assets) — reactive curve detection DISABLED, using fixed-timer Turn fallback', e);
        }
    })();

    /* ── Trick table ────────────────────────────────────────────────── */
    /* Points from the image. Rules:
       • Never repeat the same trick twice in a row (game penalty).
       • k2 pressed 270 ms after k1 — Ruffle needs the gap to see them
         as a two-key combo, not two independent single-key events.
       When curve detection is enabled, Turn is handled reactively by the
       curve watcher below and dropped from this fixed rotation so we
       don't double-turn. If detection is disabled (fallback mode), Turn
       stays in the rotation like the old blind version.                  */
    var tricks = curveDetectionEnabled ? [
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',          pts:100 },  // ↓ Space
        { k1:KEYS.DOWN,  k2:KEYS.DOWN,  label:'Run on Tracks', pts:80  },  // ↓ ↓
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',          pts:100 },  // ↓ Space
        { k1:KEYS.UP,    k2:KEYS.UP,    label:'Handstand',     pts:80  },  // ↑ ↑
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',          pts:100 },  // ↓ Space
        { k1:KEYS.SPACE, k2:KEYS.RIGHT, label:'Spin →',        pts:80  },  // Space →
    ] : [
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',          pts:100 },  // ↓ Space
        { k1:KEYS.RIGHT, k2:null,        label:'Turn →',       pts:10  },  // →
        { k1:KEYS.DOWN,  k2:KEYS.DOWN,  label:'Run on Tracks',  pts:80  },  // ↓ ↓
        { k1:KEYS.LEFT,  k2:null,        label:'Turn ←',       pts:10  },  // ←
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',           pts:100 },  // ↓ Space
        { k1:KEYS.RIGHT, k2:null,        label:'Turn →',       pts:10  },  // →
        { k1:KEYS.UP,    k2:KEYS.UP,    label:'Handstand',      pts:80  },  // ↑ ↑
        { k1:KEYS.LEFT,  k2:null,        label:'Turn ←',       pts:10  },  // ←
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',           pts:100 },  // ↓ Space
        { k1:KEYS.RIGHT, k2:null,        label:'Turn →',       pts:10  },  // →
        { k1:KEYS.SPACE, k2:KEYS.RIGHT, label:'Spin →',    pts:80  },  // Space →
        { k1:KEYS.LEFT,  k2:null,        label:'Turn ←',       pts:10  },  // ←
    ];

    var step     = 0;
    var totalPts = 0;
    var GAP_MS   = 270;   /* delay between k1 keyup and k2 keydown */
    var LOOP_MS  = 1900;  /* interval between tricks (~Cart Surfer window) */

    /* ── On-screen HUD ──────────────────────────────────────────────── */
    var hud = document.createElement('div');
    hud.id = '__gm_farm_hud';
    hud.style.cssText = [
        'position:fixed',
        'top:10px','left:50%','transform:translateX(-50%)',
        'background:rgba(0,0,0,0.78)',
        'color:#FFD700',
        'font:bold 13px/1.5 sans-serif',
        'padding:5px 16px',
        'border-radius:20px',
        'border:1.5px solid #FFD700',
        'z-index:2147483647',
        'pointer-events:none',
        'text-align:center',
        'white-space:nowrap'
    ].join(';');
    hud.innerHTML = '\uD83E\uDD16 AFK Farm ON' + (curveDetectionEnabled ? ' <small style="color:#8fd">(curvas: reativo)</small>' : ' <small style="color:#f88">(curvas: timer fixo)</small>');
    document.body.appendChild(hud);

    /* ── Reactive curve watcher ──────────────────────────────────────
       Runs independently of the trick loop's timing so a turn fires
       when a sign is actually seen close/large on screen, not on a
       guessed clock. The sign scrolls by several times before the real
       curve, so this counts distinct appearances and only fires on the
       last one: it waits for the sign to grow past
       gmSetSignTriggerSize() (close = about to reach the bend), but
       skips the first few passes. It fires once, then requires the
       sign to disappear from view before it can fire again for the
       next curve.                                                     */
    var signArmed    = true;  /* true = ready to fire on the next big-enough sign */
    var noSignStreak  = 0;
    var signPassCount = 0;    /* distinct sign appearances since last turn */

    if (curveDetectionEnabled) {
        window.__gm_curve_watcher = setInterval(function() {
            var r = scanSignBand();
            if (!r) return;               /* canvas hiccup this tick — just skip */

            if (!r.found) {
                noSignStreak++;
                if (noSignStreak >= 3) {
                    signArmed = true;
                    signPassCount = 0;     /* sign has cleared — ready for the next one */
                }
                return;
            }
            noSignStreak = 0;

            if (!signArmed) return;                /* already turned for this sign */
            if (r.dir === null) return;             /* sign visible but shape unclear yet */
            if (r.peakFrac < window.__gm_sign_trigger_size) return; /* still too small/far */

            signPassCount++;
            if (signPassCount < 4) return;          /* skip early passes, fire on the real curve sign */

            signArmed = false;
            pressKey(r.dir === 'LEFT' ? KEYS.LEFT : KEYS.RIGHT, null);
            totalPts += 10;
            hud.innerHTML = '\uD83E\uDD16 Turn ' + (r.dir === 'LEFT' ? '←' : '→')
                + ' <span style="color:#aaffaa">+10pts</span>'
                + '<br><small style="color:#bbb">total: ~' + totalPts + ' pts</small>';
        }, 90);
    }

    /* ── Main trick loop ───────────────────────────────────────────── */
    window.__gm_farm_interval = setInterval(function() {
        var t = tricks[step % tricks.length];
        step++;
        totalPts += t.pts;

        hud.innerHTML = '\uD83E\uDD16 ' + t.label
            + ' <span style="color:#aaffaa">+' + t.pts + 'pts</span>'
            + '<br><small style="color:#bbb">total: ~' + totalPts + ' pts</small>';

        /* Press k1; after its keyup fires, wait GAP_MS then press k2. */
        pressKey(t.k1, t.k2 ? function() {
            setTimeout(function() { pressKey(t.k2, null); }, GAP_MS - 130);
        } : null);

    }, LOOP_MS);

    return 'started';
})();
""".trimIndent()
}
