package ch.lkmc.bangnidraw.engine.core

/** Resolves only the generated layer-name grammar; user text stays literal. */
internal object LayerNameResolver {
    fun resolve(
        stored: String,
        defaultName: (Int) -> String,
        flattenedName: String,
        copySuffix: String,
    ): String {
        if (stored == LayerStack.FLATTENED_NAME) return flattenedName

        val defaultNumber = stored
            .removePrefix(DEFAULT_PREFIX)
            .takeIf { stored.startsWith(DEFAULT_PREFIX) }
            ?.toIntOrNull()
        if (defaultNumber != null) return defaultName(defaultNumber)

        if (!stored.endsWith(COPY_TOKEN)) return stored

        val original = stored.removeSuffix(COPY_TOKEN)
        return resolve(original, defaultName, flattenedName, copySuffix) + copySuffix
    }

    private const val DEFAULT_PREFIX = "${LayerStack.DEFAULT_NAME_KEY} "
    private const val COPY_TOKEN = " ${LayerStack.COPY_SUFFIX_KEY}"
}
