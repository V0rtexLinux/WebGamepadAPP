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
  if (window.__stopMiningFarm)      { window.__stopMiningFarm();      stopped.push('mining'); }
  if (window.__stopPizzaFarm)       { window.__stopPizzaFarm();       stopped.push('pizza'); }
  if (window.__stopFishingFarm)     { window.__stopFishingFarm();     stopped.push('fishing'); }
  if (window.__stopPuffleRoundup)   { window.__stopPuffleRoundup();   stopped.push('puffle'); }
  window.__csFarmRunning     = false;
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
    cartRunning: !!window.__csFarmRunning,
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
        MinigameType.CART_SURFER                     -> CART_SURFER_FARM_AUTO
        MinigameType.MINING, MinigameType.ICE_DRILLING -> MINING_FARM
        MinigameType.PIZZA_JOB, MinigameType.COFFEE_JOB -> PIZZA_JOB_FARM
        MinigameType.FISHING                         -> FISHING_FARM
        MinigameType.PUFFLE_ROUNDUP                  -> PUFFLE_ROUNDUP_FARM
        else -> null
    }
}
