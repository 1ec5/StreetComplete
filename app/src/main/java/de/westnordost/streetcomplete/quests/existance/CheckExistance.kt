package de.westnordost.streetcomplete.quests.existance

import de.westnordost.osmapi.map.MapDataWithGeometry
import de.westnordost.osmapi.map.data.Element
import de.westnordost.osmfeatures.FeatureDictionary
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.elementfilter.toElementFilterExpression
import de.westnordost.streetcomplete.data.meta.LAST_CHECK_DATE_KEYS
import de.westnordost.streetcomplete.data.meta.toCheckDateString
import de.westnordost.streetcomplete.data.osm.changes.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.osmquest.OsmElementQuestType
import de.westnordost.streetcomplete.ktx.containsAny
import java.util.*
import java.util.concurrent.FutureTask

class CheckExistance(
    private val featureDictionaryFuture: FutureTask<FeatureDictionary>
) : OsmElementQuestType<Unit> {

    private val nodesFilter by lazy { """
        nodes with ((
          (
            amenity = telephone
            or amenity = vending_machine and vending !~ fuel|parking_tickets|public_transport_tickets
            or birds_nest = stork
          )
          and (${lastChecked(1.0)})
        ) or (
          (
            amenity = atm
            or amenity = post_box
            or amenity = waste_basket
            or amenity = vending_machine and vending = public_transport_tickets
          )
          and (${lastChecked(2.0)})
        ) or (
          (
            amenity = clock
            or amenity = bench
            or leisure = picnic_table
            or leisure = firepit
            or tourism = information and information ~ board|terminal|map
            or advertising ~ column|board|poster_box
          )
          and (${lastChecked(4.0)})
        )) and access !~ no|private
    """.toElementFilterExpression()
    }

    private val nodesWaysFilter by lazy { """
        nodes, ways with (
            leisure = pitch and sport = table_tennis
        )
        and access !~ no|private
        and (${lastChecked(4.0)})
    """.toElementFilterExpression() }

    // TODO postkästen!

    /* not including bicycle parkings, motorcycle parkings because their capacity is asked every
    *  few years already, so if it's gone now, it will be noticed that way. */

    override val commitMessage = "Check if element still exists"
    override val wikiLink: String? = null
    override val icon = R.drawable.ic_quest_check

    override fun getTitle(tags: Map<String, String>): Int {
        val hasName = tags.containsKey("name")
        return if(hasName) R.string.quest_existance_name_title else R.string.quest_existance_title
    }

    override fun getTitleArgs(tags: Map<String, String>, featureName: Lazy<String?>): Array<String> {
        val name = tags["name"]
        val featureNameStr = featureName.value.toString()
        return if (name != null) arrayOf(name, featureNameStr) else arrayOf(featureNameStr)
    }

    override fun getApplicableElements(mapData: MapDataWithGeometry): Iterable<Element> =
        mapData.filter { isApplicableTo(it) }

    override fun isApplicableTo(element: Element) =
        (nodesFilter.matches(element) || nodesWaysFilter.matches(element))
        && hasFeatureName(element.tags)

    override fun createForm() = CheckExistanceForm()

    override fun applyAnswerTo(answer: Unit, changes: StringMapChangesBuilder) {
        changes.addOrModify("check_date", Date().toCheckDateString())
        val otherCheckDateKeys = LAST_CHECK_DATE_KEYS.filterNot { it == "check_date" }
        for (otherCheckDateKey in otherCheckDateKeys) {
            changes.deleteIfExists(otherCheckDateKey)
        }
    }

    private fun lastChecked(yearsAgo: Double): String = """
        older today -$yearsAgo years
        or ${LAST_CHECK_DATE_KEYS.joinToString(" or ") { "$it older today -$yearsAgo years" }}
    """.trimIndent()

    private fun hasProperName(tags: Map<String, String>?): Boolean =
        tags?.keys?.containsAny(listOf("name", "brand")) ?: false

    private fun hasFeatureName(tags: Map<String, String>?): Boolean =
        tags?.let { featureDictionaryFuture.get().byTags(it).find().isNotEmpty() } ?: false
}
