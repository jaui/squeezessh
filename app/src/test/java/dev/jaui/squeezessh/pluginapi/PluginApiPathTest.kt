package dev.jaui.squeezessh.pluginapi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Die Pfadprüfung ist die Antwort auf `//connections`: Androids `path-permission` vergleicht
 * wörtlich, der `UriMatcher` überspringt leere Segmente. Was hier durchgeht, muss genau so
 * geschrieben sein, wie es im Manifest steht.
 */
class PluginApiPathTest {

    @Test
    fun `die Pfade des Vertrags gehen durch`() {
        listOf(
            "/connections",
            "/connections/0f8fad5b-d9cb-469f-a165-70867728950e",
            "/connectiongroups",
            "/connectiongroupmemberships",
            "/portforwards/abc",
            "/snippets",
            "/pluginlog",
        ).forEach { assertThat(isCanonicalApiPath(it)).isTrue() }
    }

    @Test
    fun `leere Segmente werden abgelehnt`() {
        listOf(
            "//connections",
            "/connections//x",
            "/connections/",
            "//connections/x",
            "/",
            "",
        ).forEach { assertThat(isCanonicalApiPath(it)).isFalse() }
    }

    @Test
    fun `Punktsegmente werden abgelehnt`() {
        listOf(
            "/./connections",
            "/../connections",
            "/connections/..",
            "/connections/.",
            "/x/../connections",
        ).forEach { assertThat(isCanonicalApiPath(it)).isFalse() }
    }

    @Test
    fun `Prozent-Kodierung wird abgelehnt`() {
        listOf(
            "/%2Fconnections",
            "/connections%2Fx",
            "/connections%2fx",
            "/connections/%2E%2E",
            "/connection%73",
        ).forEach { assertThat(isCanonicalApiPath(it)).isFalse() }
    }

    @Test
    fun `fehlender Pfad und fremde Zeichen werden abgelehnt`() {
        listOf(
            null,
            "connections",
            "/connections/a b",
            "/connections/ä",
            "/connections\x",
            "/a/b/c",
        ).forEach { assertThat(isCanonicalApiPath(it)).isFalse() }
    }
}
