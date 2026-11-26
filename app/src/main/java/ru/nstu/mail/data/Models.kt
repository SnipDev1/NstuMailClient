package ru.nstu.mail.data

const val DEFAULT_SERVER_URL = "https://mail2.nstu.ru"

data class Credentials(
    val email: String,
    val password: String,
    val serverUrl: String = DEFAULT_SERVER_URL
)

enum class SystemFolderKind(val ewsId: String, val labelRu: String, val sortOrder: Int) {
    INBOX("inbox", "Входящие", 0),
    DRAFTS("drafts", "Черновики", 1),
    SENT("sentitems", "Отправленные", 2),
    OUTBOX("outbox", "Исходящие", 3),
    JUNK("junkemail", "Спам", 4),
    DELETED("deleteditems", "Удалённые", 5)
}

data class MailFolder(
    val id: String,
    val changeKey: String,
    val parentId: String,
    val displayName: String,
    val totalCount: Int,
    val unreadCount: Int,
    val childFolderCount: Int = 0,
    val depth: Int = 0,
    val systemKind: SystemFolderKind? = null
) {
    val isSystem: Boolean get() = systemKind != null
}

enum class MailFilter(val labelRu: String) {
    ALL("Все"),
    UNREAD("Непрочитанные"),
    FLAGGED("С флажком"),
    ATTACHMENTS("С вложениями")
}

enum class EmailImportance(val ewsValue: String, val labelRu: String) {
    LOW("Low", "Низкая"),
    NORMAL("Normal", "Обычная"),
    HIGH("High", "Высокая");

    companion object {
        fun fromEws(raw: String): EmailImportance =
            entries.firstOrNull { it.ewsValue.equals(raw.trim(), ignoreCase = true) } ?: NORMAL
    }
}

data class EmailSummary(
    val id: String,
    val changeKey: String,
    val subject: String,
    val senderName: String,
    val senderEmail: String,
    val receivedAt: String,
    val isRead: Boolean,
    val hasAttachments: Boolean,
    val preview: String,
    val isFlagged: Boolean = false,
    val importance: EmailImportance = EmailImportance.NORMAL
)

data class AttachmentInfo(
    val id: String,
    val name: String,
    val contentType: String,
    val size: Long,
    val isInline: Boolean,
    val contentId: String = ""
)

data class EmailDetail(
    val id: String,
    val changeKey: String,
    val subject: String,
    val from: String,
    val fromEmail: String,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String> = emptyList(),
    val receivedAt: String,
    val body: String,
    val isHtml: Boolean,
    val isRead: Boolean,
    val isFlagged: Boolean = false,
    val importance: EmailImportance = EmailImportance.NORMAL,
    val isDraft: Boolean = false,
    val attachments: List<AttachmentInfo>
)

data class DownloadedAttachment(
    val name: String,
    val contentType: String,
    val bytes: ByteArray
)

data class OutgoingAttachment(
    val name: String,
    val contentType: String,
    val bytes: ByteArray
)

data class ContactEntry(
    val id: String = "",
    val changeKey: String = "",
    val name: String,
    val email: String,
    val isFromDirectory: Boolean = false
)

data class CalendarEvent(
    val id: String,
    val changeKey: String,
    val subject: String,
    val startIso: String,
    val endIso: String,
    val location: String,
    val isAllDay: Boolean
)

class EwsException(
    message: String,
    val isAuthError: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)
