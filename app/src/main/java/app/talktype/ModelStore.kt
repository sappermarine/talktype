package app.talktype

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

enum class ModelKind { WHISPER, PARAKEET }

data class ModelFile(val remote: String, val local: String)

data class LocalModel(
    val id: String,
    val name: String,
    val blurb: String,
    val sizeMb: Int,
    val kind: ModelKind,
    val repo: String,
    val files: List<ModelFile>,
    /** Whisper language code; empty = auto-detect. */
    val language: String = "",
)

private fun whisper(id: String, name: String, blurb: String, sizeMb: Int, variant: String, language: String) =
    LocalModel(
        id, name, blurb, sizeMb, ModelKind.WHISPER, "csukuangfj/sherpa-onnx-whisper-$variant",
        listOf(
            ModelFile("$variant-encoder.int8.onnx", "encoder.onnx"),
            ModelFile("$variant-decoder.int8.onnx", "decoder.onnx"),
            ModelFile("$variant-tokens.txt", "tokens.txt"),
        ),
        language,
    )

private fun parakeet(id: String, name: String, blurb: String, sizeMb: Int, version: String) =
    LocalModel(
        id, name, blurb, sizeMb, ModelKind.PARAKEET, "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-$version-int8",
        listOf(
            ModelFile("encoder.int8.onnx", "encoder.onnx"),
            ModelFile("decoder.int8.onnx", "decoder.onnx"),
            ModelFile("joiner.int8.onnx", "joiner.onnx"),
            ModelFile("tokens.txt", "tokens.txt"),
        ),
    )

/** Downloads and tracks the on-device speech models (stored in app-private storage). */
object ModelStore {
    val catalog = listOf(
        parakeet("parakeet-v2", "Parakeet v2", "English. Best accuracy, adds punctuation, very fast.", 661, "v2"),
        whisper("whisper-base-en", "Whisper Base", "English. Small download, good for short dictation.", 161, "base.en", "en"),
        whisper("whisper-small-en", "Whisper Small", "English. More accurate than Base, slower.", 375, "small.en", "en"),
        parakeet("parakeet-v3", "Parakeet v3", "25 European languages, auto-detected.", 671, "v3"),
        whisper("whisper-small", "Whisper Small (multilingual)", "99 languages, auto-detected.", 375, "small", ""),
    )

    /** id -> fraction downloaded (0..1) for in-flight downloads. */
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val installed = MutableStateFlow<Set<String>>(emptySet())
    val lastError = MutableStateFlow<String?>(null)

    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val http = OkHttpClient()

    fun init(ctx: Context) {
        if (::root.isInitialized) return
        root = File(ctx.applicationContext.filesDir, "models")
        root.mkdirs()
        refresh()
    }

    fun byId(id: String) = catalog.firstOrNull { it.id == id }
    fun dir(m: LocalModel) = File(root, m.id)
    fun file(m: LocalModel, local: String): String = File(dir(m), local).absolutePath
    fun isInstalled(m: LocalModel) = File(dir(m), ".complete").exists()

    /** The model dictation should use: the selected one if installed, else any installed one. */
    fun active(): LocalModel? =
        byId(Prefs.localModel)?.takeIf { isInstalled(it) } ?: catalog.firstOrNull { isInstalled(it) }

    private fun refresh() {
        installed.value = catalog.filter { isInstalled(it) }.map { it.id }.toSet()
    }

    @Synchronized
    fun download(m: LocalModel) {
        if (jobs[m.id]?.isActive == true || isInstalled(m)) return
        lastError.value = null
        progress.update { it + (m.id to 0f) }
        jobs[m.id] = scope.launch {
            try {
                val total = m.sizeMb * 1_000_000L
                var done = 0L
                dir(m).mkdirs()
                for (f in m.files) {
                    val url = "https://huggingface.co/${m.repo}/resolve/main/${f.remote}"
                    fetch(url, File(dir(m), f.local)) { n ->
                        done += n
                        progress.update { it + (m.id to (done.toFloat() / total).coerceIn(0f, 0.99f)) }
                    }
                }
                File(dir(m), ".complete").createNewFile()
                if (Prefs.localModel.isEmpty()) Prefs.localModel = m.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = "Download of ${m.name} failed: ${e.message}. Tap Download to resume."
            } finally {
                progress.update { it - m.id }
                refresh()
            }
        }
    }

    @Synchronized
    fun cancel(m: LocalModel) {
        jobs.remove(m.id)?.cancel()
    }

    fun delete(m: LocalModel) {
        cancel(m)
        LocalTranscriber.unload()
        dir(m).deleteRecursively()
        refresh()
    }

    /** Downloads [url] to [dest], resuming a partial file if one exists. */
    private suspend fun fetch(url: String, dest: File, onBytes: (Long) -> Unit) {
        if (dest.exists()) {
            onBytes(dest.length())
            return
        }
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val append = r.code == 206
            if (!append) have = 0
            onBytes(have)
            FileOutputStream(part, append).use { out ->
                val src = r.body!!.byteStream()
                val buf = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
        }
        if (!part.renameTo(dest)) throw IOException("could not save ${dest.name}")
    }
}
