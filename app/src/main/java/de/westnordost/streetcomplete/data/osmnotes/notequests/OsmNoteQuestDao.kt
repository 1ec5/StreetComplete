package de.westnordost.streetcomplete.data.osmnotes.notequests

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.contentValuesOf

import java.util.Date

import javax.inject.Inject
import de.westnordost.streetcomplete.data.quest.QuestStatus
import de.westnordost.streetcomplete.data.WhereSelectionBuilder
import de.westnordost.streetcomplete.util.Serializer
import de.westnordost.osmapi.map.data.BoundingBox
import de.westnordost.osmapi.map.data.LatLon
import de.westnordost.osmapi.map.data.OsmLatLon
import de.westnordost.streetcomplete.data.ObjectRelationalMapping
import de.westnordost.streetcomplete.data.osmnotes.NoteMapping
import de.westnordost.streetcomplete.data.osmnotes.NoteTable

import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestTable.Columns.COMMENT
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestTable.Columns.IMAGE_PATHS
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestTable.Columns.LAST_UPDATE
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestTable.Columns.NOTE_ID
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestTable.Columns.QUEST_ID
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestTable.Columns.QUEST_STATUS
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestTable.NAME
import de.westnordost.streetcomplete.data.osmnotes.notequests.OsmNoteQuestTable.NAME_MERGED_VIEW
import de.westnordost.streetcomplete.ktx.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Singleton

/** Stores OsmNoteQuest objects - quests and answers to these for contributing to a note */
@Singleton class OsmNoteQuestDao @Inject constructor(
    private val dbHelper: SQLiteOpenHelper,
    private val mapping: OsmNoteQuestMapping
) {
    /* Must be a singleton because there is a listener that should respond to a change in the
     *  database table */

    private val db get() = dbHelper.writableDatabase

    /** Listener on answered note quests */
    interface AnsweredQuestCountListener {
        fun onAnsweredNoteQuestCountIncreased()
        fun onAnsweredNoteQuestCountDecreased()
    }

    private val answeredQuestCountListeners: MutableList<AnsweredQuestCountListener> = CopyOnWriteArrayList()

    var answeredCount: Int = getCount(statusIn = listOf(QuestStatus.ANSWERED))
        private set

    fun add(quest: OsmNoteQuest): Boolean {
        return insertAll(listOf(quest), CONFLICT_IGNORE) == 1
    }

    fun replace(quest: OsmNoteQuest): Boolean {
        return insertAll(listOf(quest), CONFLICT_REPLACE) == 1
    }

    fun get(id: Long): OsmNoteQuest? {
        return db.queryOne(NAME_MERGED_VIEW, null, "$QUEST_ID = $id") { mapping.toObject(it) }
    }

    fun update(quest: OsmNoteQuest): Boolean {
        quest.lastUpdate = Date()
        val query = "$QUEST_ID = ?"
        val args = arrayOf(quest.id.toString())
        val result = db.update(NAME, mapping.toUpdatableContentValues(quest), query, args) == 1
        if (result) onUpdated()
        return result
    }

    fun delete(id: Long): Boolean {
        val result = db.delete(NAME, "$QUEST_ID = $id", null) == 1
        if (result) onUpdated()
        return result
    }

    fun addAll(quests: Collection<OsmNoteQuest>): Int {
        return insertAll(quests, CONFLICT_IGNORE)
    }

    fun replaceAll(quests: Collection<OsmNoteQuest>): Int {
        return insertAll(quests, CONFLICT_REPLACE)
    }

    fun deleteAllIds(ids: Collection<Long>): Int {
        val result = db.delete(NAME, "$QUEST_ID IN (${ids.joinToString(",")})", null)
        if (result > 0) onUpdated()
        return result
    }

    fun getAllPositions(bounds: BoundingBox): List<LatLon> {
        val cols = arrayOf(NoteTable.Columns.LATITUDE, NoteTable.Columns.LONGITUDE)
        val qb = createQuery(bounds = bounds)
        return db.query(NAME_MERGED_VIEW, cols, qb) { OsmLatLon(it.getDouble(0), it.getDouble(1)) }
    }

    fun getAll(
            statusIn: Collection<QuestStatus>? = null,
            bounds: BoundingBox? = null,
            changedBefore: Long? = null
    ): List<OsmNoteQuest> {
        val qb = createQuery(statusIn, bounds, changedBefore)
        return db.query(NAME_MERGED_VIEW, null, qb) { mapping.toObject(it) }
    }

    fun getCount(
            statusIn: Collection<QuestStatus>? = null,
            bounds: BoundingBox? = null,
            changedBefore: Long? = null
    ): Int {
        val qb = createQuery(statusIn, bounds, changedBefore)
        return db.queryOne(NAME_MERGED_VIEW, arrayOf("COUNT(*)"), qb) { it.getInt(0) } ?: 0
    }

    fun deleteAll(
            statusIn: Collection<QuestStatus>? = null,
            bounds: BoundingBox? = null,
            changedBefore: Long? = null
    ): Int {
        val qb = createQuery(statusIn, bounds, changedBefore)
        val result = db.delete(NAME, qb.where, qb.args)
        if (result > 0) onUpdated()
        return result
    }

    fun addAnsweredQuestCountListener(listener: AnsweredQuestCountListener) {
        answeredQuestCountListeners.add(listener)
    }
    fun removeAnsweredQuestCountListener(listener: AnsweredQuestCountListener) {
        answeredQuestCountListeners.remove(listener)
    }

    private fun insertAll(quests: Collection<OsmNoteQuest>, conflictAlgorithm: Int): Int {
        var addedRows = 0
        db.transaction {
            for (quest in quests) {
                quest.lastUpdate = Date()
                val rowId = db.insertWithOnConflict(NAME, null, mapping.toContentValues(quest), conflictAlgorithm)
                if (rowId != -1L) {
                    quest.id = rowId
                    addedRows++
                }
            }
        }
        if (addedRows > 0) onUpdated()

        return addedRows
    }

    private fun onUpdated() {
        val newAnsweredQuestCount = getCount(statusIn = listOf(QuestStatus.ANSWERED))
        if (newAnsweredQuestCount != answeredCount) {
            if (newAnsweredQuestCount > answeredCount) {
                answeredQuestCountListeners.forEach { it.onAnsweredNoteQuestCountIncreased() }
            } else {
                answeredQuestCountListeners.forEach { it.onAnsweredNoteQuestCountDecreased() }
            }
            answeredCount = newAnsweredQuestCount
        }
    }
}

private fun createQuery(
        statusIn: Collection<QuestStatus>? = null,
        bounds: BoundingBox? = null,
        changedBefore: Long? = null
) = WhereSelectionBuilder().apply {
    if (statusIn != null && statusIn.isNotEmpty()) {
        if (statusIn.size == 1) {
            add("$QUEST_STATUS = ?", statusIn.single().name)
        } else {
            val names = statusIn.joinToString(",") { "\"$it\"" }
            add("$QUEST_STATUS IN ($names)")
        }
    }
    if (bounds != null) {
        add(
            "(${NoteTable.Columns.LATITUDE} BETWEEN ? AND ?)",
            bounds.minLatitude.toString(),
            bounds.maxLatitude.toString()
        )
        add(
            "(${NoteTable.Columns.LONGITUDE} BETWEEN ? AND ?)",
            bounds.minLongitude.toString(),
            bounds.maxLongitude.toString()
        )
    }
    if (changedBefore != null) {
        add("$LAST_UPDATE < ?", changedBefore.toString())
    }
}

class OsmNoteQuestMapping @Inject constructor(
        private val serializer: Serializer,
        private val questType: OsmNoteQuestType,
        private val noteMapping: NoteMapping
) : ObjectRelationalMapping<OsmNoteQuest> {

    override fun toContentValues(obj: OsmNoteQuest) =
        toConstantContentValues(obj) + toUpdatableContentValues(obj)

    override fun toObject(cursor: Cursor) = OsmNoteQuest(
            cursor.getLong(QUEST_ID),
            noteMapping.toObject(cursor),
            QuestStatus.valueOf(cursor.getString(QUEST_STATUS)),
            cursor.getStringOrNull(COMMENT),
            Date(cursor.getLong(LAST_UPDATE)),
            questType,
            cursor.getBlobOrNull(IMAGE_PATHS)?.let { serializer.toObject<ArrayList<String>>(it) }
    )

    fun toUpdatableContentValues(obj: OsmNoteQuest) = contentValuesOf(
        QUEST_STATUS to obj.status.name,
        LAST_UPDATE to obj.lastUpdate.time,
        COMMENT to obj.comment,
        IMAGE_PATHS to obj.imagePaths?.let { serializer.toBytes(ArrayList<String>(it)) }
    )

    private fun toConstantContentValues(obj: OsmNoteQuest) = contentValuesOf(
        QUEST_ID to obj.id,
        NOTE_ID to obj.note.id
    )
}
