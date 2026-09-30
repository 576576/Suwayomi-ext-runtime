package sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

/**
 * 值要跨进程重启读回来，类型就必须跟着落盘 —— `SharedPreferences` 只按字符串存的话，
 * 扩展用 `getInt` / `getBoolean` 读到的一律是默认值。备份恢复写回的是别的设备存的偏好，
 * 六种取值类型都可能出现。
 */
class FilePreferencesTest {

    private fun newFile() = Files.createTempDirectory("prefs").resolve("source.properties")

    @Test
    fun allSixValueTypesSurviveAReload() {
        val file = newFile()
        FilePreferences(file).edit()
            .putString("s", "text")
            .putBoolean("b", true)
            .putInt("i", 7)
            .putLong("o", 9_000_000_000L)
            .putFloat("f", 1.5f)
            .putStringSet("l", mutableSetOf("a", "b"))
            .apply()

        val reloaded = FilePreferences(file)
        assertEquals("text", reloaded.getString("s", null))
        assertEquals(true, reloaded.getBoolean("b", false))
        assertEquals(7, reloaded.getInt("i", 0))
        assertEquals(9_000_000_000L, reloaded.getLong("o", 0L))
        assertEquals(1.5f, reloaded.getFloat("f", 0f))
        assertEquals(setOf("a", "b"), reloaded.getStringSet("l", mutableSetOf()).orEmpty())
    }

    @Test
    fun aNumberDoesNotComeBackAsAString() {
        val file = newFile()
        FilePreferences(file).edit().putInt("i", 7).apply()

        val reloaded = FilePreferences(file)
        assertNull(reloaded.getString("i", null))
        assertEquals(7, reloaded.getInt("i", 0))
    }

    @Test
    fun allKeepsTheRuntimeTypesBackupSerializationReads() {
        // `/source/{id}/preferences/raw` 按 `all` 里每个值的运行时类型挑 JSON 字段，
        // 值一旦退化成字符串，回写到扩展那边就是类型不对的偏好。
        val file = newFile()
        FilePreferences(file).edit()
            .putString("s", "text")
            .putBoolean("b", true)
            .putInt("i", 7)
            .putLong("o", 9_000_000_000L)
            .putFloat("f", 1.5f)
            .putStringSet("l", mutableSetOf("a"))
            .apply()

        val all = FilePreferences(file).all
        assertTrue(all["s"] is String)
        assertTrue(all["b"] is Boolean)
        assertTrue(all["i"] is Int)
        assertTrue(all["o"] is Long)
        assertTrue(all["f"] is Float)
        assertTrue(all["l"] is Set<*>)
    }
}
