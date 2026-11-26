package ru.nstu.mail.data

const val DEFAULT_SERVER_URL = "https://mail2.nstu.ru"

data class Credentials(
    val email: String,
    val password: String,
    val serverUrl: String = DEFAULT_SERVER_URL
)

enum class MailFolderType(val ewsId: String, val labelRu: String) {
    INBOX("inbox", "Входящие"),
    SENT("sentitems", "Отправленные"),
    DRAFTS("drafts", "Черновики"),
    DELETED("deleteditems", "Удалённые"),
    JUNK("junkemail", "Спам")
}

data class FolderInfo(
    val type: MailFolderType,
    val displayName: String,
    val totalCount: Int,
    val unreadCount: Int
)

data class EmailSummary(
    val id: String,
    val changeKey: String,
    val subject: String,
    val senderName: String,
    val senderEmail: String,
    val receivedAt: String,
    val isRead: Boolean,
    val hasAttachments: Boolean,
    val preview: String
)

data class AttachmentInfo(
    val id: String,
    val name: String,
    val contentType: String,
    val size: Long,
    val isInline: Boolean
)

data class EmailDetail(
    val id: String,
    val changeKey: String,
    val subject: String,
    val from: String,
    val fromEmail: String,
    val to: List<String>,
    val cc: List<String>,
    val receivedAt: String,
    val body: String,
    val isHtml: Boolean,
    val isRead: Boolean,
    val attachments: List<AttachmentInfo>
)

data class DownloadedAttachment(
    val name: String,
    val contentType: String,
    val bytes: ByteArray
)

class EwsException(
    message: String,
    val isAuthError: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)
