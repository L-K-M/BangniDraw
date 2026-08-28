package ch.lkmc.bangnidraw.ui.canvas

import java.io.File

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
