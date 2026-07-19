package com.example.flightlog.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseSourceTest {
    @Test fun normalizesPublicRepositoryUrl() {
        assertEquals(
            "https://api.github.com/repos/MangoLambda/FlightLog/releases/latest",
            GitHubReleaseSource.latestReleaseApiUrl("https://github.com/MangoLambda/FlightLog/"),
        )
        assertNull(GitHubReleaseSource.latestReleaseApiUrl("http://github.com/MangoLambda/FlightLog"))
    }

    @Test fun comparesSemanticVersions() {
        assertTrue(GitHubReleaseSource.compareVersions("1.2.0", "1.1.30") > 0)
        assertTrue(GitHubReleaseSource.compareVersions("v1.1.29", "1.1.30") < 0)
        assertEquals(0, GitHubReleaseSource.compareVersions("1.1.30", "v1.1.30"))
        assertEquals(0, GitHubReleaseSource.compareVersions("not-a-version", "1.1.30"))
    }

    @Test fun selectsFirstSupportedAbi() {
        val release = GitHubReleaseSource.parse(releaseJson(), listOf("x86_64", "arm64-v8a"))
        assertEquals("FlightLog-v1.2.0-x86_64.apk", release?.assetName)
        assertEquals(DIGEST, release?.sha256)
    }

    @Test fun fallsBackToUniversalApk() {
        val release = GitHubReleaseSource.parse(releaseJson(), listOf("riscv64"))
        assertEquals("FlightLog-v1.2.0-universal.apk", release?.assetName)
    }

    @Test fun rejectsPrereleaseAndAssetsWithoutDigest() {
        assertNull(GitHubReleaseSource.parse(releaseJson(prerelease = true), listOf("x86_64")))
        assertNull(GitHubReleaseSource.parse(releaseJson().replace("sha256:$DIGEST", ""), listOf("x86_64")))
    }

    private fun releaseJson(prerelease: Boolean = false) = """
        {
          "tag_name": "v1.2.0",
          "name": "FlightLog v1.2.0",
          "body": "Changes",
          "draft": false,
          "prerelease": $prerelease,
          "assets": [
            ${asset("FlightLog-v1.2.0-arm64-v8a.apk")},
            ${asset("FlightLog-v1.2.0-x86_64.apk")},
            ${asset("FlightLog-v1.2.0-universal.apk")}
          ]
        }
    """.trimIndent()

    private fun asset(name: String) = """
        {"name":"$name","browser_download_url":"https://example.test/$name","size":123,"digest":"sha256:$DIGEST"}
    """.trimIndent()

    private companion object { const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
}
