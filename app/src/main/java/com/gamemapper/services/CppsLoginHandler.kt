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
     * Trick rotation (max coins per cycle):
     *   Flip (100) → Turn (10) → Run on Tracks (80) → Turn (10)
     *   → Flip (100) → Turn (10) → Handstand (80) → Turn (10)   = 390 pts / 8 keys
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

    /* ── Trick table ────────────────────────────────────────────────── */
    /* Points from the image. Rules:
       • Never repeat the same trick twice in a row (game penalty).
       • Turn (→ / ←) used as separator; gives 10 pts each.
       • k2 pressed 270 ms after k1 — Ruffle needs the gap to see them
         as a two-key combo, not two independent single-key events.     */
    var tricks = [
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',          pts:100 },  // ↓ Space
        { k1:KEYS.RIGHT, k2:null,        label:'Turn →',   pts:10  },  // →
        { k1:KEYS.DOWN,  k2:KEYS.DOWN,  label:'Run on Tracks',  pts:80  },  // ↓ ↓
        { k1:KEYS.LEFT,  k2:null,        label:'Turn ←',   pts:10  },  // ←
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',           pts:100 },  // ↓ Space
        { k1:KEYS.RIGHT, k2:null,        label:'Turn →',   pts:10  },  // →
        { k1:KEYS.UP,    k2:KEYS.UP,    label:'Handstand',      pts:80  },  // ↑ ↑
        { k1:KEYS.LEFT,  k2:null,        label:'Turn ←',   pts:10  },  // ←
        { k1:KEYS.DOWN,  k2:KEYS.SPACE, label:'Flip',           pts:100 },  // ↓ Space
        { k1:KEYS.RIGHT, k2:null,        label:'Turn →',   pts:10  },  // →
        { k1:KEYS.SPACE, k2:KEYS.RIGHT, label:'Spin →',    pts:80  },  // Space →
        { k1:KEYS.LEFT,  k2:null,        label:'Turn ←',   pts:10  },  // ←
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
    hud.innerHTML = '\uD83E\uDD16 AFK Farm ON';
    document.body.appendChild(hud);

    /* ── Main loop ──────────────────────────────────────────────────── */
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
}
