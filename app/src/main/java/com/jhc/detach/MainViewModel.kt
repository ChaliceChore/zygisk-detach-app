package com.jhc.detach

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DETACH = "/data/adb/modules/zygisk-detach/detach"

data class AppEntry(
    val packageName: String,
    val label: String,
    val installed: Boolean = true,
    val system: Boolean = false
)

enum class AppType { All, User, System }

sealed interface LoadState {
    data object Loading : LoadState
    data class Error(val title: String, val message: String) : LoadState
    data object Ready : LoadState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var loadState by mutableStateOf<LoadState>(LoadState.Loading)
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    /** What the module currently has on disk. */
    var savedDetached by mutableStateOf<Set<String>>(emptySet())
        private set

    /** What the user has selected, possibly not applied yet. */
    var detached by mutableStateOf<Set<String>>(emptySet())
        private set
    var applying by mutableStateOf(false)
        private set

    var query by mutableStateOf("")
    var onlyDetached by mutableStateOf(false)
    var appType by mutableStateOf(AppType.All)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val pendingChanges: Int
        get() = (detached - savedDetached).size + (savedDetached - detached).size

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loadState = LoadState.Loading
            when (val result = withContext(Dispatchers.IO) { loadApps() }) {
                is LoadState.Error -> loadState = result
                is Loaded -> {
                    apps = result.apps
                    savedDetached = result.detached
                    detached = result.detached
                    loadState = LoadState.Ready
                }
            }
        }
    }

    private class Loaded(val apps: List<AppEntry>, val detached: Set<String>)

    /** Runs off the main thread; returns either [Loaded] or [LoadState.Error]. */
    private fun loadApps(): Any {
        val shell = Shell.getShell()
        if (!shell.isRoot) {
            // libsu caches the shell; drop it so Retry asks for root again.
            shell.close()
            return LoadState.Error(
                "Root access required",
                "Grant root access to zygisk-detach in your root manager, then retry."
            )
        }
        if (Shell.cmd("test -f $DETACH").exec().code != 0) {
            return LoadState.Error(
                "Module not found",
                "The zygisk-detach module is not installed or is disabled."
            )
        }
        val list = Shell.cmd("$DETACH list").exec()
        if (list.code != 0) {
            return LoadState.Error(
                "Could not read the detach list",
                list.err.joinToString("\n").ifBlank { "detach list exited with code ${list.code}" }
            )
        }
        val alDetach = list.out.filter { it.isNotBlank() }.toSet()

        val pm = getApplication<Application>().packageManager
        val installed = pm.getInstalledPackages(0).mapNotNull {
            it.applicationInfo?.let { info ->
                AppEntry(
                    it.packageName,
                    pm.getApplicationLabel(info).toString(),
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
        }
        val installedNames = installed.mapTo(HashSet()) { it.packageName }
        val missing = alDetach.filter { it !in installedNames }
            .map { AppEntry(it, it, installed = false) }

        return Loaded((installed + missing).sortedBy { it.label.lowercase() }, alDetach)
    }

    fun toggle(packageName: String, checked: Boolean) {
        detached = if (checked) detached + packageName else detached - packageName
    }

    fun discard() {
        detached = savedDetached
    }

    fun apply() {
        if (applying) return
        val target = detached
        viewModelScope.launch {
            applying = true
            val result = withContext(Dispatchers.IO) {
                if (target.isEmpty()) Shell.cmd("$DETACH reset").exec()
                else Shell.cmd("$DETACH detachall ${target.joinToString(" ")}").exec()
            }
            applying = false
            if (result.code == 0) {
                savedDetached = target
                _messages.send(
                    if (target.isEmpty()) "Detach list cleared"
                    else "Detached ${target.size} app${if (target.size == 1) "" else "s"}"
                )
            } else {
                _messages.send("Error: " + result.err.joinToString("\n"))
            }
        }
    }
}
