package com.gamemapper.services

/**
 * JavaScript injected into the WebView to detect all game controls.
 * Works with any web game: Club Penguin, CPJourney, custom fan servers, etc.
 */
object GameAnalyzerJS {

    /**
     * PHASE 1 – Hook all event listeners before any game code runs.
     * Injected via WebViewClient.onPageStarted so it fires before game scripts.
     */
    val EARLY_HOOK_SCRIPT = """
(function() {
    if (window.__gmapper_hooked) return;
    window.__gmapper_hooked = true;
    window.__gmapper_events = [];
    window.__gmapper_freq   = {};

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
                tag: tag,
                id: (this && this.id) ? this.id : '',
                className: (this && this.className && typeof this.className === 'string') ? this.className.substring(0,80) : '',
                freq: window.__gmapper_freq[key]
            });
        }
    };
})();
""".trimIndent()

    /**
     * PHASE 2 – Deep analysis script injected after page load (onPageFinished).
     * Returns a JSON blob with all detected controls.
     */
    val DEEP_ANALYSIS_SCRIPT = """
(function() {
    var result = {
        title: document.title || location.hostname,
        url: location.href,
        keyboard: [],
        canvasZones: [],
        clickableElements: [],
        touchZones: [],
        registeredEvents: window.__gmapper_events || [],
        timestamp: Date.now()
    };

    /* ── 1. Keyboard keys actually used ──────────────────────────── */
    var keyMap = {};
    var keyNames = {
        32:'Space',37:'←',38:'↑',39:'→',40:'↓',
        65:'A',66:'B',67:'C',68:'D',69:'E',70:'F',71:'G',
        72:'H',73:'I',74:'J',75:'K',76:'L',77:'M',78:'N',
        79:'O',80:'P',81:'Q',82:'R',83:'S',84:'T',85:'U',
        86:'V',87:'W',88:'X',89:'Y',90:'Z',
        13:'Enter',27:'Esc',9:'Tab',16:'Shift',17:'Ctrl',18:'Alt',
        49:'1',50:'2',51:'3',52:'4',53:'5',54:'6',55:'7',56:'8',57:'9',48:'0',
        112:'F1',113:'F2',114:'F3',115:'F4',116:'F5'
    };

    /* Monkey-patch onkeydown to capture actual keys pressed */
    var _origOnKeyDown = window.onkeydown;
    window.__gmapper_keys_seen = window.__gmapper_keys_seen || {};
    var origDispatch = EventTarget.prototype.dispatchEvent;
    EventTarget.prototype.dispatchEvent = function(e) {
        if (e && (e.type === 'keydown' || e.type === 'keypress') && e.keyCode) {
            window.__gmapper_keys_seen[e.keyCode] = true;
        }
        return origDispatch.call(this, e);
    };

    /* Registered events that are keyboard types */
    var kbEvents = (window.__gmapper_events || []).filter(function(ev) {
        return ev.type === 'keydown' || ev.type === 'keypress' || ev.type === 'keyup';
    });
    if (kbEvents.length > 0) {
        /* We know keyboard is used – try to infer keys from common patterns */
        var commonKeys = [37,38,39,40,65,68,83,87,32,13,27,90,88,67,86];
        commonKeys.forEach(function(kc) {
            var name = keyNames[kc] || String.fromCharCode(kc);
            var cat = 'action';
            if ([37,38,39,40].indexOf(kc)>=0) cat='movement';
            if ([65,87,83,68].indexOf(kc)>=0) cat='movement';
            keyMap[kc] = { keyCode: kc, label: name, category: cat, freq: kbEvents.length };
        });
    }
    /* Also check inline onkeydown attributes */
    document.querySelectorAll('[onkeydown],[onkeypress],[onkeyup]').forEach(function(el) {
        var attr = el.getAttribute('onkeydown') || el.getAttribute('onkeypress') || '';
        var matches = attr.match(/keyCode\s*==?\s*(\d+)/g) || [];
        matches.forEach(function(m) {
            var kc = parseInt(m.replace(/\D/g,''));
            if (kc && keyNames[kc]) {
                keyMap[kc] = { keyCode: kc, label: keyNames[kc], category: 'action', freq: 1 };
            }
        });
    });
    result.keyboard = Object.values(keyMap);

    /* ── 2. Canvas zones ──────────────────────────────────────────── */
    document.querySelectorAll('canvas').forEach(function(c, ci) {
        var rect = c.getBoundingClientRect();
        if (rect.width < 10 || rect.height < 10) return;
        result.canvasZones.push({
            index: ci,
            id: c.id || ('canvas_' + ci),
            x: Math.round(rect.left),
            y: Math.round(rect.top),
            w: Math.round(rect.width),
            h: Math.round(rect.height),
            hasClick: c.onclick != null,
            hasMouse: (window.__gmapper_events||[]).some(function(ev){
                return (ev.type==='mousedown'||ev.type==='click') && (ev.tag==='canvas'||ev.id===c.id);
            }),
            hasTouch: (window.__gmapper_events||[]).some(function(ev){
                return (ev.type==='touchstart'||ev.type==='touchend') && (ev.tag==='canvas'||ev.id===c.id);
            })
        });
    });

    /* ── 3. Clickable / interactive elements ─────────────────────── */
    var interactiveSel = 'button, [role="button"], a[href], input[type="button"], ' +
        'input[type="submit"], .btn, .button, [onclick], [class*="btn"], [class*="control"],' +
        '[class*="arrow"], [class*="move"], [class*="action"], [class*="key"]';
    var seen = {};
    document.querySelectorAll(interactiveSel).forEach(function(el) {
        var rect = el.getBoundingClientRect();
        if (rect.width < 4 || rect.height < 4) return;
        var text = (el.textContent || el.value || el.getAttribute('aria-label') || el.title || '').trim().substring(0,40);
        var key = text + '|' + Math.round(rect.left) + '|' + Math.round(rect.top);
        if (seen[key]) return;
        seen[key] = true;
        result.clickableElements.push({
            tag: el.tagName.toLowerCase(),
            text: text,
            id: el.id || '',
            className: (el.className && typeof el.className==='string') ? el.className.substring(0,80) : '',
            x: Math.round(rect.left),
            y: Math.round(rect.top),
            w: Math.round(rect.width),
            h: Math.round(rect.height),
            href: el.href || ''
        });
    });

    /* ── 4. Touch zones ───────────────────────────────────────────── */
    var touchEls = (window.__gmapper_events||[]).filter(function(ev){
        return ev.type==='touchstart'||ev.type==='touchend'||ev.type==='touchmove'||ev.type==='pointerdown';
    });
    touchEls.forEach(function(ev) {
        if (ev.id || ev.className) {
            var sel = ev.id ? '#'+ev.id : '.'+ev.className.split(' ')[0];
            try {
                var el = document.querySelector(sel);
                if (el) {
                    var rect = el.getBoundingClientRect();
                    if (rect.width > 4) {
                        result.touchZones.push({
                            selector: sel,
                            x: Math.round(rect.left),
                            y: Math.round(rect.top),
                            w: Math.round(rect.width),
                            h: Math.round(rect.height),
                            eventType: ev.type
                        });
                    }
                }
            } catch(e) {}
        }
    });

    return JSON.stringify(result);
})();
""".trimIndent()

    /**
     * Alternative remap analysis – focuses on different heuristics.
     * Uses class/id patterns and positional grouping instead of event hooks.
     */
    val REMAP_ANALYSIS_SCRIPT = """
(function() {
    var result = {
        title: document.title || location.hostname,
        url: location.href,
        keyboard: [],
        canvasZones: [],
        clickableElements: [],
        touchZones: [],
        registeredEvents: [],
        remapMode: true,
        timestamp: Date.now()
    };

    /* Re-scan with broader selectors and positional grouping */
    var allInteractive = document.querySelectorAll(
        '*[tabindex], *[onclick], *[onkeydown], *[onmousedown], *[ontouchstart], ' +
        'a, button, input, select, textarea, [role], [data-key], [data-action], ' +
        '[class*="btn"], [class*="key"], [class*="ctrl"], [class*="control"], ' +
        '[class*="pad"], [class*="joystick"], [class*="dpad"], [class*="arrow"], ' +
        '[class*="move"], [class*="walk"], [class*="run"], [class*="jump"], ' +
        '[class*="fire"], [class*="shoot"], [class*="action"], [class*="attack"]'
    );

    var keyLabels = {
        up:['↑','up','north','w','arrowup'],
        down:['↓','down','south','s','arrowdown'],
        left:['←','left','west','a','arrowleft'],
        right:['→','right','east','d','arrowright'],
        action:['space','enter','click','attack','fire','shoot','jump','a','b','x','y'],
        ui:['esc','escape','menu','pause','inventory','bag','map','chat']
    };

    var seen2 = {};
    allInteractive.forEach(function(el) {
        var rect = el.getBoundingClientRect();
        if (rect.width < 4 || rect.height < 4) return;
        var text = (el.textContent || el.value || el.getAttribute('aria-label') || '').trim().toLowerCase().substring(0,40);
        var cls = (el.className && typeof el.className==='string') ? el.className.toLowerCase() : '';
        var combined = text + ' ' + cls + ' ' + (el.id||'').toLowerCase();
        var key = Math.round(rect.left/10) + '|' + Math.round(rect.top/10);
        if (seen2[key]) return;
        seen2[key] = true;

        var cat = 'action';
        if (keyLabels.up.some(function(k){ return combined.indexOf(k)>=0; })) cat='movement';
        else if (keyLabels.down.some(function(k){ return combined.indexOf(k)>=0; })) cat='movement';
        else if (keyLabels.left.some(function(k){ return combined.indexOf(k)>=0; })) cat='movement';
        else if (keyLabels.right.some(function(k){ return combined.indexOf(k)>=0; })) cat='movement';
        else if (keyLabels.ui.some(function(k){ return combined.indexOf(k)>=0; })) cat='ui';

        result.clickableElements.push({
            tag: el.tagName.toLowerCase(),
            text: (el.textContent||'').trim().substring(0,40),
            id: el.id||'',
            className: cls.substring(0,80),
            x: Math.round(rect.left),
            y: Math.round(rect.top),
            w: Math.round(rect.width),
            h: Math.round(rect.height),
            inferredCategory: cat,
            href: el.href||''
        });
    });

    /* Canvas re-scan */
    document.querySelectorAll('canvas').forEach(function(c,ci) {
        var rect = c.getBoundingClientRect();
        if (rect.width < 10) return;
        result.canvasZones.push({
            index: ci, id: c.id||('canvas_'+ci),
            x: Math.round(rect.left), y: Math.round(rect.top),
            w: Math.round(rect.width), h: Math.round(rect.height),
            hasClick: true, hasMouse: true, hasTouch: true
        });
    });

    /* Keyboard re-scan – broader key set */
    var broadKeys = [
        {kc:87,label:'W',cat:'movement'},{kc:65,label:'A',cat:'movement'},
        {kc:83,label:'S',cat:'movement'},{kc:68,label:'D',cat:'movement'},
        {kc:38,label:'↑',cat:'movement'},{kc:40,label:'↓',cat:'movement'},
        {kc:37,label:'←',cat:'movement'},{kc:39,label:'→',cat:'movement'},
        {kc:32,label:'Space',cat:'action'},{kc:13,label:'Enter',cat:'action'},
        {kc:27,label:'Esc',cat:'ui'},{kc:69,label:'E',cat:'interaction'},
        {kc:70,label:'F',cat:'interaction'},{kc:82,label:'R',cat:'action'},
        {kc:81,label:'Q',cat:'ui'},{kc:84,label:'T',cat:'ui'},
        {kc:73,label:'I',cat:'ui'},{kc:77,label:'M',cat:'ui'},
        {kc:49,label:'1',cat:'action'},{kc:50,label:'2',cat:'action'},
        {kc:51,label:'3',cat:'action'},{kc:52,label:'4',cat:'action'}
    ];
    result.keyboard = broadKeys;

    return JSON.stringify(result);
})();
""".trimIndent()
}
