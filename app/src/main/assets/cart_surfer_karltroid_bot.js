/* ============================================================================
 * Cart Surfer Auto-Farm Bot v3 — "Karltroid Hybrid"
 *
 * Combines the best of three sources:
 *   1. Karltroid/ClubPenguinCartSurfBot — yellow sign detection via color range
 *      in left/right screen regions + cart_slam→drift→back_flip combo sequence
 *   2. jMatthewIsland/Cart-Bot — WebGL pixel sampling + key injection framework
 *   3. CPJ Stamp Guide — optimal trick strategy: alternate Flip(100) & Spin(80),
 *      grind (Down+Arrow) around corners for extra points
 *
 * Key improvements over Cart-Bot v2:
 *   - Samples ENTIRE rectangular regions (not single pixels) on left & right walls
 *   - Detects YELLOW sign color (R≥200, G≥160, B≤120) — the turn indicators
 *   - Uses drift (Down held + Arrow held) for grinding corners = more points
 *   - Alternates Flip (100pts) and Spin (80pts) = highest score combo
 *   - Does cart_slam (Space+Down, 30pts) before each turn to reset combo meter
 *   - Faster loop interval (200ms) for more responsive sign detection
 *
 * Injected via WebView evaluateJavascript(). Runs entirely inside the page.
 * ========================================================================== */
(function() {
  if (window.__csV3FarmRunning) return JSON.stringify({status:'already_running'});
  window.__csV3FarmRunning = true;

  /* ════════════════════════════════════════════════════════════════════════
   *  TUNING CONSTANTS
   * ════════════════════════════════════════════════════════════════════════ */

  var LOOP_INTERVAL       = 200;    /* ms between detection ticks (fast!)     */
  var TURN_DRIFT_MS       = 700;    /* how long to hold drift (Down+Arrow)     */
  var TURN_COOLDOWN_MS    = 300;    /* pause after a turn before next action   */
  var TRICK_INTERVAL_MS   = 1400;   /* ms between tricks on straight sections  */
  var TRICK_GAP_MS        = 120;    /* gap between the two key presses in trick*/
  var KEY_TAP_MS          = 80;     /* default key tap duration                 */
  var KEY_HOLD_REPEAT_MS  = 40;     /* repeat interval for held keys            */

  /* Intentional crash to extend game (use lives strategically) */
  var CRASH_TURN_NUM      = 7;      /* miss the Nth turn intentionally          */
  var MAX_LIVES           = 1;      /* use at most this many lives for crashing */
  var RESPAWN_WAIT_MS     = 3000;   /* wait after crash before resuming         */

  /* ════════════════════════════════════════════════════════════════════════
   *  YELLOW SIGN DETECTION (Karltroid approach)
   * ════════════════════════════════════════════════════════════════════════ */

  /* Karltroid original: YELLOW_LOWER=[217,174,0], YELLOW_UPPER=[255,255,170]
   * We use a slightly wider range to catch signs at various distances/brightness
   * A pixel is "yellow sign" if R>=200 && G>=160 && B<=120 && R>B && G>B
   * (yellow = high R, high G, low B) */
  function isYellowSign(r, g, b) {
    return r >= 200 && g >= 160 && b <= 120 && r > b && g > b;
  }

  /* Detection regions — fractions of canvas dimensions.
   * Karltroid used: left bbox (400,640,1000,955), right bbox (1500,640,2000,955)
   * on a ~2400x1080 screen. That's roughly:
   *   left:  x[0.17→0.42], y[0.59→0.88]
   *   right: x[0.63→0.83], y[0.59→0.88]
   * We sample a sub-grid within these regions (not every pixel) for speed. */

  /* Left wall sign detection region */
  var LEFT_REGION  = { x0:0.15, y0:0.55, x1:0.45, y1:0.90 };
  /* Right wall sign detection region */
  var RIGHT_REGION = { x0:0.55, y0:0.55, x1:0.85, y1:0.90 };

  /* Sample grid density (N x M samples within each region).
   * Higher = more accurate but slower. 8x6=48 samples per side. */
  var GRID_COLS = 8;
  var GRID_ROWS = 6;

  /* How many yellow pixels must be found to trigger a turn.
   * With 48 samples, 2 = ~4% coverage (avoids noise/false positives) */
  var YELLOW_THRESHOLD = 2;

  /* Test spot for game-alive check (same as Cart-Bot) */
  var TEST_SPOT_X_FRAC  = 0.49;
  var TEST_SPOT_Y_FRAC  = 0.43;
  var ALIVE_THRESHOLD   = 80;   /* R+G+B >= this → game screen is visible     */

  /* ════════════════════════════════════════════════════════════════════════
   *  STATE
   * ════════════════════════════════════════════════════════════════════════ */

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
  var inTurnBlock   = false;
  var loopId        = null;
  var gameCanvas    = null;
  var glCtx         = null;

  window.__csV3FarmStats = {
    running:       true,
    state:         STATE.IDLE,
    turns:         0,
    tricks:        0,
    livesUsed:     0,
    lastTurnDir:   '',
    lastTrick:     '',
    lastLeftYel:   0,
    lastRightYel:  0,
    lastTestTotal: 0,
    startTs:       Date.now()
  };

  /* ════════════════════════════════════════════════════════════════════════
   *  CANVAS / WEBGL SETUP
   * ════════════════════════════════════════════════════════════════════════ */

  function findCanvas() {
    /* Ruffle embeds the game canvas inside a <ruffle-player> shadow DOM */
    var rp = document.querySelector('ruffle-player');
    if (rp && rp.shadowRoot) {
      var c = rp.shadowRoot.querySelector('canvas');
      if (c && c.width > 100) return c;
    }
    /* Fallback: find the largest canvas on the page */
    var all = Array.prototype.slice.call(document.querySelectorAll('canvas'));
    all.sort(function(a, b) { return (b.width * b.height) - (a.width * a.height); });
    return all[0] || null;
  }

  function ensureGL() {
    if (glCtx && gameCanvas && gameCanvas.width > 0) return true;
    gameCanvas = findCanvas();
    if (!gameCanvas) return false;
    glCtx = gameCanvas.getContext('webgl2') || gameCanvas.getContext('webgl');
    return !!glCtx;
  }

  /* ════════════════════════════════════════════════════════════════════════
   *  KEY INJECTION (KeyboardEvent dispatch — works in Ruffle/WebView)
   * ════════════════════════════════════════════════════════════════════════ */

  function getTarget() {
    return gameCanvas || document.querySelector('ruffle-player') || document.documentElement;
  }

  function fireEvent(target, type, keyCode, key, code) {
    var opts = {
      keyCode:   keyCode,
      which:     keyCode,
      key:       key,
      code:      code,
      bubbles:   true,
      cancelable:true,
      composed:  true
    };
    target.dispatchEvent(new KeyboardEvent(type, opts));
    window.dispatchEvent(new KeyboardEvent(type, opts));
  }

  /* Tap: press + release after dur ms */
  function tap(kc, k, code, dur) {
    var tg = getTarget();
    fireEvent(tg, 'keydown', kc, k, code);
    setTimeout(function() { fireEvent(tg, 'keyup', kc, k, code); }, dur || KEY_TAP_MS);
  }

  /* Hold: press, repeat every 40ms, release after dur ms */
  function hold(kc, k, code, dur) {
    var tg = getTarget();
    fireEvent(tg, 'keydown', kc, k, code);
    var rpt = setInterval(function() { fireEvent(tg, 'keydown', kc, k, code); }, KEY_HOLD_REPEAT_MS);
    setTimeout(function() {
      clearInterval(rpt);
      fireEvent(tg, 'keyup', kc, k, code);
    }, dur);
  }

  /* Key definitions: [keyCode, keyName, codeName] */
  var K = {
    LEFT:  [37, 'ArrowLeft',  'ArrowLeft'],
    RIGHT: [39, 'ArrowRight', 'ArrowRight'],
    UP:    [38, 'ArrowUp',    'ArrowUp'],
    DOWN:  [40, 'ArrowDown',  'ArrowDown'],
    SPACE: [32, ' ',          'Space']
  };

  function t(k, d) { tap(k[0], k[1], k[2], d); }
  function h(k, d) { hold(k[0], k[1], k[2], d); }

  /* ════════════════════════════════════════════════════════════════════════
   *  PIXEL SAMPLING (WebGL readPixels with Y-flip)
   * ════════════════════════════════════════════════════════════════════════ */

  /* Sample a single pixel at (x, y) in canvas coordinates.
   * Returns Uint8Array(4) = [R, G, B, A] or null on error.
   * WebGL Y is flipped relative to screen Y. */
  function samplePixel(x, y) {
    try {
      var buf = new Uint8Array(4);
      var fy = gameCanvas.height - Math.round(y) - 1;
      glCtx.readPixels(Math.round(x), fy, 1, 1, glCtx.RGBA, glCtx.UNSIGNED_BYTE, buf);
      return buf;
    } catch (e) { return null; }
  }

  function pixelTotal(px) {
    if (!px) return 0;
    return px[0] + px[1] + px[2];
  }

  /* ════════════════════════════════════════════════════════════════════════
   *  YELLOW SIGN REGION DETECTION (Karltroid core logic)
   * ════════════════════════════════════════════════════════════════════════ */

  /* Count yellow pixels in a rectangular region by sampling a grid.
   * Returns the count of yellow pixels found. */
  function countYellowInRegion(region) {
    var w   = gameCanvas.width;
    var hgt = gameCanvas.height;
    var x0  = Math.round(w * region.x0);
    var y0  = Math.round(hgt * region.y0);
    var x1  = Math.round(w * region.x1);
    var y1  = Math.round(hgt * region.y1);
    var stepX = Math.max(1, Math.round((x1 - x0) / GRID_COLS));
    var stepY = Math.max(1, Math.round((y1 - y0) / GRID_ROWS));
    var count = 0;

    for (var yy = y0; yy <= y1; yy += stepY) {
      for (var xx = x0; xx <= x1; xx += stepX) {
        var px = samplePixel(xx, yy);
        if (px && isYellowSign(px[0], px[1], px[2])) {
          count++;
        }
      }
    }
    return count;
  }

  /* ════════════════════════════════════════════════════════════════════════
   *  TRICK SEQUENCE (optimal points: Flip=100, Spin=80)
   * ════════════════════════════════════════════════════════════════════════ */

  /* CPJ trick table (from stamp guide):
   *   Flip       = Down + Space   → 100 pts (BEST)
   *   Spin       = Space + L/R    →  80 pts
   *   Handstand  = Up + Up        →  80 pts
   *   RunTracks  = Down + Down    →  80 pts
   *   Leap       = Space + Up     →  50 pts
   *   CartSlam   = Space + Down   →  30 pts
   *   Jump       = Space          →   0 pts
   *
   * Strategy: alternate Flip and Spin for maximum points.
   * Each trick = two key presses with a small gap between them. */

  var TRICKS = [
    { name: 'Flip',    fn: function() { t(K.DOWN,  KEY_TAP_MS); setTimeout(function(){ t(K.SPACE, KEY_TAP_MS); }, TRICK_GAP_MS); } },
    { name: 'SpinR',   fn: function() { t(K.SPACE, KEY_TAP_MS); setTimeout(function(){ t(K.RIGHT, KEY_TAP_MS); }, TRICK_GAP_MS); } },
    { name: 'Flip',    fn: function() { t(K.DOWN,  KEY_TAP_MS); setTimeout(function(){ t(K.SPACE, KEY_TAP_MS); }, TRICK_GAP_MS); } },
    { name: 'SpinL',   fn: function() { t(K.SPACE, KEY_TAP_MS); setTimeout(function(){ t(K.LEFT,  KEY_TAP_MS); }, TRICK_GAP_MS); } }
  ];

  function doTrick() {
    var trick = TRICKS[trickIdx % TRICKS.length];
    trickIdx++;
    trick.fn();
    window.__csV3FarmStats.lastTrick = trick.name;
    window.__csV3FarmStats.tricks++;
    lastTrickTime = Date.now();
  }

  /* ════════════════════════════════════════════════════════════════════════
   *  CART SLAM (Space + Down, 30pts — used before turns to reset combo)
   * ════════════════════════════════════════════════════════════════════════ */

  function cartSlam() {
    t(K.SPACE, KEY_TAP_MS);
    setTimeout(function() { t(K.DOWN, KEY_TAP_MS); }, TRICK_GAP_MS);
    window.__csV3FarmStats.lastTrick = 'CartSlam';
    window.__csV3FarmStats.tricks++;
  }

  /* ════════════════════════════════════════════════════════════════════════
   *  BACK FLIP (Down + Space, 100pts — done after drift for max combo)
   * ════════════════════════════════════════════════════════════════════════ */

  function backFlip() {
    t(K.DOWN, KEY_TAP_MS);
    setTimeout(function() { t(K.SPACE, KEY_TAP_MS); }, TRICK_GAP_MS);
    window.__csV3FarmStats.lastTrick = 'BackFlip';
    window.__csV3FarmStats.tricks++;
  }

  /* ════════════════════════════════════════════════════════════════════════
   *  DRIFT TURN (Karltroid's drift: hold Down + Arrow, then back_flip)
   *
   *  This is the key improvement: instead of a simple turn, we do:
   *    1. Cart slam (Space+Down) — bonus 30 pts, resets combo
   *    2. Brief pause (400ms — same as Karltroid)
   *    3. Drift: hold Down + Arrow for TURN_DRIFT_MS — grinds the corner
   *    4. Back flip (Down+Space) — 100 pts after the turn
   * ════════════════════════════════════════════════════════════════════════ */

  function executeTurn(dir) {
    if (inTurnBlock) return;
    inTurnBlock = true;
    state = STATE.TURNING;
    turnCount++;
    window.__csV3FarmStats.turns = turnCount;
    window.__csV3FarmStats.lastTurnDir = dir;
    window.__csV3FarmStats.state = STATE.TURNING;

    /* Intentional crash: skip the turn to use a life and extend game time */
    if (turnCount === CRASH_TURN_NUM && livesUsed < MAX_LIVES) {
      livesUsed++;
      window.__csV3FarmStats.livesUsed = livesUsed;
      state = STATE.CRASHED;
      window.__csV3FarmStats.state = STATE.CRASHED;
      /* Press space to respawn after the crash */
      setTimeout(function() {
        t(K.SPACE, KEY_TAP_MS);
        setTimeout(function() {
          t(K.SPACE, KEY_TAP_MS);
          state = STATE.PLAYING;
          window.__csV3FarmStats.state = STATE.PLAYING;
          lastTrickTime = Date.now() + 800;
          inTurnBlock = false;
        }, 500);
      }, RESPAWN_WAIT_MS);
      return;
    }

    /* Step 1: Cart slam (Space+Down) for bonus points */
    cartSlam();

    /* Step 2: Pause 400ms (same as Karltroid) */
    setTimeout(function() {
      /* Step 3: Drift — hold Down + Arrow simultaneously (grind the corner) */
      var arrowKey = (dir === 'LEFT') ? K.LEFT : K.RIGHT;
      /* Press DOWN and hold it */
      var tg = getTarget();
      fireEvent(tg, 'keydown', K.DOWN[0], K.DOWN[1], K.DOWN[2]);
      var downRpt = setInterval(function() {
        fireEvent(tg, 'keydown', K.DOWN[0], K.DOWN[1], K.DOWN[2]);
      }, KEY_HOLD_REPEAT_MS);

      /* Press and hold the arrow key too */
      fireEvent(tg, 'keydown', arrowKey[0], arrowKey[1], arrowKey[2]);
      var arrowRpt = setInterval(function() {
        fireEvent(tg, 'keydown', arrowKey[0], arrowKey[1], arrowKey[2]);
      }, KEY_HOLD_REPEAT_MS);

      /* Release both after TURN_DRIFT_MS */
      setTimeout(function() {
        clearInterval(downRpt);
        clearInterval(arrowRpt);
        fireEvent(tg, 'keyup', K.DOWN[0], K.DOWN[1], K.DOWN[2]);
        fireEvent(tg, 'keyup', arrowKey[0], arrowKey[1], arrowKey[2]);

        /* Step 4: Back flip after the drift (100 pts!) */
        setTimeout(function() {
          backFlip();
          lastTrickTime = Date.now();

          /* Cooldown before resuming normal play */
          setTimeout(function() {
            state = STATE.PLAYING;
            window.__csV3FarmStats.state = STATE.PLAYING;
            inTurnBlock = false;
          }, TURN_COOLDOWN_MS);
        }, 200);
      }, TURN_DRIFT_MS);
    }, 400);
  }

  /* ════════════════════════════════════════════════════════════════════════
   *  MAIN LOOP
   * ════════════════════════════════════════════════════════════════════════ */

  function loop() {
    if (!window.__csV3FarmRunning) { clearInterval(loopId); return; }

    /* Don't act during turns, crashes, or when done */
    if (state === STATE.DONE || state === STATE.CRASHED || state === STATE.TURNING) return;

    /* Initialize: find the canvas and GL context */
    if (state === STATE.IDLE) {
      if (!ensureGL()) return;
      state = STATE.PLAYING;
      window.__csV3FarmStats.state = STATE.PLAYING;
      lastTrickTime = Date.now() + 2000; /* head start before first trick */
      return;
    }

    if (state !== STATE.PLAYING) return;

    /* ── 1. Check if game is still alive (test spot brightness) ── */
    var w   = gameCanvas.width;
    var hgt = gameCanvas.height;
    var testPx = samplePixel(w * TEST_SPOT_X_FRAC, hgt * TEST_SPOT_Y_FRAC);
    var testTotal = pixelTotal(testPx);
    window.__csV3FarmStats.lastTestTotal = testTotal;

    if (testTotal < ALIVE_THRESHOLD) {
      /* Screen is dark → game over */
      state = STATE.DONE;
      window.__csV3FarmStats.state = STATE.DONE;
      return;
    }

    /* ── 2. Detect yellow signs on left & right walls (Karltroid logic) ── */
    var leftYellow  = countYellowInRegion(LEFT_REGION);
    var rightYellow = countYellowInRegion(RIGHT_REGION);
    window.__csV3FarmStats.lastLeftYel  = leftYellow;
    window.__csV3FarmStats.lastRightYel = rightYellow;

    /* ── 3. If a sign is detected on one side, turn that direction ── */
    if (!inTurnBlock) {
      if (leftYellow >= YELLOW_THRESHOLD && leftYellow > rightYellow) {
        executeTurn('LEFT');
        return;
      }
      if (rightYellow >= YELLOW_THRESHOLD && rightYellow > leftYellow) {
        executeTurn('RIGHT');
        return;
      }
    }

    /* ── 4. No sign detected → do tricks on the straight section ── */
    if (!inTurnBlock && Date.now() - lastTrickTime >= TRICK_INTERVAL_MS) {
      doTrick();
    }
  }

  /* Start the detection loop */
  loopId = setInterval(loop, LOOP_INTERVAL);

  /* ════════════════════════════════════════════════════════════════════════
   *  STOP API
   * ════════════════════════════════════════════════════════════════════════ */

  window.__stopCartSurferV3Farm = function() {
    clearInterval(loopId);
    window.__csV3FarmRunning = false;
    window.__csV3FarmStats.running = false;
    return 'stopped';
  };

  return JSON.stringify({ status: 'started', ts: Date.now() });
})();
