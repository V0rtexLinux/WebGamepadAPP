package com.gamemapper.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * # NetworkStressTester
 *
 * Classe utilitária **singleton** (`object`) de testes avançados e simulação de carga de rede,
 * focada em validar a **resilência** e a **segurança** das APIs e WebSockets do ecossistema
 * do GameMapper / WebGamepadAPP.
 *
 * O objetivo é testar como o backend lida com:
 *  - **Anomalias de concorrência** (race conditions, disparo simultâneo do mesmo evento).
 *  - **Inputs agressivos** vindos do cliente (rajadas de eventos cronometrados, payloads forjados).
 *
 * A engine é construída sobre **Kotlin Coroutines** e **OkHttp** (HTTP + WebSocket) e expõe
 * três rotinas de teste unificadas:
 *
 *  1. **[runHighPrecisionInputAutomation]** — Automação de Inputs de Alta Precisão
 *     (UI Automation / Rate Limiting Test).
 *  2. **[runClockDriftValidation]** — Validação de Anomalias Temporais
 *     (Clock Drift & Validation Test).
 *  3. **[runConcurrentStressTest]** — Disparo Concorrente em Larga Escala
 *     (Race Conditions Stress Test).
 *
 * ## Parametrização
 * Todas as URLs de conexão e chaves de autenticação são **parametrizáveis** via
 * [TestConfig]. Aponte os placeholders para o seu servidor de testes local antes de executar:
 *
 * ```kotlin
 * NetworkStressTester.configure(
 *     TestConfig(
 *         websocketUrl = "ws://10.0.2.2:8080",        // emulador -> host local
 *         httpBaseUrl   = "http://10.0.2.2:8080/api",
 *         authToken     = "Bearer TEST_KEY_PLACEHOLDER"
 *     )
 * )
 * ```
 *
 * ## Observabilidade
 * Cada rotina emite eventos detalhados (timestamps de envio/resposta, latências,
 * divergências detectadas, status HTTP/WS) através de [events] (um `SharedFlow`)
 * e mantém um estado agregado em [state] (um `StateFlow`). Tudo também é espelhado
 * em `Log.d`/`Log.w`/`Log.e` sob a tag [TAG].
 *
 * > **Aviso:** Estas rotinas são ferramentas de QA ofensivo contra o **seu próprio**
 * > backend. Dispará-las contra serviços de terceiros pode violar termos de serviço.
 */
object NetworkStressTester {

    /** Tag de logging usada em todo o Logcat. */
    const val TAG = "NetStressTester"

    // ---------------------------------------------------------------------------------------------
    //  Estado observável (para UI / dashboards de QA)
    // ---------------------------------------------------------------------------------------------

    private val _events = MutableSharedFlow<TesterEvent>(
        extraBufferCapacity = 1024,
        // replay = 0: só consome quem estiver coletando no momento. Buffer grande evita perda
        // sob rajadas de alta frequência.
    )
    /** Fluxo de eventos granulares (envio, resposta, erros, métricas). Colete na UI/ViewModel. */
    val events: SharedFlow<TesterEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(TesterState())
    /** Estado agregado e imutável das rotinas em execução. */
    val state: StateFlow<TesterState> = _state.asStateFlow()

    // ---------------------------------------------------------------------------------------------
    //  Configuração parametrizável
    // ---------------------------------------------------------------------------------------------

    /**
     * Configuração de conexão e chaves. Todos os campos são placeholders por padrão —
     * substitua pelos endpoints do seu servidor de testes locais via [configure].
     *
     * @param websocketUrl       URL do WebSocket (ex.: `ws://10.0.2.2:8080` ou `wss://...`).
     * @param httpBaseUrl        URL base das APIs HTTP (ex.: `http://10.0.2.2:8080/api`).
     * @param authToken          Token/chave de autorização injetado nos headers
     *                           (`Authorization` em HTTP e subprotocol/param em WS).
     * @param apiKey             Chave de API adicional, enviada como header `X-API-Key`.
     * @param connectTimeoutMs   Timeout de conexão (HTTP/WS).
     * @param readTimeoutMs      Timeout de leitura (HTTP/WS).
     * @param wsResponseTimeoutMs Tempo máximo de espera por uma resposta de servidor WS
     *                           em cada interação, antes de considerar timeout.
     */
    data class TestConfig(
        val websocketUrl: String = "ws://10.0.2.2:8080",
        val httpBaseUrl: String = "http://10.0.2.2:8080/api",
        val authToken: String = "Bearer TEST_AUTH_TOKEN_PLACEHOLDER",
        val apiKey: String = "TEST_API_KEY_PLACEHOLDER",
        val connectTimeoutMs: Long = 10_000L,
        val readTimeoutMs: Long = 30_000L,
        val wsResponseTimeoutMs: Long = 5_000L,
    )

    @Volatile
    private var config: TestConfig = TestConfig()

    /** Refência mutável do client OkHttp para permitir reconfiguração sem reiniciar o processo. */
    private val clientRef = java.util.concurrent.atomic.AtomicReference<OkHttpClient>(buildClient(TestConfig()))
    private fun client(): OkHttpClient = clientRef.get()

    private fun buildClient(cfg: TestConfig): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS) // keepalive WS
            .retryOnConnectionFailure(true)
            .build()

    /**
     * (Re)configura URLs, chaves e timeouts. Reconstrói o cliente OkHttp imediatamente.
     */
    fun configure(cfg: TestConfig) {
        config = cfg
        // Substitui o client ativo; o anterior tem seu pool esvaziado para liberar sockets.
        runCatching { clientRef.get().connectionPool.evictAll() }
        clientRef.set(buildClient(cfg))
        emit(TesterEvent.Info("Configuração aplicada: ws=${cfg.websocketUrl} http=${cfg.httpBaseUrl}"))
    }

    // ---------------------------------------------------------------------------------------------
    //  Escopo de coroutines
    // ---------------------------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // ---------------------------------------------------------------------------------------------
    //  Serialização JSON (Gson já presente no projeto)
    // ---------------------------------------------------------------------------------------------

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    // ---------------------------------------------------------------------------------------------
    //  Modelos de eventos / estado / resultados
    // ---------------------------------------------------------------------------------------------

    /** Selo temporal de um único envio/resposta para auditoria precisa. */
    data class TimingRecord(
        val sequenceIndex: Int,
        val payload: String,
        val sentAtMs: Long,
        val ackAtMs: Long?,          // null se não houve resposta/ack
        val rttMs: Long?,            // round-trip time = ackAtMs - sentAtMs
        val serverResponse: String?,
        val outcome: Outcome,
    ) {
        enum class Outcome { SENT, ACKED, TIMEOUT, REJECTED, ERROR }
    }

    /** Evento granular emitido em [events] e logado. */
    sealed class TesterEvent {
        abstract val timestampMs: Long
        abstract val routine: String

        data class Info(val msg: String, override val routine: String = "core", override val timestampMs: Long = now()) : TesterEvent()
        data class Sent(val sequenceIndex: Int, val payload: String, override val routine: String, override val timestampMs: Long = now()) : TesterEvent()
        data class Acked(val sequenceIndex: Int, val serverResponse: String?, val rttMs: Long, override val routine: String, override val timestampMs: Long = now()) : TesterEvent()
        data class Rejected(val sequenceIndex: Int, val reason: String, override val routine: String, override val timestampMs: Long = now()) : TesterEvent()
        data class Timeout(val sequenceIndex: Int, override val routine: String, override val timestampMs: Long = now()) : TesterEvent()
        data class Error(val message: String, val throwable: Throwable? = null, override val routine: String = "core", override val timestampMs: Long = now()) : TesterEvent()
        data class Metric(val name: String, val value: Double, override val routine: String, override val timestampMs: Long = now()) : TesterEvent()
        data class Phase(val phase: String, override val routine: String, override val timestampMs: Long = now()) : TesterEvent()
    }

    /** Estado agregado e imutável exposto em [state]. */
    data class TesterState(
        val runningRoutines: Set<String> = emptySet(),
        val totalSent: Int = 0,
        val totalAcked: Int = 0,
        val totalRejected: Int = 0,
        val totalTimeouts: Int = 0,
        val totalErrors: Int = 0,
        val avgRttMs: Double = 0.0,
        val maxRttMs: Long = 0L,
        val minRttMs: Long = Long.MAX_VALUE,
    )

    /** Resultado consolidado de uma rotina. */
    sealed class TestReport {
        abstract val routine: String
        abstract val startedAtMs: Long
        abstract val finishedAtMs: Long
        val durationMs: Long get() = finishedAtMs - startedAtMs

        /** Relatório da Rotina 1 (Automação de Inputs de Alta Precisão). */
        data class HighPrecisionReport(
            override val routine: String = ROUTINE_INPUT_AUTOMATION,
            override val startedAtMs: Long,
            override val finishedAtMs: Long,
            val totalEventsSent: Int,
            val totalAcks: Int,
            val totalTimeouts: Int,
            val totalErrors: Int,
            val avgRttMs: Double,
            val minRttMs: Long,
            val maxRttMs: Long,
            val p50RttMs: Long,
            val p95RttMs: Long,
            val p99RttMs: Long,
            val jitterMs: Double,                 // desvio-padrão dos RTTs
            val targetIntervalMs: Long,
            val observedAvgIntervalMs: Double,
            val timing: List<TimingRecord>,       // histórico completo de timestamps
        ) : TestReport()

        /** Relatório da Rotina 2 (Anomalias Temporais). */
        data class ClockDriftReport(
            override val routine: String = ROUTINE_CLOCK_DRIFT,
            override val startedAtMs: Long,
            override val finishedAtMs: Long,
            val injectedPayloads: Int,
            val acceptedByServer: Int,            // quantas anomalias passaram (ruim p/ backend)
            val rejectedByServer: Int,            // quantas foram bloqueadas (bom p/ backend)
            val errors: Int,
            val driftMatrix: List<DriftCase>,
        ) : TestReport() {
            /** Uma anomalia injetada e seu respectivo veredito do servidor. */
            data class DriftCase(
                val label: String,
                val startTimeMs: Long,
                val endTimeMs: Long,
                val artificialDriftMs: Long,      // divergência artificial injetada
                val payload: String,
                val serverStatus: String?,        // ex.: "200", "409", "422"
                val serverBody: String?,
                val verdict: Verdict,
            ) {
                enum class Verdict { ACCEPTED, REJECTED, ERROR, NO_RESPONSE }
            }
        }

        /** Relatório da Rotina 3 (Disparo Concorrente em Larga Escala). */
        data class ConcurrentStressReport(
            override val routine: String = ROUTINE_CONCURRENT_STRESS,
            override val startedAtMs: Long,
            override val finishedAtMs: Long,
            val concurrency: Int,
            val requestsPerWorker: Int,
            val totalRequestsFired: Int,
            val firedWithinSameMillisec: Int,    // disparos colapsados no mesmo ms (alvo do teste)
            val uniqueServerResponses: Map<String, Int>, // status -> contagem
            val duplicateProcessingDetected: Int,        // respostas 2xx duplicadas = bug de concorrência
            val errors: Int,
            val perWorker: List<WorkerResult>,
        ) : TestReport() {
            data class WorkerResult(
                val workerId: Int,
                val firedAtMs: Long,
                val responses: List<Pair<String, String>>, // (status, trecho do corpo)
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  Helpers de logging / emissão / estado
    // ---------------------------------------------------------------------------------------------

    private val sentCount = AtomicInteger()
    private val ackedCount = AtomicInteger()
    private val rejectedCount = AtomicInteger()
    private val timeoutCount = AtomicInteger()
    private val errorCount = AtomicInteger()
    private val rttSamples = ConcurrentLinkedQueue<Long>()

    private fun emit(event: TesterEvent) {
        when (event) {
            is TesterEvent.Info -> Log.d(TAG, "[${event.routine}] ${event.msg}")
            is TesterEvent.Sent -> Log.d(TAG, "[${event.routine}] >> #${event.sequenceIndex} ${event.payload}")
            is TesterEvent.Acked -> Log.d(TAG, "[${event.routine}] << #${event.sequenceIndex} rtt=${event.rttMs}ms resp=${event.serverResponse}")
            is TesterEvent.Rejected -> Log.w(TAG, "[${event.routine}] REJEITADO #${event.sequenceIndex}: ${event.reason}")
            is TesterEvent.Timeout -> Log.w(TAG, "[${event.routine}] TIMEOUT #${event.sequenceIndex}")
            is TesterEvent.Error -> Log.e(TAG, "[${event.routine}] ERRO: ${event.message}", event.throwable)
            is TesterEvent.Metric -> Log.d(TAG, "[${event.routine}] metric ${event.name}=${event.value}")
            is TesterEvent.Phase -> Log.d(TAG, "[${event.routine}] FASE: ${event.phase}")
        }
        _events.tryEmit(event)
    }

    private fun addRoutine(name: String) {
        _state.update { it.copy(runningRoutines = it.runningRoutines + name) }
    }

    private fun removeRoutine(name: String) {
        _state.update { it.copy(runningRoutines = it.runningRoutines - name) }
    }

    private fun recordRtt(rtt: Long) {
        rttSamples.add(rtt)
        sentCount.incrementAndGet()
        ackedCount.incrementAndGet()
        val cur = _state.value
        _state.update {
            it.copy(
                totalSent = sentCount.get(),
                totalAcked = ackedCount.get(),
                avgRttMs = rttSamples.average(),
                maxRttMs = maxOf(it.maxRttMs, rtt),
                minRttMs = minOf(it.minRttMs, rtt),
            )
        }
    }

    private fun recordRejected() { rejectedCount.incrementAndGet(); _state.update { it.copy(totalRejected = rejectedCount.get()) } }
    private fun recordTimeout() { timeoutCount.incrementAndGet(); _state.update { it.copy(totalTimeouts = timeoutCount.get()) } }
    private fun recordError() { errorCount.incrementAndGet(); _state.update { it.copy(totalErrors = errorCount.get()) } }

    // ---------------------------------------------------------------------------------------------
    //  Constantes & helpers de tempo
    // ---------------------------------------------------------------------------------------------

    const val ROUTINE_INPUT_AUTOMATION = "R1:InputAutomation"
    const val ROUTINE_CLOCK_DRIFT = "R2:ClockDrift"
    const val ROUTINE_CONCURRENT_STRESS = "R3:ConcurrentStress"

    private fun now(): Long = System.currentTimeMillis()

    private fun rttMs(sent: Long, ack: Long): Long = ack - sent

    /** Percentil sobre uma lista ordenada (0..100). */
    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** Desvio-padrão amostral (jitter). */
    private fun stdDev(values: List<Long>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return kotlin.math.sqrt(variance)
    }

    // ---------------------------------------------------------------------------------------------
    //  Construtores de requisição (HTTP + WebSocket) com auth parametrizável
    // ---------------------------------------------------------------------------------------------

    private fun authHeaders(): Array<Pair<String, String>> = arrayOf(
        "Authorization" to config.authToken,
        "X-API-Key" to config.apiKey,
        "Content-Type" to "application/json; charset=utf-8",
    )

    private fun buildHttpRequest(endpointPath: String, body: String, method: String = "POST"): Request {
        val url = if (endpointPath.startsWith("http")) endpointPath else "${config.httpBaseUrl.trimEnd('/')}/${endpointPath.trimStart('/')}"
        val builder = Request.Builder().url(url)
        authHeaders().forEach { (k, v) -> builder.header(k, v) }
        when (method.uppercase()) {
            "POST" -> builder.post(body.toRequestBody(JSON))
            "PUT" -> builder.put(body.toRequestBody(JSON))
            "PATCH" -> builder.patch(body.toRequestBody(JSON))
            "GET" -> builder.get()
            "DELETE" -> builder.delete(body.toRequestBody(JSON))
        }
        return builder.build()
    }

    private fun buildWsRequest(): Request {
        val builder = Request.Builder().url(config.websocketUrl)
        // Token injetado como header customizado (compatível com a maioria dos backends WS).
        // Se o seu servidor exigir subprotocolo, troque por: builder.header("Sec-WebSocket-Protocol", config.authToken)
        builder.header("Authorization", config.authToken)
        builder.header("X-API-Key", config.apiKey)
        return builder.build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ---------------------------------------------------------------------------------------------
    //  WebSocket corrotinizado (envia/recebe com timeout por mensagem)
    // ---------------------------------------------------------------------------------------------

    /**
     * Abre um WebSocket, aguarda OPEN, e executa [onOpen] (que normalmente envia mensagens e
     * recebe acks). Fecha ao final. Retorna a lista de [TimingRecord] da sessão.
     */
    private suspend fun openWsSession(
        routine: String,
        onResponse: suspend (payload: String) -> Unit,
        block: suspend WsChannel.() -> Unit,
    ): List<TimingRecord> {
        val records = ConcurrentLinkedQueue<TimingRecord>()
        val request = buildWsRequest()
        val channel = WsChannel(routine, config.wsResponseTimeoutMs, onResponse, records)

        withContext(Dispatchers.IO) {
            val ws = client().newWebSocket(request, channel)
            channel.bind(ws)
            // Aguarda OPEN (com timeout) antes de permitir o bloco do caller.
            val opened = withTimeoutOrNull(config.connectTimeoutMs) { channel.openedDeferred.await() }
            if (opened == null) {
                emit(TesterEvent.Error("WebSocket não abriu em ${config.connectTimeoutMs}ms", routine = routine))
                return@withContext
            }
            try {
                channel.block()
            } finally {
                channel.closeGracefully()
            }
        }
        return records.toList()
    }

    // ---------------------------------------------------------------------------------------------
    //  WsChannel — ponte coroutines <-> OkHttp WebSocketListener
    // ---------------------------------------------------------------------------------------------

    /**
     * Encapsula um [okhttp3.WebSocket] expondo uma API suspensa: [send] registra o timestamp de
     * envio e aguarda o ack/resposta do servidor dentro de um timeout configurável, devolvendo
     * um [TimingRecord]. O callback [onResponse] permite ao caller interpretar respostas
     * assíncronas (ex.: mensagens de erro/rejeição do backend).
     */
    private class WsChannel(
        private val routine: String,
        private val responseTimeoutMs: Long,
        private val onResponse: suspend (String) -> Unit,
        private val records: ConcurrentLinkedQueue<TimingRecord>,
    ) : WebSocketListener() {

        @Volatile private var socket: WebSocket? = null
        private val dispatcher = CoroutineScope(Dispatchers.Default)
        val openedDeferred = CompletableDeferred<Boolean>()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Pair<Long?, String?>>>()
        private val seqGen = AtomicInteger(0)

        fun bind(ws: WebSocket) { socket = ws }

        /** Envia [payload], retorna TimingRecord com sentAt/ackAt/rtt/outcome. */
        suspend fun send(payload: String): TimingRecord {
            val ws = socket ?: error("WebSocket não vinculado")
            val seq = seqGen.incrementAndGet()
            val sentAt = now()
            val deferred = CompletableDeferred<Pair<Long?, String?>>()
            pending[seq] = deferred
            emit(TesterEvent.Sent(seq, payload, routine, sentAt))

            val ok = withContext(Dispatchers.IO) { ws.send(payload) }
            if (!ok) {
                pending.remove(seq)
                val rec = TimingRecord(seq, payload, sentAt, null, null, null, TimingRecord.Outcome.ERROR)
                records.add(rec)
                emit(TesterEvent.Error("Falha no envio WS (buffer cheio?)", routine = routine))
                return rec
            }

            val result = withTimeoutOrNull(responseTimeoutMs) { deferred.await() }
            pending.remove(seq)
            val (ackAt, resp) = result ?: (null to null)
            val outcome = when {
                ackAt != null -> TimingRecord.Outcome.ACKED
                result == null -> TimingRecord.Outcome.TIMEOUT
                else -> TimingRecord.Outcome.ERROR
            }
            val rec = TimingRecord(
                sequenceIndex = seq,
                payload = payload,
                sentAtMs = sentAt,
                ackAtMs = ackAt,
                rttMs = if (ackAt != null) rttMs(sentAt, ackAt) else null,
                serverResponse = resp,
                outcome = outcome,
            )
            records.add(rec)
            when (outcome) {
                TimingRecord.Outcome.ACKED -> {
                    emit(TesterEvent.Acked(seq, resp, rec.rttMs ?: 0, routine, ackAt ?: now()))
                }
                TimingRecord.Outcome.TIMEOUT -> {
                    emit(TesterEvent.Timeout(seq, routine, now()))
                }
                else -> {}
            }
            return rec
        }

        /** Envia sem aguardar ack (útil para rajadas máximas / rotina de race). */
        fun fireAndForget(payload: String): Boolean {
            val ws = socket ?: return false
            return ws.send(payload)
        }

        fun closeGracefully() {
            try { socket?.close(1000, "NetworkStressTester: fim da rotina") } catch (_: Exception) {}
            dispatcher.cancel()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            openedDeferred.complete(true)
            emit(TesterEvent.Phase("WebSocket OPEN ${response.code}", routine))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Roteia a resposta para o pending correto quando possível; caso contrário,
            // repassa ao callback do caller para interpretação de rejeições/anomalias.
            dispatcher.launch {
                try { onResponse(text) } catch (t: Throwable) {
                    emit(TesterEvent.Error("onResponse falhou: ${t.message}", t, routine))
                }
            }
            // Tenta casar por seq embutida no payload; se não houver, completa o pending mais antigo.
            val seq = extractSeq(text)
            val target = if (seq != null) pending[seq] else pending.values.firstOrNull()
            target?.complete(now() to text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            openedDeferred.complete(true)
            emit(TesterEvent.Info("WS closing: $code $reason", routine))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            openedDeferred.completeExceptionally(t)
            emit(TesterEvent.Error("WS failure: ${t.message}", t, routine))
            // Falha todos os pendentes.
            pending.values.forEach { it.complete(null to null) }
            pending.clear()
        }

        private fun extractSeq(text: String): Int? = try {
            val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
            if (obj.has("seq")) obj.get("seq").asInt else null
        } catch (_: Exception) { null }
    }

    // =============================================================================================
    //  ROTINA 1 — Automação de Inputs de Alta Precisão (UI Automation / Rate Limiting Test)
    // =============================================================================================

    /**
     * Configuração da Rotina 1.
     *
     * @param intervalMs        Intervalo entre eventos via `delay()` (default 200ms). Cronometragem
     *                          precisa para validar rate-limiting do backend.
     * @param totalEvents       Quantos eventos disparar (0 = infinito até [stopRoutine]).
     * @param inputSequence     Sequência de inputs alternados. Default: ArrowDown / Space.
     * @param includeSeq        Inclui campo `seq` no payload para casar acks por sequência.
     * @param sessionId         ID de sessão injetado no payload (placeholder).
     * @param jitterMs          Jitter aleatório adicionado ao intervalo (0 = perfeitamente regular).
     */
    data class InputAutomationConfig(
        val intervalMs: Long = 200L,
        val totalEvents: Int = 100,
        val inputSequence: List<String> = listOf("ArrowDown", "Space"),
        val includeSeq: Boolean = true,
        val sessionId: String = "SESSION_ID_PLACEHOLDER",
        val jitterMs: Long = 0L,
    )

    /**
     * Dispara um fluxo contínuo e automatizado de eventos físicos ao servidor via WebSocket,
     * usando sequências rápidas e perfeitamente cronometradas de payloads JSON alternados.
     *
     * Cada envio é logado com timestamp de envio e o ack do servidor com timestamp de resposta,
     * permitindo medir RTT, jitter e detectar rate-limiting/rejeições do backend.
     *
     * Executa em segundo plano e retorna o [Job] (cancelável via [stopRoutine] ou `job.cancel()`).
     * O relatório consolidado é emitido via [events] ao final e também retornado pelo `Deferred`
     * produzido por [runHighPrecisionInputAutomationBlocking] (versão suspensa).
     */
    fun runHighPrecisionInputAutomation(cfg: InputAutomationConfig = InputAutomationConfig()): Job {
        val key = ROUTINE_INPUT_AUTOMATION
        cancelExisting(key)
        val job = scope.launch {
            addRoutine(key)
            try {
                val report = executeInputAutomation(cfg)
                emit(TesterEvent.Info(report.summary(), key))
            } catch (t: Throwable) {
                emit(TesterEvent.Error("Rotina 1 falhou: ${t.message}", t, key))
                recordError()
            } finally {
                removeRoutine(key)
            }
        }
        activeJobs[key] = job
        return job
    }

    /** Versão suspensa que devolve o [TestReport.HighPrecisionReport] diretamente. */
    suspend fun runHighPrecisionInputAutomationBlocking(cfg: InputAutomationConfig = InputAutomationConfig()): TestReport.HighPrecisionReport {
        addRoutine(ROUTINE_INPUT_AUTOMATION)
        return try { executeInputAutomation(cfg) as TestReport.HighPrecisionReport }
        finally { removeRoutine(ROUTINE_INPUT_AUTOMATION) }
    }

    private suspend fun executeInputAutomation(cfg: InputAutomationConfig): TestReport.HighPrecisionReport {
        val started = now()
        emit(TesterEvent.Phase("Início: ${cfg.totalEvents} eventos @ ${cfg.intervalMs}ms (jitter=${cfg.jitterMs}ms)", ROUTINE_INPUT_AUTOMATION))

        val rtts = mutableListOf<Long>()
        val intervals = mutableListOf<Long>()
        var lastSent = 0L

        val timing = openWsSession(ROUTINE_INPUT_AUTOMATION, onResponse = { /* acks roteados pelo canal */ }) {
            for (i in 0 until cfg.totalEvents) {
                if (!scope.isActive) break
                val input = cfg.inputSequence[i % cfg.inputSequence.size]
                val payload = buildInputPayload(input, i, cfg.sessionId, cfg.includeSeq)
                val rec = send(payload)
                rec.rttMs?.let { rtts.add(it) }
                if (lastSent != 0L) intervals.add(rec.sentAtMs - lastSent)
                lastSent = rec.sentAtMs

                // delay() configurável + jitter opcional para simular cadência humana/extrema.
                val wait = if (cfg.jitterMs > 0) {
                    cfg.intervalMs + (Math.random() * cfg.jitterMs).toLong() - cfg.jitterMs / 2
                } else cfg.intervalMs
                delay(wait.coerceAtLeast(0))
            }
        }

        val sortedRtt = rtts.sorted()
        val finished = now()
        return TestReport.HighPrecisionReport(
            startedAtMs = started,
            finishedAtMs = finished,
            totalEventsSent = timing.size,
            totalAcks = timing.count { it.outcome == TimingRecord.Outcome.ACKED },
            totalTimeouts = timing.count { it.outcome == TimingRecord.Outcome.TIMEOUT },
            totalErrors = timing.count { it.outcome == TimingRecord.Outcome.ERROR },
            avgRttMs = if (rtts.isNotEmpty()) rtts.average() else 0.0,
            minRttMs = sortedRtt.minOrNull() ?: 0L,
            maxRttMs = sortedRtt.maxOrNull() ?: 0L,
            p50RttMs = percentile(sortedRtt, 50.0),
            p95RttMs = percentile(sortedRtt, 95.0),
            p99RttMs = percentile(sortedRtt, 99.0),
            jitterMs = stdDev(rtts),
            targetIntervalMs = cfg.intervalMs,
            observedAvgIntervalMs = if (intervals.isNotEmpty()) intervals.average() else 0.0,
            timing = timing,
        )
    }

    /** Constrói o payload JSON de um input físico: {"input":"ArrowDown","seq":n,"session":...}. */
    private fun buildInputPayload(input: String, seq: Int, sessionId: String, includeSeq: Boolean): String {
        val obj = JsonObject()
        obj.addProperty("input", input)
        obj.addProperty("type", "key")
        obj.addProperty("timestamp", now())
        if (includeSeq) obj.addProperty("seq", seq)
        obj.addProperty("session", sessionId)
        return gson.toJson(obj)
    }

    private fun TestReport.HighPrecisionReport.summary(): String =
        "Rotina1 OK: enviados=$totalEventsSent acks=$totalAcks timeouts=$totalTimeouts " +
            "RTT avg=%.1f p95=$p95RttMs p99=$p99RttMs jitter=%.1fms intervaloObs=%.1fms (alvo=${targetIntervalMs}ms)"
            .format(avgRttMs, jitterMs, observedAvgIntervalMs)

    // =============================================================================================
    //  ROTINA 2 — Validação de Anomalias Temporais (Clock Drift & Validation Test)
    // =============================================================================================

    /**
     * Configuração da Rotina 2.
     *
     * @param endpointPath   Path HTTP (relativo a [TestConfig.httpBaseUrl]) ou URL absoluta que
     *                       recebe eventos de encerramento de sessão/minijogo.
     * @param useWebSocket   Se true, dispara via WebSocket em vez de HTTP POST.
     * @param sessionId      ID da sessão/minijogo (placeholder).
     * @param minigame       Nome do minijogo (placeholder).
     * @param driftScenarios Lista de divergências temporais artificiais a injetar (ms).
     * @param customStartTimeMs  Sobrescreve manualmente o start_time (0 = usar agora - duração).
     * @param customEndTimeMs    Sobrescreve manualmente o end_time (0 = usar agora).
     */
    data class ClockDriftConfig(
        val endpointPath: String = "session/end",
        val useWebSocket: Boolean = false,
        val sessionId: String = "SESSION_ID_PLACEHOLDER",
        val minigame: String = "CART_SURFER",
        val driftScenarios: List<DriftScenario> = defaultDriftScenarios(),
        val customStartTimeMs: Long = 0L,
        val customEndTimeMs: Long = 0L,
        val sessionDurationMs: Long = 60_000L,
    )

    /**
     * Cenário de divergência temporal artificial.
     *
     * @param label             Rótulo legível (ex.: "end_before_start").
     * @param startDeltaMs      Offset aplicado a start_time (positivo = atrasa o início).
     * @param endDeltaMs        Offset aplicado a end_time (positivo = adianta o fim p/ o futuro).
     * @param negativeDuration  Se true, força end_time < start_time (duração negativa).
     * @param futureEndMinutes  Se > 0, coloca end_time N minutos no futuro (clock adiantado).
     * @param pastStartDays     Se > 0, coloca start_time N dias no passado.
     */
    data class DriftScenario(
        val label: String,
        val startDeltaMs: Long = 0L,
        val endDeltaMs: Long = 0L,
        val negativeDuration: Boolean = false,
        val futureEndMinutes: Long = 0L,
        val pastStartDays: Long = 0L,
    )

    /** Cenários padrão que cobrem as principais anomalias temporais. */
    fun defaultDriftScenarios(): List<DriftScenario> = listOf(
        DriftScenario(label = "normal_baseline", startDeltaMs = 0, endDeltaMs = 0),
        DriftScenario(label = "end_before_start", negativeDuration = true),
        DriftScenario(label = "end_30s_future", futureEndMinutes = 0, endDeltaMs = 30_000L),
        DriftScenario(label = "end_10min_future", futureEndMinutes = 10),
        DriftScenario(label = "start_7d_past", pastStartDays = 7),
        DriftScenario(label = "start_2min_ahead", startDeltaMs = 120_000L), // início no futuro
        DriftScenario(label = "extreme_latency_24h", startDeltaMs = -24 * 3_600_000L, endDeltaMs = -24 * 3_600_000L),
    )

    /**
     * Gera um payload JSON estruturado de encerramento de sessão/minijogo
     * (`{"action":"end_game", ...}`) com timestamps **forjados manualmente**, permitindo injetar
     * divergências temporais artificiais (latência extrema ou dessincronização de relógio).
     *
     * @param scenario   Cenário de drift a aplicar.
     * @param cfg        Configuração da rotina.
     * @return Par `(payloadJson, meta)` onde meta descreve start/end reais e o drift injetado.
     */
    fun forgeEndGamePayload(scenario: DriftScenario, cfg: ClockDriftConfig = ClockDriftConfig()): Pair<String, ForgedPayloadMeta> {
        val nowMs = now()
        val baseStart = cfg.customStartTimeMs.takeIf { it != 0L } ?: (nowMs - cfg.sessionDurationMs)
        val baseEnd = cfg.customEndTimeMs.takeIf { it != 0L } ?: nowMs

        // Aplica offsets do cenário.
        var start = baseStart + scenario.startDeltaMs
        var end = baseEnd + scenario.endDeltaMs

        if (scenario.pastStartDays > 0) start = nowMs - scenario.pastStartDays * 86_400_000L
        if (scenario.futureEndMinutes > 0) end = nowMs + scenario.futureEndMinutes * 60_000L
        if (scenario.negativeDuration) end = start - 1_000L // end < start

        val artificialDrift = (end - start) - (baseEnd - baseStart)

        val obj = JsonObject()
        obj.addProperty("action", "end_game")
        obj.addProperty("session_id", cfg.sessionId)
        obj.addProperty("minigame", cfg.minigame)
        obj.addProperty("start_time", start)
        obj.addProperty("end_time", end)
        obj.addProperty("client_clock", nowMs)             // relógio "real" do cliente p/ auditoria
        obj.addProperty("drift_label", scenario.label)
        obj.addProperty("request_id", UUID.randomUUID().toString())
        val meta = ForgedPayloadMeta(
            label = scenario.label,
            startTimeMs = start,
            endTimeMs = end,
            clientClockMs = nowMs,
            artificialDriftMs = artificialDrift,
        )
        return gson.toJson(obj) to meta
    }

    /** Metadados de um payload forjado (para o relatório). */
    data class ForgedPayloadMeta(
        val label: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val clientClockMs: Long,
        val artificialDriftMs: Long,
    )

    /**
     * Dispara cada payload forjado contra o backend e registra se o servidor **aceitou** ou
     * **rejeitou** a inconsistência temporal. O objetivo é validar se as regras de validação do
     * backend conseguem identificar e rejeitar a anomalia (caso `normal_baseline` deve ser aceito;
     * os demais idealmente rejeitados com 4xx).
     */
    fun runClockDriftValidation(cfg: ClockDriftConfig = ClockDriftConfig()): Job {
        val key = ROUTINE_CLOCK_DRIFT
        cancelExisting(key)
        val job = scope.launch {
            addRoutine(key)
            try {
                val report = executeClockDrift(cfg)
                emit(TesterEvent.Info(report.summary(), key))
            } catch (t: Throwable) {
                emit(TesterEvent.Error("Rotina 2 falhou: ${t.message}", t, key))
                recordError()
            } finally { removeRoutine(key) }
        }
        activeJobs[key] = job
        return job
    }

    suspend fun runClockDriftValidationBlocking(cfg: ClockDriftConfig = ClockDriftConfig()): TestReport.ClockDriftReport {
        addRoutine(ROUTINE_CLOCK_DRIFT)
        return try { executeClockDrift(cfg) as TestReport.ClockDriftReport }
        finally { removeRoutine(ROUTINE_CLOCK_DRIFT) }
    }

    private suspend fun executeClockDrift(cfg: ClockDriftConfig): TestReport.ClockDriftReport {
        val started = now()
        emit(TesterEvent.Phase("Início: ${cfg.driftScenarios.size} cenários de drift @ ${cfg.endpointPath}", ROUTINE_CLOCK_DRIFT))
        val cases = mutableListOf<TestReport.ClockDriftReport.DriftCase>()

        if (cfg.useWebSocket) {
            openWsSession(ROUTINE_CLOCK_DRIFT, onResponse = {}) {
                cfg.driftScenarios.forEach { scenario ->
                    val (payload, meta) = forgeEndGamePayload(scenario, cfg)
                    val rec = send(payload)
                    val verdict = interpretVerdict(rec.serverResponse, rec.outcome)
                    cases += TestReport.ClockDriftReport.DriftCase(
                        label = scenario.label,
                        startTimeMs = meta.startTimeMs,
                        endTimeMs = meta.endTimeMs,
                        artificialDriftMs = meta.artificialDriftMs,
                        payload = payload,
                        serverStatus = rec.serverResponse,
                        serverBody = rec.serverResponse,
                        verdict = verdict,
                    )
                    emitVerdict(scenario.label, verdict)
                    delay(150) // pequeno espaçamento entre cenários
                }
            }
        } else {
            for (scenario in cfg.driftScenarios) {
                val (payload, meta) = forgeEndGamePayload(scenario, cfg)
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        client().newCall(buildHttpRequest(cfg.endpointPath, payload)).execute().use { resp ->
                            val body = resp.body?.string()?.take(500)
                            resp.code.toString() to body
                        }
                    }.getOrElse { "ERR" to (it.message ?: "exception") }
                }
                val (status, body) = result
                val verdict = interpretVerdict(status, TimingRecord.Outcome.ACKED)
                cases += TestReport.ClockDriftReport.DriftCase(
                    label = scenario.label,
                    startTimeMs = meta.startTimeMs,
                    endTimeMs = meta.endTimeMs,
                    artificialDriftMs = meta.artificialDriftMs,
                    payload = payload,
                    serverStatus = status,
                    serverBody = body,
                    verdict = verdict,
                )
                emitVerdict(scenario.label, verdict)
                delay(150)
            }
        }

        val finished = now()
        return TestReport.ClockDriftReport(
            startedAtMs = started,
            finishedAtMs = finished,
            injectedPayloads = cases.size,
            acceptedByServer = cases.count { it.verdict == TestReport.ClockDriftReport.DriftCase.Verdict.ACCEPTED },
            rejectedByServer = cases.count { it.verdict == TestReport.ClockDriftReport.DriftCase.Verdict.REJECTED },
            errors = cases.count { it.verdict == TestReport.ClockDriftReport.DriftCase.Verdict.ERROR },
            driftMatrix = cases,
        )
    }

    /** Interpreta o veredito: 2xx = aceito (anomalia passou!), 4xx = rejeitado (backend ok), resto = erro. */
    private fun interpretVerdict(statusOrBody: String?, outcome: TimingRecord.Outcome): TestReport.ClockDriftReport.DriftCase.Verdict {
        if (outcome == TimingRecord.Outcome.TIMEOUT) return TestReport.ClockDriftReport.DriftCase.Verdict.NO_RESPONSE
        if (outcome == TimingRecord.Outcome.ERROR) return TestReport.ClockDriftReport.DriftCase.Verdict.ERROR
        val code = statusOrBody?.let { Regex("\\b(\\d{3})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: return if (outcome == TimingRecord.Outcome.ACKED)
                TestReport.ClockDriftReport.DriftCase.Verdict.ACCEPTED
            else TestReport.ClockDriftReport.DriftCase.Verdict.ERROR
        return when (code) {
            in 200..299 -> TestReport.ClockDriftReport.DriftCase.Verdict.ACCEPTED
            in 400..499 -> TestReport.ClockDriftReport.DriftCase.Verdict.REJECTED
            else -> TestReport.ClockDriftReport.DriftCase.Verdict.ERROR
        }
    }

    private fun emitVerdict(label: String, v: TestReport.ClockDriftReport.DriftCase.Verdict) {
        val tag = when (v) {
            TestReport.ClockDriftReport.DriftCase.Verdict.ACCEPTED -> "ACEITOU (anomalia passou — revisar backend)"
            TestReport.ClockDriftReport.DriftCase.Verdict.REJECTED -> "REJEITOU (backend íntegro)"
            TestReport.ClockDriftReport.DriftCase.Verdict.ERROR -> "ERRO"
            TestReport.ClockDriftReport.DriftCase.Verdict.NO_RESPONSE -> "SEM RESPOSTA"
        }
        emit(TesterEvent.Info("Drift[$label] -> $tag", ROUTINE_CLOCK_DRIFT))
        if (v == TestReport.ClockDriftReport.DriftCase.Verdict.ACCEPTED && label != "normal_baseline") recordError()
        if (v == TestReport.ClockDriftReport.DriftCase.Verdict.REJECTED) recordRejected()
    }

    private fun TestReport.ClockDriftReport.summary(): String =
        "Rotina2 OK: injetados=$injectedPayloads aceitos=$acceptedByServer rejeitados=$rejectedByServer " +
            "erros=$errors — anomalias aceitas além do baseline indicam falha de validação temporal no backend."

    // =============================================================================================
    //  ROTINA 3 — Disparo Concorrente em Larga Escala (Race Conditions Stress Test)
    // =============================================================================================

    /**
     * Configuração da Rotina 3.
     *
     * @param endpointPath     Endpoint HTTP (relativo a [TestConfig.httpBaseUrl]) ou absoluto.
     * @param useWebSocket     Se true, dispara via WebSocket (várias conexões paralelas).
     * @param concurrency      Número de conexões/workers paralelos.
     * @param requestsPerWorker Quantas vezes cada worker dispara o MESMO pacote.
     * @param payloadTemplate  Payload base; se null, gera um evento legítimo com UUID fixo por
     *                         execução (para forçar race sobre o mesmo recurso).
     * @param synchronizeMs    Se > 0, todos os workers sincronizam o disparo para o mesmo
     *                         milissegundo (barreira de tempo).
     * @param staggerMs        Se > 0, atrasa cada worker em staggerMs*i (dispersão controlada).
     */
    data class ConcurrentStressConfig(
        val endpointPath: String = "session/event",
        val useWebSocket: Boolean = false,
        val concurrency: Int = 50,
        val requestsPerWorker: Int = 5,
        val payloadTemplate: String? = null,
        val synchronizeMs: Boolean = true,
        val staggerMs: Long = 0L,
        val timeoutPerRequestMs: Long = 10_000L,
    )

    /**
     * Motor de estresse concorrente: gerencia uma lista de conexões de rede simuladas em paralelo
     * e dispara **exatamente o mesmo pacote** simultaneamente (no mesmo milissegundo) usando
     * `async(Dispatchers.IO)` + `awaitAll()`.
     *
     * Foco: estressar o endpoint para validar se o banco do servidor implementa corretamente
     * mecanismos de concorrência (Row Locking / transações ACID), impedindo processamento
     * duplicado/inconsistente de um único evento legítimo.
     *
     * Heurística de detecção de race: se o backend processar o MESMO `request_id` mais de uma vez
     * (múltiplas respostas 2xx distintas ou contadores duplicados), isso sinaliza falha de
     * idempotência/lock.
     */
    fun runConcurrentStressTest(cfg: ConcurrentStressConfig = ConcurrentStressConfig()): Job {
        val key = ROUTINE_CONCURRENT_STRESS
        cancelExisting(key)
        val job = scope.launch {
            addRoutine(key)
            try {
                val report = executeConcurrentStress(cfg)
                emit(TesterEvent.Info(report.summary(), key))
            } catch (t: Throwable) {
                emit(TesterEvent.Error("Rotina 3 falhou: ${t.message}", t, key))
                recordError()
            } finally { removeRoutine(key) }
        }
        activeJobs[key] = job
        return job
    }

    suspend fun runConcurrentStressTestBlocking(cfg: ConcurrentStressConfig = ConcurrentStressConfig()): TestReport.ConcurrentStressReport {
        addRoutine(ROUTINE_CONCURRENT_STRESS)
        return try { executeConcurrentStress(cfg) as TestReport.ConcurrentStressReport }
        finally { removeRoutine(ROUTINE_CONCURRENT_STRESS) }
    }

    private suspend fun executeConcurrentStress(cfg: ConcurrentStressConfig): TestReport.ConcurrentStressReport {
        val started = now()
        // Pacote idêntico para TODOS os workers — mesmo request_id força a validação de lock/idempotência.
        val sharedPayload = cfg.payloadTemplate ?: buildSharedEventPayload()
        val sharedRequestId = extractRequestId(sharedPayload) ?: UUID.randomUUID().toString()
        emit(TesterEvent.Phase(
            "Início: concurrency=${cfg.concurrency} x ${cfg.requestsPerWorker} reqs, request_id=$sharedRequestId",
            ROUTINE_CONCURRENT_STRESS
        ))

        // Barreira de tempo: todos disparam no mesmo ms.
        val fireAt = if (cfg.synchronizeMs) now() + 500L else 0L
        val sameMsCounter = AtomicInteger()

        val perWorker = if (cfg.useWebSocket) {
            stressViaWebSockets(cfg, sharedPayload, fireAt, sameMsCounter)
        } else {
            stressViaHttp(cfg, sharedPayload, fireAt, sameMsCounter)
        }

        // Análise de duplicidade: conta respostas 2xx distintas para o MESMO request_id.
        val allResponses = perWorker.flatMap { it.responses }
        val statusCounts = allResponses.groupingBy { it.first }.eachCount()
        val dup2xx = allResponses.count { it.first.startsWith("2") } - 1 // >0 => possível processamento duplicado
        val duplicateDetected = dup2xx.coerceAtLeast(0)

        val finished = now()
        return TestReport.ConcurrentStressReport(
            startedAtMs = started,
            finishedAtMs = finished,
            concurrency = cfg.concurrency,
            requestsPerWorker = cfg.requestsPerWorker,
            totalRequestsFired = perWorker.sumOf { it.responses.size },
            firedWithinSameMillisec = sameMsCounter.get(),
            uniqueServerResponses = statusCounts,
            duplicateProcessingDetected = duplicateDetected,
            errors = allResponses.count { it.first == "ERR" },
            perWorker = perWorker,
        )
    }

    /** Estresse via HTTP: cada worker é um async(Dispatchers.IO) disparando o mesmo pacote. */
    private suspend fun stressViaHttp(
        cfg: ConcurrentStressConfig,
        payload: String,
        fireAt: Long,
        sameMsCounter: AtomicInteger,
    ): List<TestReport.ConcurrentStressReport.WorkerResult> = coroutineScope {
        val req = buildHttpRequest(cfg.endpointPath, payload)
        val deferreds = (0 until cfg.concurrency).map { i ->
            async(Dispatchers.IO) {
                // Sincronização para o mesmo milissegundo.
                if (fireAt > 0) {
                    val wait = fireAt - now() - cfg.staggerMs * i
                    if (wait > 0) delay(wait)
                } else if (cfg.staggerMs > 0) {
                    delay(cfg.staggerMs * i)
                }
                val firedAt = now()
                val responses = mutableListOf<Pair<String, String>>()
                repeat(cfg.requestsPerWorker) {
                    val result = runCatching {
                        client().newCall(req).execute().use { resp ->
                            (resp.code.toString()) to (resp.body?.string()?.take(120) ?: "")
                        }
                    }.getOrElse { "ERR" to (it.message ?: "exception") }
                    responses += result
                    if (now() == firedAt) sameMsCounter.incrementAndGet()
                    sentCount.incrementAndGet()
                    _state.update { s -> s.copy(totalSent = sentCount.get()) }
                }
                TestReport.ConcurrentStressReport.WorkerResult(i, firedAt, responses)
            }
        }
        deferreds.awaitAll()
    }

    /** Estresse via WebSocket: abre [concurrency] conexões e dispara o mesmo pacote em paralelo. */
    private suspend fun stressViaWebSockets(
        cfg: ConcurrentStressConfig,
        payload: String,
        fireAt: Long,
        sameMsCounter: AtomicInteger,
    ): List<TestReport.ConcurrentStressReport.WorkerResult> = coroutineScope {
        val deferreds = (0 until cfg.concurrency).map { i ->
            async(Dispatchers.IO) {
                if (fireAt > 0) {
                    val wait = fireAt - now() - cfg.staggerMs * i
                    if (wait > 0) delay(wait)
                } else if (cfg.staggerMs > 0) {
                    delay(cfg.staggerMs * i)
                }
                val firedAt = now()
                val responses = mutableListOf<Pair<String, String>>()
                val channel = WsChannel(ROUTINE_CONCURRENT_STRESS, cfg.timeoutPerRequestMs, onResponse = {}) {}
                val ws = client().newWebSocket(buildWsRequest(), channel)
                channel.bind(ws)
                try {
                    repeat(cfg.requestsPerWorker) {
                        channel.fireAndForget(payload)
                        responses += "WS_SENT" to payload.take(80)
                        if (now() == firedAt) sameMsCounter.incrementAndGet()
                        sentCount.incrementAndGet()
                        _state.update { s -> s.copy(totalSent = sentCount.get()) }
                    }
                } finally {
                    channel.closeGracefully()
                }
                TestReport.ConcurrentStressReport.WorkerResult(i, firedAt, responses)
            }
        }
        deferreds.awaitAll()
    }

    /** Constrói um evento legítimo compartilhado (mesmo request_id p/ forçar race no mesmo recurso). */
    private fun buildSharedEventPayload(): String {
        val obj = JsonObject()
        val reqId = UUID.randomUUID().toString()
        obj.addProperty("action", "submit_event")
        obj.addProperty("request_id", reqId)          // chave de idempotência — o backend DEVE dedup
        obj.addProperty("event", "coin_collected")
        obj.addProperty("session_id", "SESSION_ID_PLACEHOLDER")
        obj.addProperty("amount", 1)
        obj.addProperty("timestamp", now())
        return gson.toJson(obj)
    }

    private fun extractRequestId(payload: String): String? = try {
        val obj = com.google.gson.JsonParser.parseString(payload).asJsonObject
        if (obj.has("request_id")) obj.get("request_id").asString else null
    } catch (_: Exception) { null }

    private fun TestReport.ConcurrentStressReport.summary(): String {
        val dupFlag = if (duplicateProcessingDetected > 0)
            " ⚠️ DUPLICIDADE DETECTADA ($duplicateProcessingDetected) — possível falha de Row Lock/ACID!"
        else " ✓ Sem duplicidade aparente."
        return "Rotina3 OK: fired=$totalRequestsFired sameMs=$firedWithinSameMillisec " +
            "responses=$uniqueServerResponses$dupFlag"
    }

    // =============================================================================================
    //  Orquestração: execução unificada, controle e shutdown
    // =============================================================================================

    /**
     * Executa as três rotinas em sequência (cada uma só inicia após a anterior finalizar) e
     * devolve uma lista com os relatórios. Útil para uma bateria completa de QA.
     */
    suspend fun runFullBattery(
        r1: InputAutomationConfig = InputAutomationConfig(),
        r2: ClockDriftConfig = ClockDriftConfig(),
        r3: ConcurrentStressConfig = ConcurrentStressConfig(),
    ): List<TestReport> {
        emit(TesterEvent.Phase("Bateria completa iniciada", "battery"))
        val reports = mutableListOf<TestReport>()
        runCatching { reports += runHighPrecisionInputAutomationBlocking(r1) }
        runCatching { reports += runClockDriftValidationBlocking(r2) }
        runCatching { reports += runConcurrentStressTestBlocking(r3) }
        emit(TesterEvent.Phase("Bateria completa finalizada: ${reports.size} relatórios", "battery"))
        return reports
    }

    /** Cancela uma rotina em execução pelo nome (constantes ROUTINE_*). */
    suspend fun stopRoutine(routine: String) {
        activeJobs.remove(routine)?.let { it.cancelAndJoin() }
        removeRoutine(routine)
    }

    /** Cancela todas as rotinas ativas. */
    suspend fun stopAll() {
        activeJobs.values.toList().forEach { runCatching { it.cancelAndJoin() } }
        activeJobs.clear()
        _state.update { it.copy(runningRoutines = emptySet()) }
        emit(TesterEvent.Info("Todas as rotinas canceladas"))
    }

    private fun cancelExisting(routine: String) {
        activeJobs.remove(routine)?.cancel()
    }

    /** Encerra o motor: cancela escopo e fecha o cliente OkHttp. Chame em onDestroy do app. */
    fun shutdown() {
        runCatching { clientRef.get().dispatcher.executorService.shutdown() }
        runCatching { clientRef.get().connectionPool.evictAll() }
        scope.cancel()
        emit(TesterEvent.Info("NetworkStressTester encerrado"))
    }

    /** Reseta contadores agregados de [state]. */
    fun resetCounters() {
        sentCount.set(0); ackedCount.set(0); rejectedCount.set(0)
        timeoutCount.set(0); errorCount.set(0); rttSamples.clear()
        _state.value = TesterState()
    }
}
