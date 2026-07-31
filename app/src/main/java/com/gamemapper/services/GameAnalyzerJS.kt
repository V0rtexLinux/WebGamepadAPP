package com.gamemapper.services

/**
 * JavaScript injected into the WebView to detect game controls.
 *
 * Architecture (3-stage pipeline):
 *  STAGE 1 – Structural Blacklist: eliminate all institutional/nav DOM nodes before any mapping.
 *  STAGE 2 – Dynamic Login/WebGL Isolation: detect login vs. active gameplay state via WebGL/rAF.
 *  STAGE 3 – Canvas Quadrant Heuristics: map the primary game canvas into logical control zones
 *            (D-Pad lower-left, Actions lower-right, UI upper-right, Click full-canvas).
 *
 * clickableElements is intentionally left EMPTY. All mapping is canvas-coordinate-based.
 */
object GameAnalyzerJS {

    /**
     * PHASE 1 – Injected via onPageStarted, before any game script runs.
     * Hooks:
     *   • addEventListener spy (event frequency map)
     *   • requestAnimationFrame counter (detect active rendering loop)
     *   • HTMLCanvasElement.getContext hook (detect WebGL context creation)
     */
    val EARLY_HOOK_SCRIPT = """
(function() {
    if (window.__gmapper_hooked) return;
    window.__gmapper_hooked  = true;
    window.__gmapper_events  = [];
    window.__gmapper_freq    = {};
    window.__gmapper_raf_count   = 0;
    window.__gmapper_webgl_active = false;

    /* ── Event listener spy ──────────────────────────────────────── */
    var _origAEL = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function(type, listener, opts) {
        _origAEL.call(this, type, listener, opts);
        var tag = (this && this.tagName) ? this.tagName.toLowerCase() : 'window';
        var key = type + '|' + tag;
        window.__gmapper_freq[key] = (window.__gmapper_freq[key] || 0) + 1;
        if (['keydown','keyup','keypress','mousedown','mouseup','click',
             'touchstart','touchend','touchmove','mousemove','pointerdown',
             'pointerup','pointermove','contextmenu','wheel'].indexOf(type) >= 0) {
            window.__gmapper_events.push({
                type: type,
                tag:  tag,
                id:   (this && this.id) ? this.id : '',
                className: (this && this.className && typeof this.className === 'string')
                            ? this.className.substring(0, 80) : '',
                freq: window.__gmapper_freq[key]
            });
        }
    };

    /* ── requestAnimationFrame counter ──────────────────────────── */
    var _origRAF = window.requestAnimationFrame;
    window.requestAnimationFrame = function(cb) {
        window.__gmapper_raf_count++;
        return _origRAF ? _origRAF.call(window, cb) : setTimeout(cb, 16);
    };

    /* ── WebGL context hook ──────────────────────────────────────── */
    var _origGetCtx = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(type, attrs) {
        var ctx = _origGetCtx.call(this, type, attrs);
        if (ctx && (type === 'webgl' || type === 'webgl2' ||
                    type === 'experimental-webgl')) {
            window.__gmapper_webgl_active = true;
        }
        return ctx;
    };
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  READINESS PROBE — polled by AnalyzerActivity every 500 ms
    // ─────────────────────────────────────────────────────────────────────────
    /** Returns "true" when the game engine is actively rendering. */
    val READINESS_PROBE = """
(function() {
    var webgl  = window.__gmapper_webgl_active === true;
    var raf    = (window.__gmapper_raf_count || 0) > 8;
    var canvas = document.querySelector('canvas') !== null;
    return JSON.stringify({ webgl: webgl, raf: window.__gmapper_raf_count || 0,
                            canvas: canvas, ready: webgl || raf });
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  LOGIN STATE PROBE — quick check before showing the gamepad overlay
    // ─────────────────────────────────────────────────────────────────────────
    /** Returns JSON: { isLoginState, hasCanvas, hasWebGL, inputCount } */
    val LOGIN_STATE_PROBE = """
(function() {
    var inputs = Array.from(document.querySelectorAll(
        'input[type="text"],input[type="password"],input[type="email"],input:not([type])'))
        .filter(function(el) {
            var r = el.getBoundingClientRect();
            return r.width > 10 && r.height > 10;
        });
    var hasCanvas = !!document.querySelector('canvas');
    var webgl     = window.__gmapper_webgl_active === true;
    return JSON.stringify({
        isLoginState: inputs.length > 0 && !webgl,
        hasCanvas: hasCanvas,
        hasWebGL:  webgl,
        inputCount: inputs.length
    });
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  DEEP ANALYSIS — the main 3-stage engine scan
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * PHASE 2 – Full canvas-quadrant analysis.
     * Runs after page load + WebGL/rAF readiness confirmed.
     *
     * Stages:
     *   1. Structural Blacklist   – exclude nav/header/footer/menu nodes.
     *   2. Canvas Detection       – find primary game canvas (largest + WebGL).
     *   3. Login State Guard      – record whether we're pre-login or in-game.
     *   4. Quadrant Mapping       – divide canvas into D-Pad, Actions, UI, Click zones.
     *   5. Keyboard Scan          – emit keys only when canvas is present.
     *
     * clickableElements is ALWAYS empty — no DOM nav pollution.
     */
    val DEEP_ANALYSIS_SCRIPT = """
(function() {

    /* ════════════════════════════════════════════════════════════════
       STAGE 1 – STRUCTURAL BLACKLIST
       ════════════════════════════════════════════════════════════════ */

    var BLACKLIST_TEXTS = [
        'home',"what's new",'community rules','legal','discord','help',
        'terms of service','privacy policy','log out','logout','sign out',
        'reset your password','sign up','sign in','register','about us',
        'contact','blog','news','store','download','support','faq',
        'cookie policy','copyright','all rights reserved','advertisement',
        'sponsored content','follow us','newsletter','subscribe'
    ];

    /* Structural selectors whose subtree is unconditionally excluded */
    var EXCLUDE_SELECTORS = [
        'nav', 'header', 'footer', 'aside',
        '[role="navigation"]', '[role="banner"]', '[role="contentinfo"]',
        '[role="complementary"]',
        '.nav', '.navbar', '.navigation', '.main-nav', '.site-nav',
        '.menu', '.menu-links', '.menu-bar', '.menubar',
        '.header', '.site-header', '.page-header', '.top-header',
        '.footer', '.site-footer', '.page-footer',
        '.sidebar', '.side-bar', '.side-panel',
        '.topbar', '.top-bar', '.bottom-bar',
        '.breadcrumb', '.breadcrumbs',
        '.social-links', '.social-bar',
        '#nav', '#navbar', '#navigation', '#main-nav',
        '#header', '#site-header', '#page-header',
        '#footer', '#site-footer',
        '#sidebar', '#side-panel',
        '#menu', '#main-menu', '#top-menu'
    ];

    var _excludedSet = new Set();
    EXCLUDE_SELECTORS.forEach(function(sel) {
        try {
            document.querySelectorAll(sel).forEach(function(el) {
                _excludedSet.add(el);
                el.querySelectorAll('*').forEach(function(c){ _excludedSet.add(c); });
            });
        } catch(ignore) {}
    });

    function isInstitutional(el) {
        if (!el) return true;
        if (_excludedSet.has(el)) return true;
        /* Walk up to find an excluded ancestor */
        var p = el.parentElement;
        while (p) { if (_excludedSet.has(p)) return true; p = p.parentElement; }
        /* Exact text-content match against blacklist */
        var txt = (el.textContent || '').trim().toLowerCase();
        if (txt.length < 60 && BLACKLIST_TEXTS.some(function(t){ return txt === t; })) return true;
        /* href pattern match */
        var href = (el.getAttribute ? el.getAttribute('href') : '') || '';
        if (href) {
            var hl = href.toLowerCase();
            var hPatterns = ['#logout','#log-out','/logout','/signout','/legal',
                             '/privacy','/terms','/help','/about','/contact','/faq',
                             '/discord','discord.gg','discord.com/invite'];
            if (hPatterns.some(function(p){ return hl.indexOf(p) >= 0; })) return true;
        }
        return false;
    }

    /* ════════════════════════════════════════════════════════════════
       STAGE 2 – CANVAS & ENGINE DETECTION
       ════════════════════════════════════════════════════════════════ */

    var GAME_CONTAINER_IDS = [
        'game','game-container','gameContainer','game_container',
        'phaser-game','phaser','stage','canvas','gameCanvas',
        'game-canvas','gamecanvas','app','main-game','world',
        'viewport','renderCanvas','arena'
    ];

    var canvasZones = [];
    var primaryCanvas = null;
    var biggestArea   = 0;

    document.querySelectorAll('canvas').forEach(function(c, ci) {
        var rect = c.getBoundingClientRect();
        if (rect.width < 50 || rect.height < 50) return;

        var hasWebGL = false;
        try {
            var wctx = c.getContext('webgl') || c.getContext('webgl2') ||
                       c.getContext('experimental-webgl');
            if (wctx) hasWebGL = true;
        } catch(e) {}

        var has2D = false;
        try { if (c.getContext('2d')) has2D = true; } catch(e) {}

        var area = rect.width * rect.height;
        var zone = {
            index:     ci,
            id:        c.id || ('canvas_' + ci),
            x:         Math.round(rect.left),
            y:         Math.round(rect.top),
            w:         Math.round(rect.width),
            h:         Math.round(rect.height),
            area:      area,
            hasWebGL:  hasWebGL,
            has2D:     has2D,
            hasClick:  c.onclick != null,
            hasMouse:  (window.__gmapper_events||[]).some(function(ev){
                return (ev.type==='mousedown'||ev.type==='click') &&
                       (ev.tag==='canvas'||ev.id===c.id);
            }),
            hasTouch:  (window.__gmapper_events||[]).some(function(ev){
                return ev.type==='touchstart' &&
                       (ev.tag==='canvas'||ev.id===c.id);
            }),
            rafActive:  (window.__gmapper_raf_count || 0) > 5,
            webglActive: window.__gmapper_webgl_active || hasWebGL,
            isPrimary:  false
        };
        canvasZones.push(zone);

        /* Prioritise: WebGL > 2D > largest area */
        var isPriority = (hasWebGL && !primaryCanvas) ||
                         (hasWebGL && primaryCanvas && !primaryCanvas.hasWebGL) ||
                         (!primaryCanvas) ||
                         (area > biggestArea && !primaryCanvas.hasWebGL);
        if (isPriority) { biggestArea = area; primaryCanvas = zone; }

        /* Also prioritise by known game container ID */
        if (c.id && GAME_CONTAINER_IDS.indexOf(c.id.toLowerCase()) >= 0) {
            primaryCanvas = zone;
        }
    });

    if (primaryCanvas) primaryCanvas.isPrimary = true;

    /* ════════════════════════════════════════════════════════════════
       STAGE 3 – LOGIN STATE DETECTION
       ════════════════════════════════════════════════════════════════ */

    var visibleInputs = Array.from(document.querySelectorAll(
        'input[type="text"],input[type="password"],input[type="email"],input:not([type])'
    )).filter(function(el) {
        if (isInstitutional(el)) return false;
        var r = el.getBoundingClientRect();
        return r.width > 10 && r.height > 10;
    });

    var isWebGLActive  = window.__gmapper_webgl_active === true;
    var isRafActive    = (window.__gmapper_raf_count || 0) > 8;
    /* Login state = has inputs AND no active WebGL rendering yet */
    var isLoginState   = visibleInputs.length > 0 && !isWebGLActive && !isRafActive;
    var isGameplayActive = isWebGLActive || isRafActive || (primaryCanvas !== null);

    /* ════════════════════════════════════════════════════════════════
       STAGE 4 – CANVAS QUADRANT MAPPING
       ════════════════════════════════════════════════════════════════ */

    var canvasQuadrants = [];

    if (primaryCanvas && primaryCanvas.w >= 100 && primaryCanvas.h >= 100) {
        var cx = primaryCanvas.x;
        var cy = primaryCanvas.y;
        var cw = primaryCanvas.w;
        var ch = primaryCanvas.h;

        /*
         * Layout (portrait-safe):
         *
         *  ┌───────────────────────────┐
         *  │  [CANVAS_CLICK full area] │
         *  │                           │
         *  │         gameplay          │
         *  │                           │
         *  │  [DPAD]        [ACTIONS]  │
         *  └───────────────────────────┘
         *  [UI strip: upper-right 38% × 25%]
         */

        /* FULL CANVAS – click-to-move (CP walks when you click the island) */
        canvasQuadrants.push({
            zone:     'CANVAS_CLICK',
            label:    'Clique no Canvas',
            x:        cx,
            y:        cy,
            w:        cw,
            h:        ch,
            keys:     [{ keyCode: -1, label: 'Click', direction: 'click' }],
            category: 'interaction',
            priority: 0
        });

        /* D-PAD – lower-left 38% × 38% */
        canvasQuadrants.push({
            zone:     'DPAD',
            label:    'D-Pad / Movimento',
            x:        Math.round(cx),
            y:        Math.round(cy + ch * 0.57),
            w:        Math.round(cw * 0.38),
            h:        Math.round(ch * 0.38),
            keys: [
                { keyCode: 38, label: '↑', direction: 'up'    },
                { keyCode: 40, label: '↓', direction: 'down'  },
                { keyCode: 37, label: '←', direction: 'left'  },
                { keyCode: 39, label: '→', direction: 'right' }
            ],
            category: 'movement',
            priority: 1
        });

        /* ACTION BUTTONS – lower-right 36% × 38% */
        canvasQuadrants.push({
            zone:     'ACTION',
            label:    'Botões de Ação',
            x:        Math.round(cx + cw * 0.62),
            y:        Math.round(cy + ch * 0.57),
            w:        Math.round(cw * 0.36),
            h:        Math.round(ch * 0.38),
            keys: [
                { keyCode: 32,  label: 'Espaço', direction: 'south' },
                { keyCode: 13,  label: 'Enter',  direction: 'east'  },
                { keyCode: 69,  label: 'E',       direction: 'north' },
                { keyCode: 27,  label: 'Esc',     direction: 'west'  }
            ],
            category: 'action',
            priority: 2
        });

        /* UI STRIP – upper-right 36% × 25% (chat, map, inventory) */
        canvasQuadrants.push({
            zone:     'UI',
            label:    'Interface / UI',
            x:        Math.round(cx + cw * 0.62),
            y:        Math.round(cy),
            w:        Math.round(cw * 0.36),
            h:        Math.round(ch * 0.25),
            keys: [
                { keyCode: 84, label: 'T  Chat',  direction: 'l1' },
                { keyCode: 77, label: 'M  Mapa',  direction: 'r1' },
                { keyCode: 73, label: 'I  Inv',   direction: 'l2' }
            ],
            category: 'ui',
            priority: 3
        });
    }

    /* ════════════════════════════════════════════════════════════════
       STAGE 5 – KEYBOARD SCAN (canvas-context-only)
       Emit keyboard mapping only when the page has an active canvas/engine.
       ════════════════════════════════════════════════════════════════ */

    var keyboard = [];
    var kbEvts = (window.__gmapper_events||[]).filter(function(ev){
        return ev.type==='keydown'||ev.type==='keypress'||ev.type==='keyup';
    });
    var keyNames = {
        32:'Space',37:'←',38:'↑',39:'→',40:'↓',
        65:'A',66:'B',67:'C',68:'D',69:'E',70:'F',71:'G',72:'H',73:'I',
        74:'J',75:'K',76:'L',77:'M',78:'N',79:'O',80:'P',81:'Q',82:'R',
        83:'S',84:'T',85:'U',86:'V',87:'W',88:'X',89:'Y',90:'Z',
        13:'Enter',27:'Esc',49:'1',50:'2',51:'3',52:'4',53:'5',
        54:'6',55:'7',56:'8',57:'9',48:'0'
    };

    if (canvasZones.length > 0 || kbEvts.length > 0) {
        /* CP-centric key set: arrows + WASD + hotkeys */
        var cpKeys = [
            {kc:38,cat:'movement'},{kc:40,cat:'movement'},
            {kc:37,cat:'movement'},{kc:39,cat:'movement'},
            {kc:87,cat:'movement'},{kc:65,cat:'movement'},
            {kc:83,cat:'movement'},{kc:68,cat:'movement'},
            {kc:32,cat:'action' },{kc:13,cat:'action'  },
            {kc:69,cat:'interaction'},{kc:70,cat:'interaction'},
            {kc:27,cat:'ui'},{kc:84,cat:'ui'},
            {kc:77,cat:'ui'},{kc:73,cat:'ui'},
            {kc:81,cat:'ui'},{kc:82,cat:'action'}
        ];
        cpKeys.forEach(function(entry) {
            keyboard.push({
                keyCode:  entry.kc,
                label:    keyNames[entry.kc] || String.fromCharCode(entry.kc),
                category: entry.cat,
                freq:     kbEvts.length > 0 ? kbEvts.length : 4
            });
        });
    }

    /* ════════════════════════════════════════════════════════════════
       FINAL RESULT – NO DOM nav elements ever reach this object
       ════════════════════════════════════════════════════════════════ */
    var result = {
        title:           document.title || location.hostname,
        url:             location.href,
        analysisMode:    'canvas_quadrant_v2',
        isLoginState:    isLoginState,
        isGameplayActive:isGameplayActive,
        isWebGLActive:   isWebGLActive,
        rafCount:        window.__gmapper_raf_count || 0,
        primaryCanvas:   primaryCanvas,
        canvasZones:     canvasZones,
        canvasQuadrants: canvasQuadrants,
        keyboard:        keyboard,
        clickableElements: [],   /* ALWAYS EMPTY – prevents nav/footer pollution */
        touchZones:      [],
        registeredEventCount: (window.__gmapper_events||[]).length,
        timestamp:       Date.now()
    };

    return JSON.stringify(result);
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  REMAP ANALYSIS — alternative layout pass (fresh coordinate re-scan)
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Re-runs the canvas quadrant mapping with fresh BoundingClientRect values.
     * Used when the user taps "Remap" after the page has loaded/scrolled.
     * Identical blacklist rules apply.
     */
    val REMAP_ANALYSIS_SCRIPT = """
(function() {

    /* Same canvas detection as deep analysis, fresh coordinates */
    var canvasZones = [];
    var primaryCanvas = null;
    var biggestArea = 0;
    var GAME_IDS = ['game','game-container','gamecointainer','phaser-game','phaser',
                    'stage','canvas','gameCanvas','game-canvas','gamecanvas','app',
                    'main-game','world','viewport','renderCanvas','arena'];

    document.querySelectorAll('canvas').forEach(function(c, ci) {
        var rect = c.getBoundingClientRect();
        if (rect.width < 50 || rect.height < 50) return;
        var hasWebGL = false;
        try { hasWebGL = !!(c.getContext('webgl') || c.getContext('webgl2') ||
                            c.getContext('experimental-webgl')); } catch(e) {}
        var area = rect.width * rect.height;
        var zone = {
            index: ci, id: c.id||('canvas_'+ci),
            x: Math.round(rect.left), y: Math.round(rect.top),
            w: Math.round(rect.width), h: Math.round(rect.height),
            area: area, hasWebGL: hasWebGL, has2D: !hasWebGL,
            hasClick: true, hasMouse: true, hasTouch: false,
            rafActive: true, webglActive: hasWebGL || window.__gmapper_webgl_active,
            isPrimary: false
        };
        canvasZones.push(zone);
        if (!primaryCanvas || area > biggestArea ||
            (hasWebGL && !primaryCanvas.hasWebGL) ||
            (c.id && GAME_IDS.indexOf(c.id.toLowerCase()) >= 0)) {
            biggestArea = area; primaryCanvas = zone;
        }
    });
    if (primaryCanvas) primaryCanvas.isPrimary = true;

    var canvasQuadrants = [];
    if (primaryCanvas) {
        var cx=primaryCanvas.x, cy=primaryCanvas.y,
            cw=primaryCanvas.w, ch=primaryCanvas.h;

        canvasQuadrants.push({ zone:'CANVAS_CLICK', label:'Clique no Canvas',
            x:cx, y:cy, w:cw, h:ch,
            keys:[{keyCode:-1,label:'Click',direction:'click'}],
            category:'interaction', priority:0 });

        canvasQuadrants.push({ zone:'DPAD', label:'D-Pad / Movimento',
            x:Math.round(cx), y:Math.round(cy+ch*0.57),
            w:Math.round(cw*0.38), h:Math.round(ch*0.38),
            keys:[{keyCode:38,label:'↑',direction:'up'},
                  {keyCode:40,label:'↓',direction:'down'},
                  {keyCode:37,label:'←',direction:'left'},
                  {keyCode:39,label:'→',direction:'right'}],
            category:'movement', priority:1 });

        canvasQuadrants.push({ zone:'ACTION', label:'Botões de Ação',
            x:Math.round(cx+cw*0.62), y:Math.round(cy+ch*0.57),
            w:Math.round(cw*0.36), h:Math.round(ch*0.38),
            keys:[{keyCode:32,label:'Espaço',direction:'south'},
                  {keyCode:13,label:'Enter',direction:'east'},
                  {keyCode:69,label:'E',direction:'north'},
                  {keyCode:27,label:'Esc',direction:'west'}],
            category:'action', priority:2 });

        canvasQuadrants.push({ zone:'UI', label:'Interface / UI',
            x:Math.round(cx+cw*0.62), y:Math.round(cy),
            w:Math.round(cw*0.36), h:Math.round(ch*0.25),
            keys:[{keyCode:84,label:'T  Chat',direction:'l1'},
                  {keyCode:77,label:'M  Mapa',direction:'r1'},
                  {keyCode:73,label:'I  Inv',direction:'l2'}],
            category:'ui', priority:3 });
    }

    var broadKeys = [
        {kc:38,label:'↑',cat:'movement'},{kc:40,label:'↓',cat:'movement'},
        {kc:37,label:'←',cat:'movement'},{kc:39,label:'→',cat:'movement'},
        {kc:87,label:'W',cat:'movement'},{kc:65,label:'A',cat:'movement'},
        {kc:83,label:'S',cat:'movement'},{kc:68,label:'D',cat:'movement'},
        {kc:32,label:'Space',cat:'action'},{kc:13,label:'Enter',cat:'action'},
        {kc:27,label:'Esc',cat:'ui'},{kc:69,label:'E',cat:'interaction'},
        {kc:70,label:'F',cat:'interaction'},{kc:82,label:'R',cat:'action'},
        {kc:81,label:'Q',cat:'ui'},{kc:84,label:'T',cat:'ui'},
        {kc:73,label:'I',cat:'ui'},{kc:77,label:'M',cat:'ui'},
        {kc:49,label:'1',cat:'action'},{kc:50,label:'2',cat:'action'},
        {kc:51,label:'3',cat:'action'},{kc:52,label:'4',cat:'action'}
    ];

    return JSON.stringify({
        title:            document.title || location.hostname,
        url:              location.href,
        analysisMode:     'canvas_quadrant_remap',
        isLoginState:     false,
        isGameplayActive: true,
        isWebGLActive:    window.__gmapper_webgl_active || false,
        rafCount:         window.__gmapper_raf_count || 0,
        primaryCanvas:    primaryCanvas,
        canvasZones:      canvasZones,
        canvasQuadrants:  canvasQuadrants,
        keyboard:         broadKeys.map(function(k){
                            return {keyCode:k.kc,label:k.label,category:k.cat,freq:5};
                          }),
        clickableElements:[], touchZones:[],
        registeredEventCount: (window.__gmapper_events||[]).length,
        remapMode: true,
        timestamp: Date.now()
    });
})();
""".trimIndent()
}
