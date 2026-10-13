package app.talktype

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** On-device speech recognition. Keeps one model loaded at a time. */
object LocalTranscriber {
    private var loadedId: String? = null
    private var recognizer: OfflineRecognizer? = null

    /** Loads [m] if it is not already in memory. Slow the first time (reads hundreds of MB). */
    @Synchronized
    fun ensureLoaded(m: LocalModel): OfflineRecognizer {
        recognizer?.let { if (loadedId == m.id) return it }
        unload()
        val model = OfflineModelConfig().apply {
            tokens = ModelStore.file(m, "tokens.txt")
            numThreads = 4
            provider = "cpu"
            debug = false
        }
        when (m.kind) {
            ModelKind.WHISPER -> {
                model.whisper = OfflineWhisperModelConfig().apply {
                    encoder = ModelStore.file(m, "encoder.onnx")
                    decoder = ModelStore.file(m, "decoder.onnx")
                    language = m.language
                    task = "transcribe"
                }
                model.modelType = "whisper"
            }
            ModelKind.PARAKEET -> {
                model.transducer = OfflineTransducerModelConfig().apply {
                    encoder = ModelStore.file(m, "encoder.onnx")
                    decoder = ModelStore.file(m, "decoder.onnx")
                    joiner = ModelStore.file(m, "joiner.onnx")
                }
                model.modelType = "nemo_transducer"
            }
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
            }
            modelConfig = model
            decodingMethod = "greedy_search"
        }
        return OfflineRecognizer(null, config).also {
            recognizer = it
            loadedId = m.id
        }
    }

    @Synchronized
    fun transcribe(m: LocalModel, samples: FloatArray): String {
        val rec = ensureLoaded(m)
        return Pcm.split(samples).joinToString(" ") { piece ->
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(piece, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    @Synchronized
    fun unload() {
        recognizer?.release()
        recognizer = null
        loadedId = null
    }
}

/** Speech-to-text and text cleanup through an OpenAI-compatible HTTP API. */
object CloudClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = Prefs.apiBase.trim().trimEnd('/') + path

    private fun send(req: Request.Builder): JSONObject {
        http.newCall(req.header("Authorization", "Bearer ${Prefs.apiKey.trim()}").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException(msg ?: "HTTP ${r.code}")
            }
            return JSONObject(body)
        }
    }

    fun transcribe(wav: ByteArray): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", Prefs.sttModel.trim())
            .addFormDataPart("response_format", "json")
        Prefs.language.trim().takeIf { it.isNotEmpty() }?.let { form.addFormDataPart("language", it) }
        // Whisper treats the prompt as preceding text, which nudges it toward these spellings.
        Prefs.vocabulary.trim().takeIf { it.isNotEmpty() }?.let { form.addFormDataPart("prompt", it) }
        return send(Request.Builder().url(url("/audio/transcriptions")).post(form.build())).optString("text").trim()
    }

    fun cleanup(text: String): String {
        var system = Prefs.cleanupPrompt.ifBlank { DEFAULT_CLEANUP_PROMPT }
        Prefs.vocabulary.trim().takeIf { it.isNotEmpty() }?.let {
            system += "\n\nUse these spellings for names and terms when they are what the speaker meant: $it"
        }
        val payload = JSONObject()
            .put("model", Prefs.cleanupModel.trim())
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", "<transcript>\n$text\n</transcript>")),
            )
        // gpt-oss models "think" before answering; cleanup doesn't need it and it costs latency.
        if ("gpt-oss" in Prefs.cleanupModel) payload.put("reasoning_effort", "low")
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val out = send(Request.Builder().url(url("/chat/completions")).post(body))
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        return out.replace(Regex("</?transcript>"), "").trim()
    }
}

private const val TAG = "TalkType"

/** Recording -> text, using whichever engine is selected, plus optional AI cleanup. */
object Pipeline {
    data class Output(val text: String, val note: String? = null)

    private val cloudReady get() = Prefs.apiKey.isNotBlank()

    /** A user-facing reason dictation can't start, or null when everything is set up. */
    fun readinessProblem(): String? = when {
        Prefs.engine == ENGINE_CLOUD && !cloudReady -> "Add an API key in TalkType to use the cloud engine"
        Prefs.engine == ENGINE_LOCAL && ModelStore.active() == null -> "Download a speech model in TalkType first"
        else -> null
    }

    /** Starts loading the on-device model so it is ready by the time the user stops talking. */
    fun warmUp() {
        if (Prefs.engine == ENGINE_LOCAL) ModelStore.active()?.let { LocalTranscriber.ensureLoaded(it) }
    }

    fun run(pcm: ShortArray): Output {
        val started = SystemClock.elapsedRealtime()
        val audio = "%.1f".format(pcm.size / SAMPLE_RATE.toFloat())
        val raw = if (Prefs.engine == ENGINE_CLOUD) {
            CloudClient.transcribe(Pcm.toWav(pcm))
        } else {
            val model = ModelStore.active() ?: throw IOException("no speech model downloaded")
            LocalTranscriber.transcribe(model, Pcm.toFloats(pcm))
        }
        val text = finish(tidy(raw))
        val transcribed = SystemClock.elapsedRealtime()
        if (text.isEmpty() || !Prefs.cleanup || !cloudReady) {
            Log.i(TAG, "timing: audio=${audio}s engine=${Prefs.engine} transcribe=${transcribed - started}ms cleanup=off")
            return Output(text)
        }
        return try {
            val cleaned = CloudClient.cleanup(text).ifBlank { text }
            Log.i(TAG, "timing: audio=${audio}s engine=${Prefs.engine} transcribe=${transcribed - started}ms cleanup=${SystemClock.elapsedRealtime() - transcribed}ms")
            Output(finish(cleaned))
        } catch (e: Exception) {
            Log.w(TAG, "cleanup failed with model ${Prefs.cleanupModel}: ${e.message}")
            Output(text, "AI cleanup failed: ${e.message}")
        }
    }

    /** Local formatting applied to every transcript: digits for numbers, then the user's corrections. */
    private fun finish(text: String) = applyReplacements(if (Prefs.digits) Numbers.toDigits(text) else text)

    /**
     * Applies the user's "heard -> meant" rules: whole words or phrases, ignoring case. Runs on
     * every engine, so it also fixes words the on-device models have no way to learn.
     */
    fun applyReplacements(text: String, rules: String = Prefs.replacements): String {
        var out = text
        for (line in rules.lines()) {
            val parts = line.split("->", "=>", "=", limit = 2)
            if (parts.size != 2) continue
            val heard = parts[0].trim()
            val meant = parts[1].trim()
            if (heard.isEmpty()) continue
            val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(heard) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            out = pattern.replace(out, Regex.escapeReplacement(meant))
        }
        return out
    }

    /** Drops non-speech markers such as "[BLANK_AUDIO]" or "(music)" that Whisper emits on silence. */
    private fun tidy(s: String) =
        s.replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
}
