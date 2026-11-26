package ru.nstu.mail

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nstu.mail.data.AppUpdater

class AppUpdaterTest {

    @Test
    fun `isNewerVersion compares semantic version numbers properly`() {
        assertTrue(AppUpdater.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(AppUpdater.isNewerVersion("1.10.0", "1.2.0"))
        assertTrue(AppUpdater.isNewerVersion("v2.0.0-rc1", "1.9.9"))
        assertFalse(AppUpdater.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdater.isNewerVersion("0.9.9", "1.0.0"))
        assertFalse(AppUpdater.isNewerVersion("invalid", "1.0.0"))
    }

    @Test
    fun `isTrustedGithubApkUrl allows only github https origins without userinfo`() {
        assertTrue(
            AppUpdater.isTrustedGithubApkUrl(
                "https://github.com/SnipDev1/NstuMailApk/releases/download/v1.0.1/app-release.apk"
            )
        )
        assertTrue(
            AppUpdater.isTrustedGithubApkUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/123/app.apk"
            )
        )
        assertFalse(AppUpdater.isTrustedGithubApkUrl("http://github.com/SnipDev1/NstuMailApk/app.apk"))
        assertFalse(AppUpdater.isTrustedGithubApkUrl("https://evil.com/app.apk"))
        assertFalse(AppUpdater.isTrustedGithubApkUrl("https://github.com.evil.com/app.apk"))
        assertFalse(AppUpdater.isTrustedGithubApkUrl("https://user@github.com/app.apk"))
    }

    @Test
    fun `parseReleaseJson selects release apk and ignores drafts or older versions`() {
        val validJson = """
            {
              "tag_name": "v1.1.0",
              "draft": false,
              "prerelease": false,
              "body": "Исправлены ошибки входа",
              "assets": [
                {
                  "name": "app-debug.apk",
                  "size": 17000000,
                  "browser_download_url": "https://github.com/SnipDev1/NstuMailApk/releases/download/v1.1.0/app-debug.apk"
                },
                {
                  "name": "NstuMail-1.1.0.apk",
                  "size": 12000000,
                  "browser_download_url": "https://github.com/SnipDev1/NstuMailApk/releases/download/v1.1.0/NstuMail-1.1.0.apk"
                }
              ]
            }
        """.trimIndent()

        val info = AppUpdater.parseReleaseJson(validJson, "1.0.0")
        assertNotNull(info)
        assertEquals("1.1.0", info!!.versionName)
        assertEquals("NstuMail-1.1.0.apk", info.apkName)
        assertEquals(12000000L, info.apkSizeBytes)
        assertEquals("Исправлены ошибки входа", info.releaseNotes)

        assertNull(AppUpdater.parseReleaseJson(validJson, "1.1.0"))
        assertNull(AppUpdater.parseReleaseJson(validJson.replace("\"draft\": false", "\"draft\": true"), "1.0.0"))
        assertNull(AppUpdater.parseReleaseJson(validJson.replace("\"prerelease\": false", "\"prerelease\": true"), "1.0.0"))
        assertNull(AppUpdater.parseReleaseJson(validJson.replace("https://github.com", "https://evil.com"), "1.0.0"))
    }

    @Test
    fun `checkLatestRelease queries GitHub API and handles 404`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "tag_name": "v1.0.2",
                      "draft": false,
                      "prerelease": false,
                      "body": "Release notes",
                      "assets": [
                        {
                          "name": "NstuMail-v1.0.2.apk",
                          "size": 1024,
                          "browser_download_url": "https://github.com/SnipDev1/NstuMailApk/releases/download/v1.0.2/NstuMail-v1.0.2.apk"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(MockResponse().setResponseCode(404))

            val updater = AppUpdater(
                repo = "SnipDev1/NstuMailApk",
                apiBaseUrl = server.url("/").toString().trimEnd('/')
            )

            val found = updater.checkLatestRelease("1.0.0")
            assertNotNull(found)
            assertEquals("v1.0.2", found!!.tagName)

            val req = server.takeRequest()
            assertEquals("/repos/SnipDev1/NstuMailApk/releases/latest", req.path)

            assertNull(updater.checkLatestRelease("1.0.0"))
        } finally {
            server.shutdown()
        }
    }
}
