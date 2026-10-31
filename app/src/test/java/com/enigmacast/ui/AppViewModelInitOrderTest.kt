package com.enigmacast.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * viewModelScope launches on Main.immediate, so a coroutine started in an init block runs inside
 * the constructor until it first suspends. Properties it touches must be declared before that
 * init block, or they are still null (beta.122 crashed on start: Mutex.tryLock on null).
 */
class AppViewModelInitOrderTest {
    private val source = File("src/main/java/com/enigmacast/ui/AppViewModel.kt").readLines()

    private fun line(regex: Regex) = source.indexOfFirst { regex.containsMatchIn(it) }

    @Test fun updateCheckStateIsDeclaredBeforeTheInitThatStartsTheCheck() {
        val startCheck = line(Regex("""checkForUpdate\(.*Reason\.START\)"""))
        assertTrue("start check not found", startCheck >= 0)
        val init = (startCheck downTo 0).first { source[it].trim() == "init {" }
        for (name in listOf("updateCheckLock", "lastUpdateCheckAt", "dismissedUpdateVersion", "dismissedUpdateAt", "downloadJob", "updatePrompt")) {
            val decl = line(Regex("""^\s*(private )?va[lr] $name\b"""))
            assertTrue("$name not declared", decl >= 0)
            assertTrue("$name (line ${decl + 1}) must be declared before init (line ${init + 1})", decl < init)
        }
    }
}
