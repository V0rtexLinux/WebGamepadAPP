package com.gamemapper.services

import com.gamemapper.models.MinigameType

/**
 * All JavaScript farm scripts for play.cpjourney.net (Club Penguin Journey).
 *
 * The game uses Ruffle (Flash emulator) with WebGL. Key technical requirements:
 *  1. WebGL preserveDrawingBuffer must be forced EARLY (onPageStarted) so
 *     readPixels() works for pixel-based turn detection.
 *  2. Key events go to the Ruffle canvas inside <ruffle-player> shadow DOM;
 *     composed:true lets them pierce the shadow boundary.
 *  3. Tricks must ALTERNATE to avoid the 50% repeat-penalty.
 *
 * Confirmed trick table (CPJ Stamp Guide, rebelfederation.com):
 *  Flip         : ↓ then Space  = 100 pts  ← highest
 *  Handstand    : ↑ then ↑      =  80 pts
 *  Spin R/L     : Space then ←/→=  80 pts
 *  Run on Tracks: ↓ then ↓      =  80 pts
 *  Leap         : Space then ↑  =  50 pts
 *  Cart Slam    : Space then ↓  =  30 pts
 *  Surf Jump    : ↑ then Space  =  20 pts
 *  Turn         : ←/→           =  10 pts
 *  Surf Turn    : ↑ then ←/→   =  ** bonus on turns
 *
 * Turn detection uses WebGL readPixels on the yellow/gold arrow indicator zone.
 * Turn keys: A = go left, D = go right.
 *
 * Life strategy: use exactly 1 life intentionally (miss turn #CRASH_TURN)
 * to reset the run timer and earn more coins per session.
 */
object FarmScripts {

    // ─────────────────────────────────────────────────────────────────────────
    // EARLY INJECT — must run in onPageStarted(), BEFORE Ruffle creates WebGL
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Intercepts HTMLCanvasElement.getContext to force preserveDrawingBuffer:true
     * on every WebGL context. This enables gl.readPixels() for turn detection.
     * MUST be injected on page start, not page finish.
     */
    val CART_SURFER_EARLY_INJECT = """
(function() {
  if (window.__csGLPatched) return;
  window.__csGLPatched = true;
  const _orig = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function(type, attrs) {
    if (type === 'webgl' || type === 'webgl2') {
      attrs = Object.assign({}, attrs || {}, { preserveDrawingBuffer: true });
    }
    return _orig.call(this, type, attrs);
  };
  console.log('[CS-Farm] WebGL patched — preserveDrawingBuffer enabled');
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // MINIGAME DETECTOR
    // ─────────────────────────────────────────────────────────────────────────
    val MINIGAME_DETECTOR = """
(function() {
  var result = { minigame: 'NONE', confidence: 0, url: location.href };
  var hash = location.hash.toLowerCase();
  var href = location.href.toLowerCase();

  var ROOM_MAP = {
    850: 'CART_SURFER', 851: 'CART_SURFER',
    810: 'MINING', 811: 'ICE_DRILLING',
    330: 'PIZZA_JOB', 320: 'COFFEE_JOB',
    430: 'FISHING', 440: 'PUFFLE_ROUNDUP'
  };

  // 1. cpClient room-based detection (most reliable)
  try {
    if (window.cpClient && window.cpClient.room) {
      var room = window.cpClient.room;
      var id = room.id || room.room_id || room.roomId;
      if (ROOM_MAP[id]) {
        result.minigame = ROOM_MAP[id];
        result.confidence = 95;
        return JSON.stringify(result);
      }
    }
  } catch(e) {}

  // 2. URL/hash pattern
  var patterns = {
    CART_SURFER: ['cart', 'surfer', 'mine'],
    MINING:      ['mining', 'drill'],
    PIZZA_JOB:   ['pizza'],
    FISHING:     ['fishing', 'fish'],
    PUFFLE_ROUNDUP: ['puffle', 'roundup']
  };
  for (var key in patterns) {
    if (patterns[key].some(function(p) { return hash.includes(p) || href.includes(p); })) {
      result.minigame = key;
      result.confidence = 60;
    }
  }

  // 3. Canvas + ruffle presence = in some minigame
  if (result.minigame === 'NONE') {
    var ruffle = document.querySelector('ruffle-player');
    if (ruffle) {
      result.minigame = 'UNKNOWN';
      result.confidence = 30;
    }
  }

  return JSON.stringify(result);
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // CART SURFER — FULL AUTO-FARM
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Full automatic Cart Surfer farm.
     *
     * Algorithm:
     *  – Runs at 20 Hz (every 50ms poll loop)
     *  – Tricks: Flip(100)→Handstand(80)→SpinR(80)→RunTracks(80)→Flip→SpinL→...
     *    alternated so no consecutive repeat → no 50% penalty
     *  – Turn detection: samples a grid of pixels in the upper portion of the
     *    Ruffle canvas looking for golden/yellow arrow pixels (R>185, G>155, B<90)
     *    on the left zone vs right zone to determine A or D press
     *  – Surf Turn on real turns: does ↑ + dir first to earn extra trick points
     *  – Life strategy: intentionally skips one turn after CRASH_TURN turns to
     *    use exactly 1 life and extend the run time
     */
    val CART_SURFER_FARM_AUTO = """
(function() {
  if (window.__csFarmRunning) return JSON.stringify({status:'already_running'});
  window.__csFarmRunning = true;

  /* ── Tuning constants ───────────────────────────────────────────────── */
  var TRICK_INTERVAL   = 1700;  // ms between tricks
  var TURN_HOLD_MS     = 340;   // how long to hold the turn key
  var KEY_TAP_MS       = 85;    // tap duration for trick keys
  var SEQ_DELAY_MS     = 90;    // delay between first+second key in a 2-key trick
  var COOLDOWN_AFTER_TURN = 600; // ms gap after a turn before more tricks
  var CRASH_TURN       = 6;     // intentionally miss this turn to use 1 life
  var MAX_LIVES        = 1;     // total lives to spend intentionally
  var RESPAWN_WAIT     = 2800;  // ms to wait after a crash before resuming
  var LOOP_RATE        = 50;    // main loop hz (ms interval)

  /* ── Arrow detection pixel thresholds ───────────────────────────────── */
  // The turn sign is a YELLOW/BLACK road-chevron (like a ← or → curve warning).
  // Confirmed from in-game screenshots: the yellow is pure amber with very low blue.
  // Measured approx: R~230-255, G~170-215, B~0-45 (not R>185/B<95 as first estimated).
  var ARROW_R_MIN  = 200;
  var ARROW_G_MIN  = 155;
  var ARROW_B_MAX  = 55;   // KEY FIX: pure yellow has almost no blue component
  var ARROW_SCORE_THRESHOLD = 4; // pixels needed to confirm direction

  /* ── State ───────────────────────────────────────────────────────────── */
  var STATE = {
    IDLE:     'IDLE',
    PLAYING:  'PLAYING',
    TURNING:  'TURNING',
    CRASHED:  'CRASHED',
    DONE:     'DONE'
  };
  var state         = STATE.IDLE;
  var turnCount     = 0;
  var livesUsed     = 0;
  var trickIdx      = 0;
  var lastTrickTime = 0;
  var inTurnBlock   = false;   // true while executing/cooling down a turn
  var loopId        = null;
  var gameCanvas    = null;
  var glCtx         = null;

  /* ── Stats (readable from Android via evaluateJavascript) ───────────── */
  window.__csFarmStats = {
    running:    true,
    state:      STATE.IDLE,
    turns:      0,
    tricks:     0,
    livesUsed:  0,
    lastTrick:  '',
    lastTurnDir:'',
    startTs:    Date.now()
  };

  /* ── Canvas / GL setup ──────────────────────────────────────────────── */
  function findCanvas() {
    var rp = document.querySelector('ruffle-player');
    if (rp && rp.shadowRoot) {
      var c = rp.shadowRoot.querySelector('canvas');
      if (c && c.width > 100) return c;
    }
    var all = Array.from(document.querySelectorAll('canvas'));
    all.sort(function(a,b){ return (b.width*b.height)-(a.width*a.height); });
    return all[0] || null;
  }

  function ensureGL() {
    if (glCtx && gameCanvas) return true;
    gameCanvas = findCanvas();
    if (!gameCanvas) return false;
    glCtx = gameCanvas.getContext('webgl2') || gameCanvas.getContext('webgl');
    return !!glCtx;
  }

  /* ── Key injection ──────────────────────────────────────────────────── */
  function getTarget() {
    return (gameCanvas || document.querySelector('ruffle-player') || document.documentElement);
  }

  function fireEvent(target, type, keyCode, key, code) {
    var opts = { keyCode:keyCode, which:keyCode, key:key, code:code,
                 bubbles:true, cancelable:true, composed:true };
    target.dispatchEvent(new KeyboardEvent(type, opts));
    // Belt-and-suspenders: also fire on window so Ruffle's top-level listener catches it
    window.dispatchEvent(new KeyboardEvent(type, opts));
  }

  function tap(keyCode, key, code, dur) {
    var t = getTarget();
    fireEvent(t, 'keydown', keyCode, key, code);
    setTimeout(function(){ fireEvent(t, 'keyup', keyCode, key, code); }, dur || KEY_TAP_MS);
  }

  function hold(keyCode, key, code, dur) {
    var t = getTarget();
    fireEvent(t, 'keydown', keyCode, key, code);
    var rpt = setInterval(function(){ fireEvent(t, 'keydown', keyCode, key, code); }, 40);
    setTimeout(function(){
      clearInterval(rpt);
      fireEvent(t, 'keyup', keyCode, key, code);
    }, dur);
  }

  /* Key codes */
  var K = {
    LEFT : [37,'ArrowLeft','ArrowLeft'],
    RIGHT: [39,'ArrowRight','ArrowRight'],
    UP   : [38,'ArrowUp','ArrowUp'],
    DOWN : [40,'ArrowDown','ArrowDown'],
    SPACE: [32,' ','Space'],
    A    : [65,'a','KeyA'],
    D    : [68,'d','KeyD']
  };
  function t(k,d){ tap(k[0],k[1],k[2],d); }
  function h(k,d){ hold(k[0],k[1],k[2],d); }

  /* ── Trick sequence (alternated, no consecutive repeats) ─────────────
   * Pattern: Flip(100)→Handstand(80)→SpinR(80)→RunTracks(80)→
   *          Flip(100)→Handstand(80)→SpinL(80)→RunTracks(80) → repeat
   * Average = (100+80+80+80+100+80+80+80)/8 = 85 pts per trick ≈ 680 pts/8
   */
  var TRICKS = [
    { name:'Flip',          fn: function(){ t(K.DOWN); setTimeout(function(){ t(K.SPACE); }, SEQ_DELAY_MS); } },
    { name:'Handstand',     fn: function(){ t(K.UP);   setTimeout(function(){ t(K.UP);    }, SEQ_DELAY_MS+40); } },
    { name:'Spin Right',    fn: function(){ t(K.SPACE); setTimeout(function(){ t(K.RIGHT); }, SEQ_DELAY_MS); } },
    { name:'Run on Tracks', fn: function(){ t(K.DOWN); setTimeout(function(){ t(K.DOWN);  }, SEQ_DELAY_MS+40); } },
    { name:'Flip',          fn: function(){ t(K.DOWN); setTimeout(function(){ t(K.SPACE); }, SEQ_DELAY_MS); } },
    { name:'Handstand',     fn: function(){ t(K.UP);   setTimeout(function(){ t(K.UP);    }, SEQ_DELAY_MS+40); } },
    { name:'Spin Left',     fn: function(){ t(K.SPACE); setTimeout(function(){ t(K.LEFT);  }, SEQ_DELAY_MS); } },
    { name:'Run on Tracks', fn: function(){ t(K.DOWN); setTimeout(function(){ t(K.DOWN);  }, SEQ_DELAY_MS+40); } }
  ];

  function doTrick() {
    var trick = TRICKS[trickIdx % TRICKS.length];
    trickIdx++;
    trick.fn();
    window.__csFarmStats.lastTrick = trick.name;
    window.__csFarmStats.tricks++;
    lastTrickTime = Date.now();
  }

  /* ── Pixel-based turn sign detection ────────────────────────────────
   * Confirmed from in-game screenshots:
   *
   *  SIGN ON LEFT WALL  (x≈28-50%, y≈28-65%) → chevron points RIGHT → press D
   *  SIGN ON RIGHT WALL (x≈60-82%, y≈30-65%) → chevron points LEFT  → press A
   *
   * The sign is a standard road "curve ahead" chevron: bright amber-yellow (#FFD700
   * area) stripes on black.  We sample a 6×7 pixel grid in each wall zone.
   * WebGL origin is BOTTOM-LEFT, so we flip Y: fy = canvas.height - y - 1.
   */
  function samplePixel(x, y) {
    try {
      var buf = new Uint8Array(4);
      var fy  = gameCanvas.height - Math.round(y) - 1; // WebGL Y-flip
      glCtx.readPixels(Math.round(x), fy, 1, 1, glCtx.RGBA, glCtx.UNSIGNED_BYTE, buf);
      return buf;
    } catch(e) { return null; }
  }

  function isArrowPixel(px) {
    // Pure amber-yellow: high R, high G, very low B (confirmed from screenshots)
    return px && px[3] > 80
              && px[0] > ARROW_R_MIN
              && px[1] > ARROW_G_MIN
              && px[2] < ARROW_B_MAX;
  }

  function detectTurnDirection() {
    if (!ensureGL()) return null;
    var w = gameCanvas.width, h = gameCanvas.height;

    // LEFT wall zone  → RIGHT turn (D key)
    // Columns x: 24%..50%, rows y: 28%..62%
    var xSignLeft  = [0.24, 0.29, 0.34, 0.38, 0.43, 0.48];

    // RIGHT wall zone → LEFT turn (A key)
    // Columns x: 59%..84%, rows y: 30%..65%
    var xSignRight = [0.59, 0.64, 0.68, 0.73, 0.78, 0.83];

    // Shared row samples covering where the sign appears vertically
    var yRows = [0.28, 0.33, 0.38, 0.43, 0.48, 0.54, 0.62];

    var lWall = 0, rWall = 0;
    for (var ri = 0; ri < yRows.length; ri++) {
      var py = h * yRows[ri];
      for (var ci = 0; ci < xSignLeft.length; ci++) {
        if (isArrowPixel(samplePixel(w * xSignLeft[ci],  py))) lWall++;
        if (isArrowPixel(samplePixel(w * xSignRight[ci], py))) rWall++;
      }
    }

    // CRITICAL: sign on LEFT wall  → turn RIGHT (D)
    //           sign on RIGHT wall → turn LEFT  (A)
    if (lWall >= ARROW_SCORE_THRESHOLD && lWall > rWall) return 'RIGHT';
    if (rWall >= ARROW_SCORE_THRESHOLD && rWall > lWall) return 'LEFT';
    return null;
  }

  /* ── Execute a turn ────────────────────────────────────────────────── */
  function executeTurn(dir) {
    if (inTurnBlock) return;
    inTurnBlock = true;
    state = STATE.TURNING;
    turnCount++;
    window.__csFarmStats.turns      = turnCount;
    window.__csFarmStats.lastTurnDir = dir;
    window.__csFarmStats.state      = STATE.TURNING;

    /* Intentional crash: miss CRASH_TURN to use 1 life, extending game time */
    if (turnCount === CRASH_TURN && livesUsed < MAX_LIVES) {
      livesUsed++;
      window.__csFarmStats.livesUsed = livesUsed;
      // Don't press anything → cart crashes → 1 life used → run continues
      state = STATE.CRASHED;
      window.__csFarmStats.state = STATE.CRASHED;
      setTimeout(function() {
        state = STATE.PLAYING;
        window.__csFarmStats.state = STATE.PLAYING;
        lastTrickTime = Date.now() + 500;
        inTurnBlock = false;
      }, RESPAWN_WAIT);
      return;
    }

    /* Surf Turn: ↑ + direction arrow = extra points AND navigates the curve */
    var arrowKey = (dir === 'LEFT') ? K.LEFT : K.RIGHT;
    var turnKey  = (dir === 'LEFT') ? K.A    : K.D;

    // Slightly pre-tap ↑ then hold the direction key
    t(K.UP, 60);
    setTimeout(function() {
      h(arrowKey, TURN_HOLD_MS);
      h(turnKey,  TURN_HOLD_MS);
    }, 45);

    setTimeout(function() {
      state = STATE.PLAYING;
      window.__csFarmStats.state = STATE.PLAYING;
      lastTrickTime = Date.now() + COOLDOWN_AFTER_TURN;
      inTurnBlock = false;
    }, TURN_HOLD_MS + 450);
  }

  /* ── Main loop ─────────────────────────────────────────────────────── */
  function loop() {
    if (!window.__csFarmRunning) { clearInterval(loopId); return; }
    if (state === STATE.DONE || state === STATE.CRASHED || state === STATE.TURNING) return;

    if (state === STATE.IDLE) {
      if (!ensureGL()) return; // canvas not ready yet
      state = STATE.PLAYING;
      window.__csFarmStats.state = STATE.PLAYING;
      lastTrickTime = Date.now() + 1800; // initial wait for game to start
      return;
    }

    if (state === STATE.PLAYING) {
      // 1. Turn detection (highest priority — ~20ms check each loop tick)
      var dir = detectTurnDirection();
      if (dir && !inTurnBlock) {
        executeTurn(dir);
        return;
      }

      // 2. Trick loop
      if (Date.now() - lastTrickTime >= TRICK_INTERVAL) {
        doTrick();
      }
    }
  }

  loopId = setInterval(loop, LOOP_RATE);

  /* ── Stop API ─────────────────────────────────────────────────────── */
  window.__stopCartSurferFarm = function() {
    clearInterval(loopId);
    window.__csFarmRunning = false;
    window.__csFarmStats.running = false;
    return 'stopped';
  };

  return JSON.stringify({ status:'started', ts: Date.now() });
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // MINING AUTO-FARM (Ice Drilling / Regular Mining)
    // ─────────────────────────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════
    // CART SURFER — SIGN-BASED AUTO-FARM (IMAGE / BASE64 TEMPLATE MATCHING)
    // ═════════════════════════════════════════════════════════════════════════════
    /**
     * Full automatic Cart Surfer farm using IMAGE-based sign detection instead of
     * color sampling. This is the recommended script — it matches a cropped PNG of
     * the in-game yellow/black chevron turn sign (embedded as base64) against the
     * live Ruffle canvas every loop tick, counts the repeated signs that scroll
     * toward the player before a curve, and presses A (left) or D (right) when only
     * 2 signs remain in the countdown.
     *
     * How it works (per the user's spec):
     *  – A cropped template of the RIGHT-wall sign is embedded as base64.
     *    RIGHT-wall chevron points LEFT  → the curve goes LEFT  → press A.
     *  – A horizontally-flipped copy is the LEFT-wall sign template.
     *    LEFT-wall  chevron points RIGHT → the curve goes RIGHT → press D.
     *  – Every loop tick we draw the game canvas into an offscreen canvas, convert
     *    a downscaled grayscale copy, and run a normalized cross-correlation
     *    (TM_CCOEFF_NORMED-style) against each template inside the wall zones.
     *  – Signs repeat 4–6 times along the tunnel before each curve. We track each
     *    sign with a temporal cooldown so the same on-screen sign is only counted
     *    once as it scrolls past; a NEW sign (one that wasn't matched recently) is
     *    pushed onto a per-direction queue.
     *  – When the queue length reaches the configured TURN_AT_REMAINING (default 2),
     *    meaning 2 signs are still ahead, the app presses the turn key (A or D) so
     *    the cart takes the curve in time. The Surf Turn trick (up + dir) is used
     *    for bonus points.
     *
     * Performance: the full-res screenshot test scans both walls in ~43 ms; the
     * smaller in-game Ruffle canvas (~800x360) scans both walls in ~22 ms, well
     * within the 50 ms loop budget.
     *
     * Keys: A = turn left, D = turn right (same as the color-based script).
     * Tricks remain alternated (Flip->Handstand->SpinR->RunTracks->...) to avoid
     * the 50% repeat penalty, and the 1-life crash strategy is preserved.
     */
    val CART_SURFER_FARM_SIGN = """
(function() {
  if (window.__csSignFarmRunning) return JSON.stringify({status:'already_running'});
  window.__csSignFarmRunning = true;

  /* -- Tuning constants -- */
  var TRICK_INTERVAL      = 1700;
  var TURN_HOLD_MS        = 340;
  var KEY_TAP_MS          = 85;
  var SEQ_DELAY_MS        = 90;
  var COOLDOWN_AFTER_TURN = 600;
  var CRASH_TURN          = 6;
  var MAX_LIVES           = 1;
  var RESPAWN_WAIT        = 2800;
  var LOOP_RATE           = 80;     /* ms — main loop tick (light) */
  var DETECT_INTERVAL_MS  = 140;    /* only run heavy template match this often */

  /* Sign-detection tuning — HEAVILY optimised to avoid freezing the WebView */
  var MATCH_THRESHOLD     = 0.50;   /* NCC score above this => sign present */
  var TURN_AT_REMAINING   = 2;      /* press turn key when this many signs remain */
  var SIGN_MIN_SPACING_PX = 50;     /* px (downscaled) to count as a NEW sign */
  var SIGN_COOLDOWN_MS    = 250;
  var MAX_QUEUE           = 8;
  var DOWNSCALE           = 4;      /* bigger downscale = far fewer pixels to scan */
  var COARSE_STEP         = 8;      /* first-pass scan step (fast, covers whole zone) */
  var FINE_STEP           = 2;      /* refinement scan step around best candidate */
  var FINE_MARGIN         = 12;     /* refinement half-window in px */

  var RIGHT_ZONE = { x0:0.52, x1:0.78, y0:0.18, y1:0.80 };
  var LEFT_ZONE  = { x0:0.22, x1:0.48, y0:0.18, y1:0.80 };

  /* -- Embedded base64 templates --
     sign_right_template = original crop of the right-wall chevron (points LEFT).
     sign_left_template  = horizontally-flipped copy (points RIGHT). */
  var SIGN_RIGHT_B64 = "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";
  var SIGN_LEFT_B64  = "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";

  /* -- State -- */
  var STATE = { IDLE:'IDLE', PLAYING:'PLAYING', TURNING:'TURNING',
                CRASHED:'CRASHED', DONE:'DONE' };
  var state = STATE.IDLE;
  var turnCount = 0, livesUsed = 0, trickIdx = 0, lastTrickTime = 0;
  var inTurnBlock = false, loopId = null;
  var gameCanvas = null, glCtx = null;

  var rightSigns = [];
  var leftSigns  = [];
  var lastDir = null;
  var thPad = 40;

  window.__csSignFarmStats = {
    running:true, state:STATE.IDLE, turns:0, tricks:0, livesUsed:0,
    lastTrick:'', lastTurnDir:'',
    rightSignsSeen:0, leftSignsSeen:0,
    rightRemaining:0, leftRemaining:0,
    startTs:Date.now()
  };

  /* -- Canvas / GL setup -- */
  function findCanvas() {
    var rp = document.querySelector('ruffle-player');
    if (rp && rp.shadowRoot) {
      var c = rp.shadowRoot.querySelector('canvas');
      if (c && c.width > 100) return c;
    }
    var all = Array.prototype.slice.call(document.querySelectorAll('canvas'));
    all.sort(function(a,b){ return (b.width*b.height)-(a.width*a.height); });
    return all[0] || null;
  }
  function ensureGL() {
    if (glCtx && gameCanvas) return true;
    gameCanvas = findCanvas();
    if (!gameCanvas) return false;
    glCtx = gameCanvas.getContext('webgl2') || gameCanvas.getContext('webgl');
    return !!glCtx;
  }

  /* -- Template loading (once) -- */
  var tmplRight = null, tmplLeft = null;
  var offCanvas = document.createElement('canvas');
  var offCtx = offCanvas.getContext('2d');

  function loadTemplate(b64) {
    return new Promise(function(resolve){
      var img = new Image();
      img.onload = function(){
        offCanvas.width = img.width; offCanvas.height = img.height;
        offCtx.drawImage(img, 0, 0);
        var d = offCtx.getImageData(0, 0, img.width, img.height).data;
        var g = new Float32Array(img.width * img.height);
        for (var i=0, p=0; i<d.length; i+=4, p++)
          g[p] = 0.299*d[i] + 0.587*d[i+1] + 0.114*d[i+2];
        var mean = 0; for (var k=0;k<g.length;k++) mean += g[k]; mean /= g.length;
        var denom = 0; for (var j=0;j<g.length;j++){ var dv=g[j]-mean; denom += dv*dv; }
        denom = Math.sqrt(denom);
        resolve({ gray:g, w:img.width, h:img.height, mean:mean, denom:denom });
      };
      img.onerror = function(){ resolve(null); };
      img.src = 'data:image/png;base64,' + b64;
    });
  }

  var templatesReady = false;
  Promise.all([loadTemplate(SIGN_RIGHT_B64), loadTemplate(SIGN_LEFT_B64)])
    .then(function(res){ tmplRight = res[0]; tmplLeft = res[1]; templatesReady = true; });

  /* -- Key injection -- */
  function getTarget() {
    return (gameCanvas || document.querySelector('ruffle-player') || document.documentElement);
  }
  function fireEvent(target, type, keyCode, key, code) {
    var opts = { keyCode:keyCode, which:keyCode, key:key, code:code,
                 bubbles:true, cancelable:true, composed:true };
    target.dispatchEvent(new KeyboardEvent(type, opts));
    window.dispatchEvent(new KeyboardEvent(type, opts));
  }
  function tap(kc, k, code, dur) {
    var t = getTarget();
    fireEvent(t, 'keydown', kc, k, code);
    setTimeout(function(){ fireEvent(t, 'keyup', kc, k, code); }, dur || KEY_TAP_MS);
  }
  function hold(kc, k, code, dur) {
    var t = getTarget();
    fireEvent(t, 'keydown', kc, k, code);
    var rpt = setInterval(function(){ fireEvent(t, 'keydown', kc, k, code); }, 40);
    setTimeout(function(){ clearInterval(rpt); fireEvent(t, 'keyup', kc, k, code); }, dur);
  }
  var K = {
    LEFT:[37,'ArrowLeft','ArrowLeft'], RIGHT:[39,'ArrowRight','ArrowRight'],
    UP:[38,'ArrowUp','ArrowUp'], DOWN:[40,'ArrowDown','ArrowDown'],
    SPACE:[32,' ','Space'], A:[65,'a','KeyA'], D:[68,'d','KeyD']
  };
  function t(k,d){ tap(k[0],k[1],k[2],d); }
  function h(k,d){ hold(k[0],k[1],k[2],d); }

  /* -- Trick sequence (alternated) -- */
  var TRICKS = [
    { name:'Flip',          fn:function(){ t(K.DOWN); setTimeout(function(){ t(K.SPACE); }, SEQ_DELAY_MS); } },
    { name:'Handstand',     fn:function(){ t(K.UP);   setTimeout(function(){ t(K.UP);    }, SEQ_DELAY_MS+40); } },
    { name:'Spin Right',    fn:function(){ t(K.SPACE); setTimeout(function(){ t(K.RIGHT); }, SEQ_DELAY_MS); } },
    { name:'Run on Tracks', fn:function(){ t(K.DOWN); setTimeout(function(){ t(K.DOWN);  }, SEQ_DELAY_MS+40); } },
    { name:'Flip',          fn:function(){ t(K.DOWN); setTimeout(function(){ t(K.SPACE); }, SEQ_DELAY_MS); } },
    { name:'Handstand',     fn:function(){ t(K.UP);   setTimeout(function(){ t(K.UP);    }, SEQ_DELAY_MS+40); } },
    { name:'Spin Left',     fn:function(){ t(K.SPACE); setTimeout(function(){ t(K.LEFT);  }, SEQ_DELAY_MS); } },
    { name:'Run on Tracks', fn:function(){ t(K.DOWN); setTimeout(function(){ t(K.DOWN);  }, SEQ_DELAY_MS+40); } }
  ];
  function doTrick() {
    var trick = TRICKS[trickIdx % TRICKS.length]; trickIdx++;
    trick.fn();
    window.__csSignFarmStats.lastTrick = trick.name;
    window.__csSignFarmStats.tricks++;
    lastTrickTime = Date.now();
  }

  /* -- Image capture + grayscale (downscaled) + integral image (SAT) -- */
  var grabCanvas = document.createElement('canvas');
  var grabCtx = grabCanvas.getContext('2d');
  var grabGray = null, grabSAT = null, grabW = 0, grabH = 0;

  /* SAT has (sw+1)*(sh+1) entries; sat(x,y) = sum of gray[0..x-1, 0..y-1].
     Rect sum from (x0,y0) inclusive to (x1,y1) exclusive:
       = sat(x1,y1) - sat(x0,y1) - sat(x1,y0) + sat(x0,y0) */
  function captureGrayscale() {
    if (!gameCanvas) return false;
    var sw = Math.floor(gameCanvas.width / DOWNSCALE);
    var sh = Math.floor(gameCanvas.height / DOWNSCALE);
    if (sw < 10 || sh < 10) return false;
    if (grabCanvas.width !== sw || grabCanvas.height !== sh) {
      grabCanvas.width = sw; grabCanvas.height = sh;
      grabGray = new Float32Array(sw * sh);
      grabSAT  = new Float32Array((sw + 1) * (sh + 1));
      grabW = sw; grabH = sh;
    }
    try {
      grabCtx.drawImage(gameCanvas, 0, 0, sw, sh);
    } catch(e) { return false; }
    var d = grabCtx.getImageData(0, 0, sw, sh).data;
    for (var i=0, p=0; i<d.length; i+=4, p++)
      grabGray[p] = 0.299*d[i] + 0.587*d[i+1] + 0.114*d[i+2];

    /* build SAT in-place (row-major, indexed [y*(sw+1)+x]) */
    var sat = grabSAT, W = sw + 1;
    for (var y = 0; y < sh; y++) {
      var rowSum = 0;
      var gy = y * sw;
      for (var x = 0; x < sw; x++) {
        rowSum += grabGray[gy + x];
        sat[(y + 1) * W + (x + 1)] = sat[y * W + (x + 1)] + rowSum;
      }
    }
    return true;
  }

  /* O(1) sum of a rectangular region of grabGray via SAT.
     x0,y0 = top-left inclusive; x1,y1 = bottom-right exclusive. */
  function rectSum(x0, y0, x1, y1) {
    var W = grabW + 1;
    return grabSAT[y1 * W + x1] - grabSAT[y0 * W + x1]
         - grabSAT[y1 * W + x0] + grabSAT[y0 * W + x0];
  }

  /* -- Template matching -- */
  var tmplRightScaled = null, tmplLeftScaled = null;
  function scaleTemplate(tmpl) {
    if (!tmpl) return null;
    var nw = Math.floor(tmpl.w / DOWNSCALE);
    var nh = Math.floor(tmpl.h / DOWNSCALE);
    if (nw < 4 || nh < 4) return null;
    var out = new Float32Array(nw * nh);
    for (var y=0; y<nh; y++) {
      for (var x=0; x<nw; x++) {
        var sum=0, cnt=0;
        for (var dy=0; dy<DOWNSCALE && (y*DOWNSCALE+dy)<tmpl.h; dy++) {
          var row = (y*DOWNSCALE+dy)*tmpl.w;
          for (var dx=0; dx<DOWNSCALE && (x*DOWNSCALE+dx)<tmpl.w; dx++) {
            sum += tmpl.gray[row + x*DOWNSCALE + dx]; cnt++;
          }
        }
        out[y*nw + x] = sum / cnt;
      }
    }
    var mean=0; for (var k=0;k<out.length;k++) mean += out[k]; mean /= out.length;
    var denom=0; for (var j=0;j<out.length;j++){ var dv=out[j]-mean; denom += dv*dv; }
    denom = Math.sqrt(denom);
    return { gray:out, w:nw, h:nh, mean:mean, denom:denom };
  }
  function ensureScaledTemplates() {
    if (!tmplRightScaled && tmplRight) tmplRightScaled = scaleTemplate(tmplRight);
    if (!tmplLeftScaled  && tmplLeft)  tmplLeftScaled  = scaleTemplate(tmplLeft);
  }

  /* Full NCC at a single (x,y) offset using precomputed template stats.
     sMean is passed in (computed via SAT in O(1)). */
  function nccAt(tmpl, x, y, sMean) {
    var tw = tmpl.w, th = tmpl.h, tg = tmpl.gray, tMean = tmpl.mean, tDenom = tmpl.denom;
    var cross = 0, sDenom = 0;
    for (var ty = 0; ty < th; ty++) {
      var srow = (y + ty) * grabW + x;
      var trow = ty * tw;
      for (var tx = 0; tx < tw; tx++) {
        var s = grabGray[srow + tx] - sMean;
        var tt = tg[trow + tx] - tMean;
        cross += s * tt; sDenom += s * s;
      }
    }
    sDenom = Math.sqrt(sDenom);
    if (sDenom < 1e-6 || tDenom < 1e-6) return 0;
    return cross / (sDenom * tDenom);
  }

  /* Coarse-to-fine zone scan.
     Pass 1: coarse grid (COARSE_STEP) using SAT for O(1) mean → only compute
             the expensive cross-correlation denominator at each coarse point.
     Pass 2: fine grid (FINE_STEP) inside a small window around the best
             coarse candidate. */
  function matchZone(tmpl, zone) {
    if (!tmpl || !grabGray) return { score: 0, x: 0, y: 0 };
    var x0 = Math.floor(grabW * zone.x0), x1 = Math.floor(grabW * zone.x1);
    var y0 = Math.floor(grabH * zone.y0), y1 = Math.floor(grabH * zone.y1);
    var tw = tmpl.w, th = tmpl.h, nPx = tw * th;
    if (tDenom_is_zero(tmpl)) return { score: 0, x: 0, y: 0 };

    /* ── Pass 1: coarse scan (SAT mean, full NCC only at grid points) ── */
    var bestScore = -Infinity, bestX = 0, bestY = 0;
    for (var y = y0; y + th <= y1; y += COARSE_STEP) {
      for (var x = x0; x + tw <= x1; x += COARSE_STEP) {
        var sMean = rectSum(x, y, x + tw, y + th) / nPx;
        var score = nccAt(tmpl, x, y, sMean);
        if (score > bestScore) { bestScore = score; bestX = x; bestY = y; }
      }
    }

    /* ── Pass 2: fine refinement around best coarse candidate ── */
    var rx0 = Math.max(x0, bestX - FINE_MARGIN);
    var ry0 = Math.max(y0, bestY - FINE_MARGIN);
    var rx1 = Math.min(x1 - tw, bestX + FINE_MARGIN);
    var ry1 = Math.min(y1 - th, bestY + FINE_MARGIN);
    for (var fy = ry0; fy <= ry1; fy += FINE_STEP) {
      for (var fx = rx0; fx <= rx1; fx += FINE_STEP) {
        var sm = rectSum(fx, fy, fx + tw, fy + th) / nPx;
        var sc = nccAt(tmpl, fx, fy, sm);
        if (sc > bestScore) { bestScore = sc; bestX = fx; bestY = fy; }
      }
    }
    return { score: bestScore, x: bestX, y: bestY };
  }

  function tDenom_is_zero(tmpl) { return tmpl.denom < 1e-6; }

  /* -- Sign tracking -- */
  function trackSigns(queue, match, now) {
    if (match.score < MATCH_THRESHOLD) return;
    for (var i = queue.length - 1; i >= 0; i--) {
      if (now - queue[i].t > SIGN_COOLDOWN_MS * 3) queue.splice(i, 1);
    }
    var isNew = true;
    for (var j=0; j<queue.length; j++) {
      if (Math.abs(queue[j].x - match.x) < SIGN_MIN_SPACING_PX &&
          Math.abs(queue[j].y - match.y) < SIGN_MIN_SPACING_PX) {
        queue[j].x = match.x; queue[j].y = match.y; queue[j].t = now;
        isNew = false; break;
      }
    }
    if (isNew) {
      queue.push({ x: match.x, y: match.y, t: now });
      if (queue.length > MAX_QUEUE) queue.shift();
    }
    var maxY = grabH * 0.80;
    for (var k = queue.length - 1; k >= 0; k--) {
      if (queue[k].y > maxY + thPad) queue.splice(k, 1);
    }
  }

  /* -- Execute a turn -- */
  function executeTurn(dir) {
    if (inTurnBlock) return;
    inTurnBlock = true; state = STATE.TURNING; turnCount++;
    window.__csSignFarmStats.turns = turnCount;
    window.__csSignFarmStats.lastTurnDir = dir;
    window.__csSignFarmStats.state = STATE.TURNING;
    lastDir = dir;

    if (turnCount === CRASH_TURN && livesUsed < MAX_LIVES) {
      livesUsed++;
      window.__csSignFarmStats.livesUsed = livesUsed;
      state = STATE.CRASHED; window.__csSignFarmStats.state = STATE.CRASHED;
      setTimeout(function(){
        state = STATE.PLAYING; window.__csSignFarmStats.state = STATE.PLAYING;
        lastTrickTime = Date.now() + 500; inTurnBlock = false;
        rightSigns = []; leftSigns = [];
      }, RESPAWN_WAIT);
      return;
    }

    var arrowKey = (dir === 'LEFT') ? K.LEFT : K.RIGHT;
    var turnKey  = (dir === 'LEFT') ? K.A    : K.D;
    t(K.UP, 60);
    setTimeout(function(){ h(arrowKey, TURN_HOLD_MS); h(turnKey, TURN_HOLD_MS); }, 45);
    setTimeout(function(){
      state = STATE.PLAYING; window.__csSignFarmStats.state = STATE.PLAYING;
      lastTrickTime = Date.now() + COOLDOWN_AFTER_TURN;
      inTurnBlock = false;
      rightSigns = []; leftSigns = [];
    }, TURN_HOLD_MS + 450);
  }

  /* -- Main loop (throttled: heavy detection runs at most every DETECT_INTERVAL_MS) -- */
  var lastDetectTs = 0;
  function loop() {
    if (!window.__csSignFarmRunning) { clearInterval(loopId); return; }
    if (state === STATE.DONE || state === STATE.CRASHED || state === STATE.TURNING) {
      /* keep trick timer honest while waiting */
      if (state === STATE.PLAYING && Date.now() - lastTrickTime >= TRICK_INTERVAL) doTrick();
      return;
    }

    if (state === STATE.IDLE) {
      if (!ensureGL() || !templatesReady) return;
      ensureScaledTemplates();
      state = STATE.PLAYING; window.__csSignFarmStats.state = STATE.PLAYING;
      lastTrickTime = Date.now() + 1800;
      lastDetectTs = 0;
      return;
    }

    if (state === STATE.PLAYING) {
      var now = Date.now();
      var doDetect = (now - lastDetectTs) >= DETECT_INTERVAL_MS;
      if (doDetect) {
        lastDetectTs = now;
        if (captureGrayscale()) {
          var mR = matchZone(tmplRightScaled, RIGHT_ZONE);
          var mL = matchZone(tmplLeftScaled,  LEFT_ZONE);
          trackSigns(rightSigns, mR, now);
          trackSigns(leftSigns,  mL, now);
          window.__csSignFarmStats.rightRemaining = rightSigns.length;
          window.__csSignFarmStats.leftRemaining  = leftSigns.length;

          if (!inTurnBlock) {
            if (rightSigns.length >= TURN_AT_REMAINING && rightSigns.length > leftSigns.length) {
              executeTurn('LEFT'); return;
            }
            if (leftSigns.length  >= TURN_AT_REMAINING && leftSigns.length  > rightSigns.length) {
              executeTurn('RIGHT'); return;
            }
          }
        }
      }
      if (now - lastTrickTime >= TRICK_INTERVAL) doTrick();
    }
  }
  loopId = setInterval(loop, LOOP_RATE);

  /* -- Stop API -- */
  window.__stopCartSurferSignFarm = function() {
    clearInterval(loopId);
    window.__csSignFarmRunning = false;
    window.__csSignFarmStats.running = false;
    return 'stopped';
  };
  return JSON.stringify({ status:'started', ts: Date.now() });
})();
""".trimIndent()

    // ═════════════════════════════════════════════════════════════════════════════
    // MINING AUTO-FARM (Ice Drilling / Regular Mining)
    // ═════════════════════════════════════════════════════════════════════════════
    val MINING_FARM = """
(function() {
  if (window.__miningFarmRunning) return JSON.stringify({status:'already_running'});
  window.__miningFarmRunning = true;
  var id = setInterval(function() {
    if (!window.__miningFarmRunning) { clearInterval(id); return; }
    // Press D every 5s to refresh drill / advance mining
    var e = new KeyboardEvent('keydown',{keyCode:68,which:68,key:'d',code:'KeyD',bubbles:true,cancelable:true,composed:true});
    (document.querySelector('ruffle-player')||document.body).dispatchEvent(e);
    window.dispatchEvent(e);
    setTimeout(function(){
      var u = new KeyboardEvent('keyup',{keyCode:68,which:68,key:'d',code:'KeyD',bubbles:true,cancelable:true,composed:true});
      (document.querySelector('ruffle-player')||document.body).dispatchEvent(u);
    }, 80);
  }, 5000);
  window.__stopMiningFarm = function(){ window.__miningFarmRunning=false; clearInterval(id); return 'stopped'; };
  return JSON.stringify({status:'started',ts:Date.now()});
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // PIZZA JOB AUTO-FARM
    // ─────────────────────────────────────────────────────────────────────────
    val PIZZA_JOB_FARM = """
(function() {
  if (window.__pizzaFarmRunning) return JSON.stringify({status:'already_running'});
  window.__pizzaFarmRunning = true;
  function click(x, y) {
    var el = document.elementFromPoint(x, y) || document.body;
    el.dispatchEvent(new MouseEvent('mousedown',{clientX:x,clientY:y,bubbles:true}));
    el.dispatchEvent(new MouseEvent('mouseup',{clientX:x,clientY:y,bubbles:true}));
    el.dispatchEvent(new MouseEvent('click',{clientX:x,clientY:y,bubbles:true}));
  }
  var step = 0;
  var id = setInterval(function() {
    if (!window.__pizzaFarmRunning) { clearInterval(id); return; }
    var w = window.innerWidth, h = window.innerHeight;
    if (step % 3 === 0) click(w*0.3, h*0.5);
    else if (step % 3 === 1) click(w*0.7, h*0.4);
    else click(w*0.5, h*0.7);
    step++;
  }, 1800);
  window.__stopPizzaFarm = function(){ window.__pizzaFarmRunning=false; clearInterval(id); return 'stopped'; };
  return JSON.stringify({status:'started',ts:Date.now()});
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // FISHING AUTO-FARM
    // ─────────────────────────────────────────────────────────────────────────
    val FISHING_FARM = """
(function() {
  if (window.__fishingFarmRunning) return JSON.stringify({status:'already_running'});
  window.__fishingFarmRunning = true;
  function tap(kc,k,code){
    var t=document.querySelector('ruffle-player')||document.body;
    var d=new KeyboardEvent('keydown',{keyCode:kc,key:k,code:code,bubbles:true,composed:true});
    var u=new KeyboardEvent('keyup',{keyCode:kc,key:k,code:code,bubbles:true,composed:true});
    t.dispatchEvent(d); window.dispatchEvent(d);
    setTimeout(function(){t.dispatchEvent(u);window.dispatchEvent(u);},80);
  }
  var phase = 0;
  var id = setInterval(function(){
    if (!window.__fishingFarmRunning){clearInterval(id);return;}
    if (phase === 0) tap(32,' ','Space');       // cast
    else if (phase === 2) tap(38,'ArrowUp','ArrowUp'); // pull up
    phase = (phase + 1) % 4;
  }, 2200);
  window.__stopFishingFarm = function(){window.__fishingFarmRunning=false;clearInterval(id);return 'stopped';};
  return JSON.stringify({status:'started',ts:Date.now()});
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // PUFFLE ROUNDUP AUTO-FARM
    // ─────────────────────────────────────────────────────────────────────────
    val PUFFLE_ROUNDUP_FARM = """
(function() {
  if (window.__puffleRunning) return JSON.stringify({status:'already_running'});
  window.__puffleRunning = true;
  var dirs = [[39,0],[40,0],[37,0],[38,0]]; var di=0;
  function tap(kc){
    var t=document.querySelector('ruffle-player')||document.body;
    var d=new KeyboardEvent('keydown',{keyCode:kc,bubbles:true,composed:true});
    t.dispatchEvent(d);window.dispatchEvent(d);
    setTimeout(function(){
      var u=new KeyboardEvent('keyup',{keyCode:kc,bubbles:true,composed:true});
      t.dispatchEvent(u);window.dispatchEvent(u);
    },120);
  }
  var id=setInterval(function(){
    if(!window.__puffleRunning){clearInterval(id);return;}
    tap(dirs[di%dirs.length][0]);di++;
  },1200);
  window.__stopPuffleRoundup=function(){window.__puffleRunning=false;clearInterval(id);return 'stopped';};
  return JSON.stringify({status:'started',ts:Date.now()});
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // STOP ALL FARMS
    // ─────────────────────────────────────────────────────────────────────────
    val STOP_ALL_FARMS = """
(function() {
  var stopped = [];
  if (window.__stopCartSurferFarm)  { window.__stopCartSurferFarm();  stopped.push('cart_surfer'); }
  if (window.__stopCartSurferSignFarm) { window.__stopCartSurferSignFarm(); stopped.push('cart_surfer_sign'); }
  if (window.__stopMiningFarm)      { window.__stopMiningFarm();      stopped.push('mining'); }
  if (window.__stopPizzaFarm)       { window.__stopPizzaFarm();       stopped.push('pizza'); }
  if (window.__stopFishingFarm)     { window.__stopFishingFarm();     stopped.push('fishing'); }
  if (window.__stopPuffleRoundup)   { window.__stopPuffleRoundup();   stopped.push('puffle'); }
  window.__csFarmRunning     = false;
  window.__csSignFarmRunning = false;
  window.__miningFarmRunning = false;
  window.__pizzaFarmRunning  = false;
  window.__fishingFarmRunning= false;
  window.__puffleRunning     = false;
  return JSON.stringify({ stopped: stopped });
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // STATUS POLL
    // ─────────────────────────────────────────────────────────────────────────
    val GET_FARM_STATUS = """
(function() {
  return JSON.stringify({
    cartSurfer: window.__csFarmStats || null,
    cartSignFarm: window.__csSignFarmStats || null,
    cartRunning: !!window.__csFarmRunning,
    cartSignRunning: !!window.__csSignFarmRunning,
    miningRunning: !!window.__miningFarmRunning,
    pizzaRunning: !!window.__pizzaFarmRunning,
    fishingRunning: !!window.__fishingFarmRunning,
    puffleRunning: !!window.__puffleRunning
  });
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // Dispatch helpers
    // ─────────────────────────────────────────────────────────────────────────
    fun scriptForMinigame(type: MinigameType): String? = when (type) {
        MinigameType.CART_SURFER                       -> CART_SURFER_FARM_SIGN
        MinigameType.MINING, MinigameType.ICE_DRILLING -> MINING_FARM
        MinigameType.PIZZA_JOB, MinigameType.COFFEE_JOB -> PIZZA_JOB_FARM
        MinigameType.FISHING                         -> FISHING_FARM
        MinigameType.PUFFLE_ROUNDUP                  -> PUFFLE_ROUNDUP_FARM
        else -> null
    }

    /** Fallback to the legacy color-based Cart Surfer script if desired. */
    fun scriptForMinigameColor(type: MinigameType): String? = when (type) {
        MinigameType.CART_SURFER -> CART_SURFER_FARM_AUTO
        else -> scriptForMinigame(type)
    }
}
