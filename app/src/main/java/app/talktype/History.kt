package app.talktype

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File

/** The most recent dictations, kept on the device so nothing is lost if insertion fails. */
object History {
    private const val MAX = 50
    private lateinit var file: File
    val items = MutableStateFlow<List<String>>(emptyList())

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.applicationContext.filesDir, "history.json")
        items.value = runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(text: String) {
        items.value = (listOf(text) + items.value).take(MAX)
        save()
    }

    @Synchronized
    fun clear() {
        items.value = emptyList()
        save()
    }

    private fun save() {
        runCatching { file.writeText(JSONArray(items.value).toString()) }
    }
}
