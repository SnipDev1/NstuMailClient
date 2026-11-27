package ru.nstu.mail.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nstu.mail.data.Credentials
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.SystemFolderKind
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.applyFolderUnreadDeltas
import ru.nstu.mail.data.shouldBatchMarkAsRead
import java.nio.file.Files

class DesktopStorageTest {

    @Test
    fun `DesktopCredentialStore encrypts roundtrip and clears credentials`() {
        val tempDir = Files.createTempDirectory("nstumail-cred-test").toFile()
        try {
            val store = DesktopCredentialStore(tempDir)
            assertNull(store.load())

            val creds = Credentials("student@stud.nstu.ru", "p@ssw0rd!#123", "https://mail2.nstu.ru")
            store.save(creds)

            val loaded = store.load()
            assertEquals(creds, loaded)

            // Stored file must not contain plaintext password
            val rawCredBytes = tempDir.resolve("credentials.bin").readBytes()
            assertFalse(rawCredBytes.toString(Charsets.UTF_8).contains("p@ssw0rd"))

            store.clear()
            assertNull(store.load())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `DesktopSettingsStore persists theme, light body toggle, templates and rules`() {
        val tempDir = Files.createTempDirectory("nstumail-settings-test").toFile()
        try {
            val store = DesktopSettingsStore(tempDir)
            val saved = DesktopSettings(
                themeMode = DesktopThemeMode.DARK,
                alwaysLightEmailBody = true,
                notificationsEnabled = false,
                mutedFolderIds = setOf("f-1", "inbox"),
                mutedSenders = setOf("spam@example.com"),
                tagTemplates = listOf("Учёба", "Учёба/ПЗ", "Вопрос"),
                tagSortRules = listOf(TagSortRule("Учёба/ПЗ", "f-pz", "ПЗ", true)),
                inboxFolderId = "f-inbox",
                updateChannel = DesktopUpdateChannel.TEST
            )
            store.save(saved)

            val loaded = store.load()
            assertEquals(DesktopThemeMode.DARK, loaded.themeMode)
            assertTrue(loaded.alwaysLightEmailBody)
            assertFalse(loaded.notificationsEnabled)
            assertEquals(setOf("f-1", "inbox"), loaded.mutedFolderIds)
            assertEquals(setOf("spam@example.com"), loaded.mutedSenders)
            assertEquals(listOf("Вопрос", "Учёба", "Учёба/ПЗ"), loaded.tagTemplates)
            assertEquals(1, loaded.tagSortRules.size)
            assertEquals("f-pz", loaded.tagSortRules[0].targetFolderId)
            assertEquals("f-inbox", loaded.inboxFolderId)
            assertEquals(DesktopUpdateChannel.TEST, loaded.updateChannel)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `DesktopUpdater picks highest semver prerelease regardless of array order`() {
        val json = """
            [
              {
                "tag_name": "v1.0.6-test.9",
                "draft": false,
                "prerelease": true,
                "body": "Older test",
                "html_url": "https://github.com/SnipDev1/NstuMailClient/releases/tag/v1.0.6-test.9",
                "assets": []
              },
              {
                "tag_name": "v1.0.6-test.13",
                "draft": false,
                "prerelease": true,
                "body": "Newest test",
                "html_url": "https://github.com/SnipDev1/NstuMailClient/releases/tag/v1.0.6-test.13",
                "assets": []
              },
              {
                "tag_name": "v1.0.7-alpha.2",
                "draft": false,
                "prerelease": true,
                "body": "Alpha",
                "html_url": "https://github.com/SnipDev1/NstuMailClient/releases/tag/v1.0.7-alpha.2",
                "assets": []
              },
              {
                "tag_name": "v1.0.7-beta.1",
                "draft": false,
                "prerelease": true,
                "body": "Beta",
                "html_url": "https://github.com/SnipDev1/NstuMailClient/releases/tag/v1.0.7-beta.1",
                "assets": []
              }
            ]
        """.trimIndent()

        val best = DesktopUpdater().parseReleaseList(json, currentVersion = "1.0.6-test.5", allowPrerelease = true)
        assertEquals("v1.0.7-beta.1", best?.tagName)
        assertNull(DesktopUpdater().parseReleaseList(json, currentVersion = "1.0.7", allowPrerelease = true))
    }

    @Test
    fun `fresh install has no default tag templates and legacy defaults purge once on upgrade`() {
        val tempDir = Files.createTempDirectory("nstumail-empty-tpl").toFile()
        try {
            assertTrue(DesktopSettings().tagTemplates.isEmpty())
            val store = DesktopSettingsStore(tempDir)
            assertTrue(store.load().tagTemplates.isEmpty())

            // Simulate pre-v1.0.10 settings.json containing old saved defaults without legacyDefaultsPurged flag
            tempDir.resolve("settings.json").writeText(
                """
                {
                  "tagTemplates": ["Важно", "Учёба", "Учёба/Лаба", "Учёба/Лекция", "Учёба/ПЗ", "Проект"],
                  "tagSortRules": []
                }
                """.trimIndent(),
                Charsets.UTF_8
            )
            val migrated = store.load()
            assertEquals(listOf("Проект"), migrated.tagTemplates)

            // User explicitly adds "Важно" after migration -> must stay
            store.save(migrated.copy(tagTemplates = listOf("Важно", "Проект")))
            assertEquals(listOf("Важно", "Проект"), store.load().tagTemplates)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `parseEmailHtmlToBlocks extracts headings, formatted text, links, lists, tables, and images`() {
        val raw = """
            <h2>Заголовок</h2>
            <p style="color:#555555">Обычный <b style="color:#b71c1c">важный</b> текст с <a href="https://nstu.ru">ссылкой</a></p>
            <blockquote>Цитата письма</blockquote>
            <ul><li>Пункт 1</li><li>Пункт 2</li></ul>
            <table><tr><th>Колонка А</th><th>Колонка Б</th></tr><tr><td>1</td><td>2</td></tr></table>
            <img src="data:image/png;base64,iVBORw0KGgo=" alt="logo"/>
        """.trimIndent()
        val sanitized = ru.nstu.mail.data.EmailHtmlSanitizer.renderEmailDocument(
            body = raw,
            isHtml = true,
            darkMode = false,
            bgHex = "#FFFFFF",
            textHex = "#000000",
            linkHex = "#006B47"
        )
        val blocks = parseEmailHtmlToBlocks(
            sanitizedHtmlDoc = sanitized,
            defaultTextColor = androidx.compose.ui.graphics.Color.Black,
            linkColor = androidx.compose.ui.graphics.Color(0xFF006B47),
            onOpenUrl = {}
        )
        assertTrue(blocks.any { it is EmailBlock.RichText && it.text.text.contains("Заголовок") })
        assertTrue(blocks.any { it is EmailBlock.RichText && it.text.text.contains("важный") && it.text.text.contains("ссылкой") })
        assertTrue(blocks.any { it is EmailBlock.Quote })
        assertEquals(2, blocks.count { it is EmailBlock.BulletItem })
        val tbl = blocks.filterIsInstance<EmailBlock.TableBlock>().single()
        assertEquals(2, tbl.rows.size)
        assertEquals(2, tbl.rows[0].size)
        assertTrue(blocks.any { it is EmailBlock.ImageBlock && it.alt == "logo" })
    }

    @Test
    fun `appendRecipientEmail appends cleanly without overwriting prior comma-separated recipients`() {
        assertEquals("ivan@nstu.ru", appendRecipientEmail("iv", "ivan@nstu.ru"))
        assertEquals(
            "a@nstu.ru, ivan@nstu.ru",
            appendRecipientEmail("a@nstu.ru, iv", "ivan@nstu.ru")
        )
        assertEquals(
            "a@nstu.ru, b@nstu.ru",
            appendRecipientEmail("a@nstu.ru; b@nstu.ru; B", "b@nstu.ru")
        )
        assertEquals(
            "a@nstu.ru, b@nstu.ru",
            appendRecipientEmail("a@nstu.ru, ", "b@nstu.ru")
        )
    }

    @Test
    fun `applyFolderUnreadDeltas updates affected folders, propagates to parent, and clamps at zero`() {
        val folders = listOf(
            MailFolder(id = "inbox", changeKey = "1", parentId = "", displayName = "Входящие", totalCount = 10, unreadCount = 2, systemKind = SystemFolderKind.INBOX),
            MailFolder(id = "parent", changeKey = "2", parentId = "", displayName = "Родитель", totalCount = 6, unreadCount = 4),
            MailFolder(id = "sub", changeKey = "3", parentId = "parent", displayName = "Подпапка", totalCount = 4, unreadCount = 3),
            MailFolder(id = "other", changeKey = "4", parentId = "", displayName = "Другая", totalCount = 5, unreadCount = 1)
        )
        val updated = applyFolderUnreadDeltas(
            folders,
            mapOf("inbox" to -5, "sub" to 2, "other" to 0, "missing" to -1)
        )
        assertEquals(0, updated[0].unreadCount)
        assertEquals(6, updated[1].unreadCount)
        assertEquals(5, updated[2].unreadCount)
        assertEquals(1, updated[3].unreadCount)
    }

    @Test
    fun `shouldBatchMarkAsRead returns true only when at least one selected item is unread`() {
        val items = listOf(
            EmailSummary("1", "ck1", "s1", "a", "a@nstu.ru", "2026-01-01T00:00:00Z", isRead = false, hasAttachments = false, preview = ""),
            EmailSummary("2", "ck2", "s2", "b", "b@nstu.ru", "2026-01-01T00:00:00Z", isRead = true, hasAttachments = false, preview = "")
        )
        assertFalse(shouldBatchMarkAsRead(items, emptySet()))
        assertTrue(shouldBatchMarkAsRead(items, setOf("1", "2")))
        assertTrue(shouldBatchMarkAsRead(items, setOf("1")))
        assertFalse(shouldBatchMarkAsRead(items, setOf("2")))
    }
}
