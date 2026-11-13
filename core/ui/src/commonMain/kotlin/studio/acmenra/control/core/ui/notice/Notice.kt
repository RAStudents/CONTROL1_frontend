package studio.acmenra.control.core.ui.notice

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

enum class NoticeType { Info, Success, Error, Offline }

object NoticeDefaults {
    val MaxVisibleNotices = 3
    val InfoDurationMs = 4000L
    val ErrorDurationMs = 6000L
    val EnterScale = 0.92f
    val PulseScale = 1.12f
    val PulseDurationMs = 900
    val TimerAlpha = 0.35f

    // null means the notice stays until dismissed
    fun durationFor(type: NoticeType): Long? =
        when (type) {
            NoticeType.Offline -> null
            NoticeType.Error -> ErrorDurationMs
            NoticeType.Info, NoticeType.Success -> InfoDurationMs
        }
}

// Resource for strings from composeResources, Raw only for text that came from the server
sealed interface NoticeText {
    data class Resource(
        val resource: StringResource,
        val args: List<Any> = emptyList(),
    ) : NoticeText

    data class Raw(
        val value: String,
    ) : NoticeText
}

@Composable
internal fun NoticeText.resolve(): String =
    when (this) {
        is NoticeText.Resource -> stringResource(resource, *args.toTypedArray())
        is NoticeText.Raw -> value
    }

val Notice.isPersistent: Boolean
    get() = durationMs == null

// Lives in a State field, is shown through ConsumeNotice and then reset by the ViewModel.
// key dedupes: a notice with the same key is ignored while one is current or queued.
// id defaults to a positive unique ID generated automatically.
data class Notice(
    val type: NoticeType,
    val title: NoticeText,
    val message: NoticeText? = null,
    val durationMs: Long? = NoticeDefaults.durationFor(type),
    val key: String? = null,
    val id: Long = Random.nextLong(1L, Long.MAX_VALUE),
) {
    companion object {
        fun info(
            title: StringResource,
            message: StringResource? = null,
            key: String? = null,
            id: Long = Random.nextLong(1L, Long.MAX_VALUE),
        ) = Notice(
            type = NoticeType.Info,
            title = NoticeText.Resource(title),
            message = message?.let { NoticeText.Resource(it) },
            key = key,
            id = id,
        )

        fun success(
            title: StringResource,
            message: StringResource? = null,
            key: String? = null,
            id: Long = Random.nextLong(1L, Long.MAX_VALUE),
        ) = Notice(
            type = NoticeType.Success,
            title = NoticeText.Resource(title),
            message = message?.let { NoticeText.Resource(it) },
            key = key,
            id = id,
        )

        fun error(
            title: StringResource,
            message: StringResource? = null,
            key: String? = null,
            id: Long = Random.nextLong(1L, Long.MAX_VALUE),
        ) = Notice(
            type = NoticeType.Error,
            title = NoticeText.Resource(title),
            message = message?.let { NoticeText.Resource(it) },
            key = key,
            id = id,
        )

        fun error(
            title: StringResource,
            message: StringResource,
            args: List<Any>,
            key: String? = null,
            id: Long = Random.nextLong(1L, Long.MAX_VALUE),
        ) = Notice(
            type = NoticeType.Error,
            title = NoticeText.Resource(title),
            message = NoticeText.Resource(message, args),
            key = key,
            id = id,
        )

        fun error(
            title: StringResource,
            serverMessage: String,
            key: String? = null,
            id: Long = Random.nextLong(1L, Long.MAX_VALUE),
        ) = Notice(
            type = NoticeType.Error,
            title = NoticeText.Resource(title),
            message = NoticeText.Raw(serverMessage),
            key = key,
            id = id,
        )

        fun offline(
            title: StringResource,
            message: StringResource? = null,
            key: String? = null,
            id: Long = Random.nextLong(1L, Long.MAX_VALUE),
        ) = Notice(
            type = NoticeType.Offline,
            title = NoticeText.Resource(title),
            message = message?.let { NoticeText.Resource(it) },
            durationMs = null,
            key = key,
            id = id,
        )
    }
}

// Single instance per app (Koin single). Thread-safe.
class NoticeController {
    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()

    // All live NoticeHost instances. Only the last one (topmost layer) renders the notice.
    private val _hosts = MutableStateFlow<List<Any>>(emptyList())
    val hosts: StateFlow<List<Any>> = _hosts.asStateFlow()

    fun show(notice: Notice): Long {
        val key = notice.key
        val candidateId = if (notice.id != 0L) notice.id else Random.nextLong(1L, Long.MAX_VALUE)
        val entry = if (notice.id == candidateId) notice else notice.copy(id = candidateId)
        var actualId = candidateId

        _notices.update { currentList ->
            if (key != null) {
                val existing = currentList.firstOrNull { it.key == key }
                if (existing != null) {
                    actualId = existing.id
                    return@update currentList
                }
            }
            actualId = entry.id
            val trimmed = trimForNewNotice(currentList, NoticeDefaults.MaxVisibleNotices)
            trimmed + entry
        }
        return actualId
    }

    fun dismiss(id: Long) {
        _notices.update { list -> list.filterNot { it.id == id } }
    }

    fun dismissKey(key: String) {
        _notices.update { list -> list.filterNot { it.key == key } }
    }

    internal fun attachHost(token: Any) {
        _hosts.update { it + token }
    }

    internal fun detachHost(token: Any) {
        _hosts.update { it - token }
    }

    internal fun trimForNewNotice(
        currentList: List<Notice>,
        maxVisible: Int,
    ): List<Notice> {
        if (currentList.size < maxVisible) return currentList
        val neededRemovals = currentList.size - maxVisible + 1
        var remainingToRemove = neededRemovals
        val mutable = currentList.toMutableList()

        // Phase 1: drop oldest non-persistent notices
        val iterator = mutable.iterator()
        while (iterator.hasNext() && remainingToRemove > 0) {
            val item = iterator.next()
            if (!item.isPersistent) {
                iterator.remove()
                remainingToRemove--
            }
        }

        // Phase 2: if still over limit (all visible are persistent), drop oldest persistent
        while (remainingToRemove > 0 && mutable.isNotEmpty()) {
            mutable.removeAt(0)
            remainingToRemove--
        }

        return mutable
    }
}
