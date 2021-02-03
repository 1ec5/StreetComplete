package de.westnordost.streetcomplete.data.visiblequests

import de.westnordost.streetcomplete.data.quest.QuestType
import de.westnordost.streetcomplete.data.quest.QuestTypeRegistry
import javax.inject.Inject

/** Provides a list of quest types that are enabled and ordered by (user chosen) importance.
 *
 *  This can be changed anytime by user preference */
class OrderedVisibleQuestTypesProvider @Inject constructor(
        private val questTypeRegistry: QuestTypeRegistry,
        private val visibleQuestTypeSource: VisibleQuestTypeSource,
        private val questTypeOrderList: QuestTypeOrderList
) {
    fun get(): List<QuestType<*>> {
        val visibleQuestTypes = questTypeRegistry.all.mapNotNull { questType ->
            questType.takeIf { visibleQuestTypeSource.isVisible(it) }
        }.toMutableList()

        questTypeOrderList.sort(visibleQuestTypes)

        return visibleQuestTypes
    }
}
