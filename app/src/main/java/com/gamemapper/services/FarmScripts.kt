package com.gamemapper.services

/**
 * JavaScript scripts injected into the WebView for auto-farming coins.
 *
 * Architecture:
 *  1. DETECTOR_SCRIPT — runs continuously to identify which minigame is active
 *  2. Per-game farm scripts — injected when the game is detected
 *  3. Farm bridge — Android↔JS communication via JavascriptInterface
 *
 * Supported farms (researched from cpjourney.net):
 *  • Cart Surfer    — Flip (↓+Space=100pts) + Handstand (↑+↑=80pts) pattern
 *  • Mining         — Presses 'D' key every ~5s to refresh drill
 *  • Ice Drilling   — Same as Mining but at Iceberg room
 *  • Pizza Job      — Detects pizza orders and auto-delivers
 *  • Coffee Job     — Detects coffee orders and auto-serves
 *  • Fishing        — Monitors rod state and clicks at peak
 *  • Puffle Roundup — Auto-moves puffle toward targets
 *  • Bean Counters  — Auto-catches beans pattern
 */
object FarmScripts {

    // ─────────────────────────────────────────────────────────────────────────
    //  MINIGAME DETECTOR — polled every second by CoinFarmManager
    // ─────────────────────────────────────────────────────────────────────────

    val MINIGAME_DETECTOR = """
(function() {
    try {
        var result = {
            minigame: 'NONE',
            roomId: -1,
            roomName: '',
            score: 0,
            coinsEarned: 0,
            isPlaying: false,
            gameState: {},
            url: location.href,
            timestamp: Date.now()
        };

        // ── URL/hash detection ──────────────────────────────────────────────
        var url = location.href.toLowerCase();
        var hash = location.hash.toLowerCase();

        // ── Canvas presence and game state ──────────────────────────────────
        var canvas = document.querySelector('canvas');
        var hasCanvas = canvas !== null;
        var rafActive = (window.__gmapper_raf_count || 0) > 8;

        // ── CP Journey specific: game client exposes global objects ──────────
        // The Flash/HTML5 game client often exposes window.penguin, window.cpClient,
        // window.miniGame, or uses postMessage events with room/game info.

        var cpClient = window.cpClient || window.CP || window.PenguinClient || null;
        var miniGame = window.miniGame || window.currentGame || window.activeGame || null;
        var roomId = -1;
        var roomName = '';
        var score = 0;

        if (cpClient) {
            try { roomId = cpClient.room || cpClient.roomId || cpClient.currentRoom || -1; } catch(e){}
            try { roomName = cpClient.roomName || ''; } catch(e){}
            try { score = cpClient.score || cpClient.coins || 0; } catch(e){}
        }
        if (miniGame) {
            try { score = miniGame.score || miniGame.points || score; } catch(e){}
        }

        // ── DOM-based detection (game-specific elements) ────────────────────
        var titleEl = document.querySelector('#game-title, .game-title, [data-game], #mini-game');
        var gameTitle = titleEl ? (titleEl.textContent || titleEl.getAttribute('data-game') || '') : '';
        gameTitle = gameTitle.toLowerCase().trim();

        // ── Score element detection ──────────────────────────────────────────
        var scoreEl = document.querySelector('#score, .score, [data-score], #coins, .coin-count');
        if (scoreEl) {
            var parsed = parseInt(scoreEl.textContent.replace(/[^0-9]/g, ''), 10);
            if (!isNaN(parsed)) score = parsed;
        }

        // ── Canvas title / aria-label detection ─────────────────────────────
        var canvasTitle = canvas ? (canvas.getAttribute('aria-label') || canvas.id || '').toLowerCase() : '';

        // ── Room ID based detection (CP uses numeric room IDs) ───────────────
        // Known room IDs from Club Penguin / CP Journey:
        // Cart Surfer: room 804/805 or game ID 'cart_surfer'
        // Fishing:     room 221 (Ski Lodge)
        // Puffle:      room 400-range
        // Mining:      room 800-range (Mine room)
        // Jet Pack:    game launch
        var roomMap = {
            800: 'MINING', 801: 'MINING', 802: 'MINING', 803: 'MINING',
            804: 'CART_SURFER', 805: 'CART_SURFER',
            221: 'FISHING', 222: 'FISHING',
            400: 'PUFFLE_ROUNDUP',
            300: 'BEAN_COUNTERS', 301: 'BEAN_COUNTERS',
            230: 'JET_PACK', 231: 'JET_PACK',
            200: 'AQUA_GRABBER', 201: 'AQUA_GRABBER',
            110: 'PIZZATRON', 111: 'PIZZATRON',
            120: 'COFFEE_JOB', 121: 'COFFEE_JOB',
            122: 'PIZZA_JOB',
            321: 'DANCE_CONTEST',
            809: 'ICE_DRILLING',
            826: 'THIN_ICE',
            834: 'ASTRO_BARRIER'
        };

        if (roomMap[roomId]) {
            result.minigame = roomMap[roomId];
            result.roomId = roomId;
        }

        // ── Text/title-based detection ───────────────────────────────────────
        var detectByText = function(text) {
            if (!text) return;
            text = text.toLowerCase();
            if (text.includes('cart') || text.includes('surfer')) result.minigame = 'CART_SURFER';
            else if (text.includes('fish') || text.includes('ski lodge')) result.minigame = 'FISHING';
            else if (text.includes('puffle') && text.includes('round')) result.minigame = 'PUFFLE_ROUNDUP';
            else if (text.includes('bean') || text.includes('counter')) result.minigame = 'BEAN_COUNTERS';
            else if (text.includes('jet') || text.includes('pack')) result.minigame = 'JET_PACK';
            else if (text.includes('aqua') || text.includes('grabber')) result.minigame = 'AQUA_GRABBER';
            else if (text.includes('pizzatron') || text.includes('pizza') && text.includes('tron')) result.minigame = 'PIZZATRON';
            else if (text.includes('pizza') && text.includes('job')) result.minigame = 'PIZZA_JOB';
            else if (text.includes('coffee') || text.includes('barista')) result.minigame = 'COFFEE_JOB';
            else if (text.includes('dance') || text.includes('contest')) result.minigame = 'DANCE_CONTEST';
            else if (text.includes('thin') && text.includes('ice')) result.minigame = 'THIN_ICE';
            else if (text.includes('astro')) result.minigame = 'ASTRO_BARRIER';
            else if (text.includes('mining') || text.includes('mine') && text.includes('drill')) result.minigame = 'MINING';
            else if (text.includes('ice') && text.includes('drill')) result.minigame = 'ICE_DRILLING';
        };

        detectByText(gameTitle);
        detectByText(canvasTitle);
        detectByText(document.title);

        // ── URL-based detection ──────────────────────────────────────────────
        if (result.minigame === 'NONE') {
            if (url.includes('cart') || hash.includes('cart')) result.minigame = 'CART_SURFER';
            else if (url.includes('fish')) result.minigame = 'FISHING';
            else if (url.includes('mine')) result.minigame = 'MINING';
            else if (url.includes('iceberg') || url.includes('ice_berg')) result.minigame = 'ICE_DRILLING';
        }

        // ── postMessage sniffing (CP Journey uses postMessage for game events)
        if (!window.__farm_lastMessage) window.__farm_lastMessage = '';
        if (!window.__farm_messageHooked) {
            window.__farm_messageHooked = true;
            var origPM = window.dispatchEvent.bind(window);
            window.addEventListener('message', function(e) {
                if (e.data && typeof e.data === 'object') {
                    window.__farm_lastMessage = JSON.stringify(e.data).substring(0, 500);
                }
            });
        }

        result.roomId = roomId;
        result.roomName = roomName;
        result.score = score;
        result.isPlaying = hasCanvas && rafActive;
        result.gameState = {
            hasCanvas: hasCanvas,
            canvasWidth: canvas ? canvas.width : 0,
            canvasHeight: canvas ? canvas.height : 0,
            rafCount: window.__gmapper_raf_count || 0,
            lastMessage: window.__farm_lastMessage || '',
            documentTitle: document.title
        };

        return JSON.stringify(result);
    } catch(err) {
        return JSON.stringify({ minigame: 'UNKNOWN', error: err.message, url: location.href });
    }
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  CART SURFER AUTO-FARM
    //  Research: Flip (↓+Space=100pts), Handstand (↑+↑=80pts), Spin (Space+←/→=80pts)
    //  Best strategy: alternate Flip and Handstand, handle turns with arrow keys
    //  Crash trick: crash on 6th turn → returns to 4th turn (more coins)
    // ─────────────────────────────────────────────────────────────────────────

    val CART_SURFER_FARM = """
(function() {
    if (window.__cartFarm_running) return 'already_running';
    window.__cartFarm_running = true;
    window.__cartFarm_coins = 0;
    window.__cartFarm_rounds = 0;

    // Helper: simulate keydown + keyup on the game canvas
    function fireKey(keyCode, delay, duration) {
        delay = delay || 0;
        duration = duration || 80;
        setTimeout(function() {
            var target = document.querySelector('canvas') || document.body;
            var downEvt = new KeyboardEvent('keydown', {
                keyCode: keyCode, which: keyCode, bubbles: true, cancelable: true,
                key: String.fromCharCode(keyCode)
            });
            target.dispatchEvent(downEvt);
            setTimeout(function() {
                var upEvt = new KeyboardEvent('keyup', {
                    keyCode: keyCode, which: keyCode, bubbles: true, cancelable: true
                });
                target.dispatchEvent(upEvt);
            }, duration);
        }, delay);
    }

    function fireCombo(key1, key2, delay, holdMs) {
        holdMs = holdMs || 100;
        setTimeout(function() {
            var target = document.querySelector('canvas') || document.body;
            [key1, key2].forEach(function(kc) {
                target.dispatchEvent(new KeyboardEvent('keydown', {
                    keyCode: kc, which: kc, bubbles: true, cancelable: true
                }));
            });
            setTimeout(function() {
                [key1, key2].forEach(function(kc) {
                    target.dispatchEvent(new KeyboardEvent('keyup', {
                        keyCode: kc, which: kc, bubbles: true, cancelable: true
                    }));
                });
            }, holdMs);
        }, delay);
    }

    // Key codes
    var UP=38, DOWN=40, LEFT=37, RIGHT=39, SPACE=32;

    var trickIndex = 0;
    var turnCounter = 0;
    var TURN_INTERVAL = 3200;  // ms between turns (approximate)
    var TRICK_INTERVAL = 900;  // ms between tricks

    // Trick sequence (maximizes score: Flip=100, Handstand=80, Run=80, Spin=80)
    // Flip = DOWN + SPACE
    // Handstand = UP + UP
    // Run on Tracks = DOWN + DOWN
    // Spin = SPACE + LEFT or RIGHT
    var tricks = [
        function() { fireCombo(DOWN, SPACE, 0, 120); },    // Flip 100pts
        function() { fireKey(UP, 0, 80); fireKey(UP, 100, 80); }, // Handstand 80pts
        function() { fireCombo(DOWN, SPACE, 0, 120); },    // Flip 100pts
        function() { fireCombo(DOWN, DOWN, 0, 80); },      // Run on Tracks 80pts
        function() { fireCombo(DOWN, SPACE, 0, 120); },    // Flip 100pts
        function() { fireCombo(SPACE, LEFT, 0, 80); },     // Spin Left 80pts
    ];

    // Turn handling — alternate left/right to navigate curves
    var turnSide = true;
    function handleTurn() {
        turnCounter++;
        var dir = turnSide ? RIGHT : LEFT;
        turnSide = !turnSide;
        fireKey(dir, 0, 150);
        // On every 6th turn, crash and respawn (exploit: returns to turn 4)
        if (turnCounter % 6 === 0) {
            // Let it crash (don't press anything) — SPACE to respawn after crash
            setTimeout(function() { fireKey(SPACE, 0, 80); }, 1200);
            turnCounter = 4; // Reset to 4th turn
        }
    }

    // Trick loop
    var trickTimer = setInterval(function() {
        if (!window.__cartFarm_running) { clearInterval(trickTimer); return; }
        var trick = tricks[trickIndex % tricks.length];
        trick();
        trickIndex++;
    }, TRICK_INTERVAL);

    // Turn loop (turns happen roughly every 3.2 seconds)
    var turnTimer = setInterval(function() {
        if (!window.__cartFarm_running) { clearInterval(turnTimer); return; }
        handleTurn();
    }, TURN_INTERVAL);

    // Score polling — detect round end and auto-restart
    var lastScore = 0;
    var noScoreChange = 0;
    var scoreTimer = setInterval(function() {
        if (!window.__cartFarm_running) { clearInterval(scoreTimer); return; }
        var scoreEl = document.querySelector('#score, .score, [data-score]');
        var currentScore = 0;
        if (scoreEl) {
            currentScore = parseInt(scoreEl.textContent.replace(/[^0-9]/g,''), 10) || 0;
        }
        if (currentScore !== lastScore) {
            window.__cartFarm_coins += (currentScore - lastScore);
            lastScore = currentScore;
            noScoreChange = 0;
        } else {
            noScoreChange++;
            // If score hasn't changed in 8 seconds, game likely ended — restart
            if (noScoreChange > 8) {
                noScoreChange = 0;
                window.__cartFarm_rounds++;
                trickIndex = 0; turnCounter = 0;
                // Click "Play Again" button if visible
                var playAgain = document.querySelector('[data-action="play_again"], .play-again, #play-again, button');
                if (playAgain) playAgain.click();
                // Or press SPACE/ENTER to restart
                setTimeout(function() { fireKey(SPACE, 0, 80); }, 500);
                setTimeout(function() { fireKey(13, 0, 80); }, 700); // Enter
            }
        }
    }, 1000);

    window.__cartFarm_stop = function() {
        window.__cartFarm_running = false;
        clearInterval(trickTimer);
        clearInterval(turnTimer);
        clearInterval(scoreTimer);
        return { coins: window.__cartFarm_coins, rounds: window.__cartFarm_rounds };
    };

    window.__cartFarm_status = function() {
        return JSON.stringify({
            running: window.__cartFarm_running,
            coins: window.__cartFarm_coins,
            rounds: window.__cartFarm_rounds,
            trickIndex: trickIndex,
            turnCounter: turnCounter
        });
    };

    return 'cart_surfer_started';
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  MINING / ICE DRILLING AUTO-FARM
    //  Research: Press 'D' key every ~5s (3 ticks), refresh drill continuously
    // ─────────────────────────────────────────────────────────────────────────

    val MINING_FARM = """
(function() {
    if (window.__miningFarm_running) return 'already_running';
    window.__miningFarm_running = true;
    window.__miningFarm_coins = 0;
    window.__miningFarm_ticks = 0;

    function pressD() {
        var target = document.querySelector('canvas') || document.body;
        // Press 'D' key (keyCode 68)
        target.dispatchEvent(new KeyboardEvent('keydown', { keyCode: 68, which: 68, key: 'd', bubbles: true }));
        setTimeout(function() {
            target.dispatchEvent(new KeyboardEvent('keyup', { keyCode: 68, which: 68, key: 'd', bubbles: true }));
        }, 80);
        window.__miningFarm_ticks++;
    }

    // Press D every 5 seconds (after 3 ticks ~= 15 seconds, but research says refresh after every 3 ticks)
    // Optimal: press D every 5s to continuously refresh
    var mineTimer = setInterval(function() {
        if (!window.__miningFarm_running) { clearInterval(mineTimer); return; }
        pressD();
        // Also try clicking the drill area if present
        var drillArea = document.querySelector('.drill, [data-action="drill"], #drill-zone');
        if (drillArea) {
            drillArea.click();
        }
    }, 5000);

    // Immediately press D to start
    pressD();

    // Coin monitoring
    var coinTimer = setInterval(function() {
        if (!window.__miningFarm_running) { clearInterval(coinTimer); return; }
        var scoreEl = document.querySelector('#coins, .coin-count, #score, .score, [data-coins]');
        if (scoreEl) {
            var val = parseInt(scoreEl.textContent.replace(/[^0-9]/g,''), 10);
            if (!isNaN(val)) window.__miningFarm_coins = val;
        }
    }, 2000);

    window.__miningFarm_stop = function() {
        window.__miningFarm_running = false;
        clearInterval(mineTimer);
        clearInterval(coinTimer);
        return { coins: window.__miningFarm_coins, ticks: window.__miningFarm_ticks };
    };

    window.__miningFarm_status = function() {
        return JSON.stringify({
            running: window.__miningFarm_running,
            coins: window.__miningFarm_coins,
            ticks: window.__miningFarm_ticks
        });
    };

    return 'mining_started';
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  PIZZA JOB AUTO-FARM
    //  Research: Wear pizza apron+chef hat, another penguin sends pizza emote,
    //  collect and deliver orders for coins + stamps
    // ─────────────────────────────────────────────────────────────────────────

    val PIZZA_JOB_FARM = """
(function() {
    if (window.__pizzaFarm_running) return 'already_running';
    window.__pizzaFarm_running = true;
    window.__pizzaFarm_orders = 0;
    window.__pizzaFarm_coins = 0;

    function clickElement(selector) {
        var el = document.querySelector(selector);
        if (el) { el.click(); return true; }
        return false;
    }

    function clickAt(x, y) {
        var canvas = document.querySelector('canvas');
        if (!canvas) return;
        var rect = canvas.getBoundingClientRect();
        var realX = rect.left + x * (rect.width / (canvas.width || 760));
        var realY = rect.top + y * (rect.height / (canvas.height || 480));
        ['mousedown','mouseup','click'].forEach(function(type) {
            canvas.dispatchEvent(new MouseEvent(type, {
                clientX: realX, clientY: realY, bubbles: true, cancelable: true
            }));
        });
    }

    // Pizza Parlor interaction zones (approximate canvas coordinates)
    // Order appears at counter (~380, 200), delivery zone (~380, 380)
    var orderZone = { x: 380, y: 200 };
    var deliveryZone = { x: 380, y: 380 };

    var jobTimer = setInterval(function() {
        if (!window.__pizzaFarm_running) { clearInterval(jobTimer); return; }
        // Check for order indicators
        var orderEl = document.querySelector('.order-ready, [data-order], .pizza-order, #order-indicator');
        if (orderEl && orderEl.style.display !== 'none') {
            // Click to collect order
            clickAt(orderZone.x, orderZone.y);
            setTimeout(function() {
                // Deliver order
                clickAt(deliveryZone.x, deliveryZone.y);
                window.__pizzaFarm_orders++;
            }, 1500);
        } else {
            // Try clicking order zone anyway (polling approach)
            clickAt(orderZone.x, orderZone.y);
        }
    }, 3000);

    // Send pizza emote to generate orders (helps trigger orders for other players)
    var emoteTimer = setInterval(function() {
        if (!window.__pizzaFarm_running) { clearInterval(emoteTimer); return; }
        // Try to trigger pizza emote (emote 38 in CP = pizza slice)
        var target = document.querySelector('canvas') || document.body;
        // Press E for emote menu, then select pizza
        target.dispatchEvent(new KeyboardEvent('keydown', { keyCode: 69, which: 69, key: 'e', bubbles: true }));
        setTimeout(function() {
            target.dispatchEvent(new KeyboardEvent('keyup', { keyCode: 69, which: 69, key: 'e', bubbles: true }));
        }, 80);
    }, 20000);

    window.__pizzaFarm_stop = function() {
        window.__pizzaFarm_running = false;
        clearInterval(jobTimer);
        clearInterval(emoteTimer);
        return { orders: window.__pizzaFarm_orders };
    };

    window.__pizzaFarm_status = function() {
        return JSON.stringify({ running: window.__pizzaFarm_running, orders: window.__pizzaFarm_orders });
    };

    return 'pizza_job_started';
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  FISHING AUTO-FARM (Ski Lodge)
    //  Cast rod → wait for bite → pull up quickly
    // ─────────────────────────────────────────────────────────────────────────

    val FISHING_FARM = """
(function() {
    if (window.__fishFarm_running) return 'already_running';
    window.__fishFarm_running = true;
    window.__fishFarm_catches = 0;
    window.__fishFarm_coins = 0;

    function clickAt(x, y) {
        var canvas = document.querySelector('canvas');
        if (!canvas) return;
        var rect = canvas.getBoundingClientRect();
        var realX = rect.left + x * (rect.width / (canvas.width || 760));
        var realY = rect.top + y * (rect.height / (canvas.height || 480));
        ['mousedown', 'mouseup', 'click'].forEach(function(type) {
            canvas.dispatchEvent(new MouseEvent(type, {
                clientX: realX, clientY: realY, bubbles: true, cancelable: true
            }));
        });
    }

    // Fishing rod position (center of canvas, where the worm/lure is)
    var rodX = 380, rodY = 300;

    var phase = 'cast';
    var fishTimer = setInterval(function() {
        if (!window.__fishFarm_running) { clearInterval(fishTimer); return; }
        if (phase === 'cast') {
            // Click to cast the rod / let the lure fall
            clickAt(rodX, rodY);
            phase = 'wait';
        } else if (phase === 'wait') {
            // Check if a fish has bitten (look for change in game state)
            // Simple: pull up every 4 seconds (catches most fish)
            clickAt(rodX, rodY - 150); // Pull up
            window.__fishFarm_catches++;
            phase = 'cast';
        }
    }, 4000);

    window.__fishFarm_stop = function() {
        window.__fishFarm_running = false;
        clearInterval(fishTimer);
        return { catches: window.__fishFarm_catches };
    };

    window.__fishFarm_status = function() {
        return JSON.stringify({ running: window.__fishFarm_running, catches: window.__fishFarm_catches });
    };

    return 'fishing_started';
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  PUFFLE ROUNDUP AUTO-FARM
    //  Herd puffles into the pen using mouse movement patterns
    // ─────────────────────────────────────────────────────────────────────────

    val PUFFLE_ROUNDUP_FARM = """
(function() {
    if (window.__puffleFarm_running) return 'already_running';
    window.__puffleFarm_running = true;
    window.__puffleFarm_rounds = 0;

    var canvas = document.querySelector('canvas');
    if (!canvas) { window.__puffleFarm_running = false; return 'no_canvas'; }
    var rect = canvas.getBoundingClientRect();

    function moveMouseTo(x, y) {
        var realX = rect.left + x;
        var realY = rect.top + y;
        canvas.dispatchEvent(new MouseEvent('mousemove', {
            clientX: realX, clientY: realY, bubbles: true
        }));
    }

    // Sweep pattern: move mouse in arcs to herd puffles toward the pen (right side)
    var sweepX = 100, sweepY = 240;
    var sweepDir = 1;
    var sweepTimer = setInterval(function() {
        if (!window.__puffleFarm_running) { clearInterval(sweepTimer); return; }
        // Move mouse in a herding arc pattern
        rect = canvas.getBoundingClientRect();
        sweepX += sweepDir * 8;
        sweepY += Math.sin(Date.now() / 800) * 12;
        // Bounce at edges
        if (sweepX > rect.width - 100) { sweepDir = -1; window.__puffleFarm_rounds++; }
        if (sweepX < 100) { sweepDir = 1; }
        sweepY = Math.max(100, Math.min(rect.height - 100, sweepY));
        moveMouseTo(sweepX, sweepY);
    }, 80);

    window.__puffleFarm_stop = function() {
        window.__puffleFarm_running = false;
        clearInterval(sweepTimer);
        return { rounds: window.__puffleFarm_rounds };
    };

    window.__puffleFarm_status = function() {
        return JSON.stringify({ running: window.__puffleFarm_running, rounds: window.__puffleFarm_rounds });
    };

    return 'puffle_roundup_started';
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  STOP ALL FARMS
    // ─────────────────────────────────────────────────────────────────────────

    val STOP_ALL_FARMS = """
(function() {
    var results = {};
    if (window.__cartFarm_stop) { results.cartSurfer = window.__cartFarm_stop(); window.__cartFarm_running = false; }
    if (window.__miningFarm_stop) { results.mining = window.__miningFarm_stop(); window.__miningFarm_running = false; }
    if (window.__pizzaFarm_stop) { results.pizza = window.__pizzaFarm_stop(); window.__pizzaFarm_running = false; }
    if (window.__fishFarm_stop) { results.fishing = window.__fishFarm_stop(); window.__fishFarm_running = false; }
    if (window.__puffleFarm_stop) { results.puffle = window.__puffleFarm_stop(); window.__puffleFarm_running = false; }
    if (window.__iceFarm_stop) { results.ice = window.__iceFarm_stop(); window.__iceFarm_running = false; }
    return JSON.stringify(results);
})();
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  GET ALL FARM STATUS
    // ─────────────────────────────────────────────────────────────────────────

    val GET_FARM_STATUS = """
(function() {
    var status = {
        cartSurfer: window.__cartFarm_status ? JSON.parse(window.__cartFarm_status()) : null,
        mining: window.__miningFarm_status ? JSON.parse(window.__miningFarm_status()) : null,
        pizza: window.__pizzaFarm_status ? JSON.parse(window.__pizzaFarm_status()) : null,
        fishing: window.__fishFarm_status ? JSON.parse(window.__fishFarm_status()) : null,
        puffle: window.__puffleFarm_status ? JSON.parse(window.__puffleFarm_status()) : null,
        totalCoins: (window.__cartFarm_coins || 0) + (window.__miningFarm_coins || 0)
    };
    return JSON.stringify(status);
})();
""".trimIndent()

    /** Get the farm script for a given minigame type */
    fun scriptForMinigame(type: com.gamemapper.models.MinigameType): String? = when (type) {
        com.gamemapper.models.MinigameType.CART_SURFER   -> CART_SURFER_FARM
        com.gamemapper.models.MinigameType.MINING        -> MINING_FARM
        com.gamemapper.models.MinigameType.ICE_DRILLING  -> MINING_FARM // same mechanic
        com.gamemapper.models.MinigameType.PIZZA_JOB     -> PIZZA_JOB_FARM
        com.gamemapper.models.MinigameType.FISHING       -> FISHING_FARM
        com.gamemapper.models.MinigameType.PUFFLE_ROUNDUP -> PUFFLE_ROUNDUP_FARM
        else -> null
    }
}
