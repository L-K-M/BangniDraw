package ch.lkmc.bangnidraw.engine.core

/** Keeps live opacity previews outside history, then emits one final edit. */
internal class LayerOpacityGesture private constructor(
    private val layerId: LayerId,
    private val before: LayerProps,
    private val value: Float,
) {
    fun withValue(next: Float): LayerOpacityGesture = LayerOpacityGesture(layerId, before, next)

    fun preview(stack: LayerStack): LayerStack? {
        val index = stack.indexOf(layerId)
        val layer = stack.layers.getOrNull(index) ?: return null
        if (layer.props != before) return null

        val after = before.withOpacity(value)
        return stack.copy(
            layers = stack.layers.toMutableList().apply {
                set(index, layer.copy(props = after))
            },
        )
    }

    fun finish(stack: LayerStack): StackResult {
        val next = preview(stack) ?: return StackResult.Refused(Refusal.NOOP)
        val after = next.layers[next.indexOf(layerId)].props
        if (after == before) return StackResult.Refused(Refusal.NOOP)

        val active = stack.active.id
        return StackResult.Ok(
            StackEdit(
                stack = next,
                pixels = null,
                entry = HistoryEntry.LayerProps(
                    activeBefore = active,
                    activeAfter = active,
                    layerId = layerId,
                    before = before.toRecord(),
                    after = after.toRecord(),
                ),
            ),
        )
    }

    companion object {
        fun begin(stack: LayerStack, index: Int): LayerOpacityGesture? {
            val props = stack.layers.getOrNull(index)?.props ?: return null

            return LayerOpacityGesture(props.id, props, props.opacity)
        }
    }
}
