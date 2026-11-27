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
import ru.nstu.mail.data.UpdateChannel

class AppUpdaterTest {

    @Test
    fun `isNewerVersion compares semantic version numbers properly`() {
        assertTrue(AppUpdater.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(AppUpdater.isNewerVersion("1.10.0", "1.2.0"))
        assertTrue(AppUpdater.isNewerVersion("v2.0.0-rc1", "1.9.9"))
        assertTrue(AppUpdater.isNewerVersion("v1.0.6-test.1", "1.0.5"))
        assertTrue(AppUpdater.isNewerVersion("v1.0.6-test.2", "1.0.6-test.1"))
        assertTrue(AppUpdater.isNewerVersion("v1.0.6-test.10", "1.0.6-test.2"))
        assertTrue(AppUpdater.isNewerVersion("v1.0.6", "1.0.6-test.2"))
        assertFalse(AppUpdater.isNewerVersion("v1.0.6-test.1", "1.0.6"))
        assertFalse(AppUpdater.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdater.isNewerVersion("0.9.9", "1.0.0"))
        assertFalse(AppUpdater.isNewerVersion("invalid", "1.0.0"))
    }

    @Test
    fun `isTrustedGithubApkUrl allows only github https origins without userinfo`() {
        assertTrue(
            AppUpdater.isTrustedGithubApkUrl(
                "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.0.1/app-release.apk"
            )
        )
        assertTrue(
            AppUpdater.isTrustedGithubApkUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/123/app.apk"
            )
        )
        assertFalse(AppUpdater.isTrustedGithubApkUrl("http://github.com/SnipDev1/NstuMailClient/app.apk"))
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
                  "browser_download_url": "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.1.0/app-debug.apk"
                },
                {
                  "name": "NstuMail-1.1.0.apk",
                  "size": 12000000,
                  "browser_download_url": "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.1.0/NstuMail-1.1.0.apk"
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
                          "browser_download_url": "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.0.2/NstuMail-v1.0.2.apk"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(MockResponse().setResponseCode(404))

            val updater = AppUpdater(
                repo = "SnipDev1/NstuMailClient",
                apiBaseUrl = server.url("/").toString().trimEnd('/')
            )

            val found = updater.checkLatestRelease("1.0.0")
            assertNotNull(found)
            assertEquals("v1.0.2", found!!.tagName)

            val req = server.takeRequest()
            assertEquals("/repos/SnipDev1/NstuMailClient/releases/latest", req.path)

            assertNull(updater.checkLatestRelease("1.0.0"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `test channel queries releases list and selects highest prerelease`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val releasesJson = """
                [
                  {
                    "tag_name": "v1.0.6-test.2",
                    "draft": false,
                    "prerelease": true,
                    "body": "Test 2",
                    "assets": [
                      {
                        "name": "NstuMail-v1.0.6-test.2.apk",
                        "size": 2048,
                        "browser_download_url": "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.0.6-test.2/NstuMail-v1.0.6-test.2.apk"
                      }
                    ]
                  },
                  {
                    "tag_name": "v1.0.6-test.1",
                    "draft": false,
                    "prerelease": true,
                    "body": "Test 1",
                    "assets": [
                      {
                        "name": "NstuMail-v1.0.6-test.1.apk",
                        "size": 1024,
                        "browser_download_url": "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.0.6-test.1/NstuMail-v1.0.6-test.1.apk"
                      }
                    ]
                  },
                  {
                    "tag_name": "v1.0.7-test.1",
                    "draft": true,
                    "prerelease": true,
                    "body": "Draft",
                    "assets": [
                      {
                        "name": "NstuMail-v1.0.7-test.1.apk",
                        "size": 1024,
                        "browser_download_url": "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.0.7-test.1/NstuMail-v1.0.7-test.1.apk"
                      }
                    ]
                  }
                ]
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody(releasesJson))

            val updater = AppUpdater(
                repo = "SnipDev1/NstuMailClient",
                apiBaseUrl = server.url("/").toString().trimEnd('/')
            )

            val found = updater.checkLatestRelease("1.0.5", UpdateChannel.TEST)
            assertNotNull(found)
            assertEquals("v1.0.6-test.2", found!!.tagName)
            assertTrue(found.isPrerelease)

            val req = server.takeRequest()
            assertEquals("/repos/SnipDev1/NstuMailClient/releases?per_page=50", req.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `test channel selects highest release even when GitHub returns releases out of semver order`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val releasesJson = """
                [
                  {
                    "tag_name": "v1.0.6-test.9",
                    "draft": false,
                    "prerelease": true,
                    "body": "Older test",
                    "assets": [
                      {
                        "name": "NstuMail-v1.0.6-test.9.apk",
                        "size": 1000,
                        "browser_download_url": "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.0.6-test.9/NstuMail-v1.0.6-test.9.apk"
                      }
                    ]
                  },
                  {
                    "tag_name": "v1.0.6-test.13",
                    "draft": false,
                    "prerelease": true,
                    "body": "Latest test",
                    "assets": [
                      {
                        "name": "NstuMail-v1.0.6-test.13.apk",
                        "size": 2000,
                        "browser_download_url": "https://github.com/SnipDev1/NstuMailClient/releases/download/v1.0.6-test.13/NstuMail-v1.0.6-test.13.apk"
                      }
                    ]
                  }
                ]
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody(releasesJson))

            val updater = AppUpdater(
                repo = "SnipDev1/NstuMailClient",
                apiBaseUrl = server.url("/").toString().trimEnd('/')
            )

            val found = updater.checkLatestRelease("1.0.6-test.5", UpdateChannel.TEST)
            assertNotNull(found)
            assertEquals("v1.0.6-test.13", found!!.tagName)
        } finally {
            server.shutdown()
        }
    }
}
