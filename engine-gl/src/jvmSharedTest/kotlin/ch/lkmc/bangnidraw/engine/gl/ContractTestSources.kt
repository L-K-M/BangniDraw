package ch.lkmc.bangnidraw.engine.gl

import java.io.File

/**
 * Reads repository source files for the contract tests. The :app twin
 * (`ui.canvas.ContractTestSources`) is the original; a module's test
 * sources cannot import another module's, so this small reader travels
 * with each suite that needs it.
 */
internal object ContractTestSources {

    fun read(path: String): String = File(repositoryRoot(), path).readText()

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: error("cannot locate repository root from $workingDirectory")
    }

    private const val USER_DIRECTORY_PROPERTY = "user.dir"
    private const val ROOT_MARKER = "settings.gradle.kts"
    private const val APP_DIRECTORY = "app/src/main"
}
