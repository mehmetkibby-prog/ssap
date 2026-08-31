package com.caglar.sivilsavunma

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class StudyRepository(private val context: Context) {
    val sets: List<QuizSet> = buildSets()

    var favorites by mutableStateOf(loadStringSet("favorites_v2"))
        private set

    var savedTests by mutableStateOf(loadSavedTests())
        private set

    var lastScores by mutableStateOf(loadScores())
        private set

    var wrongs by mutableStateOf(loadWrongs())
        private set

    var stats by mutableStateOf(loadStats())
        private set

    private val syncManager = SyncManager(this)

    init {
        syncManager.start()
    }

    val allQuestions: List<QuizQuestion>
        get() = sets.flatMap { it.questions }

    val wrongCount: Int
        get() = wrongs.values.sumOf { it.size }

    fun question(id: String): QuizQuestion? = allQuestions.firstOrNull { it.id == id }

    fun setFor(setID: String): QuizSet? =
        sets.firstOrNull { it.id == setID || it.scoreKey == setID || it.questions.firstOrNull()?.setID == setID }

    fun addFavorite(id: String) {
        if (id in favorites) return
        favorites = favorites + id
        saveStringSet("favorites_v2", favorites)
        question(id)?.let { syncManager.favorite(it, true) }
    }

    fun removeFavorite(id: String) {
        favorites = favorites - id
        saveStringSet("favorites_v2", favorites)
        question(id)?.let { syncManager.favorite(it, false) }
    }

    fun clearAllFavorites() {
        val old = favorites.toList()
        favorites = emptySet()
        saveStringSet("favorites_v2", favorites)
        old.forEach { id -> question(id)?.let { syncManager.favorite(it, false) } }
    }

    fun saveTest(item: SavedTest) {
        savedTests = listOf(item) + savedTests.filterNot { it.id == item.id }
        saveSavedTests()
        syncManager.saved(item, true)
    }

    fun deleteSaved(id: String) {
        savedTests = savedTests.filterNot { it.id == id }
        saveSavedTests()
        syncManager.deleteSaved(id)
    }

    fun recordAnswer(question: QuizQuestion, isCorrect: Boolean) {
        stats = if (isCorrect) {
            stats.copy(answered = stats.answered + 1, correct = stats.correct + 1)
        } else {
            val set = wrongs[question.setID].orEmpty() + question.id
            wrongs = wrongs + (question.setID to set)
            saveWrongs()
            stats.copy(answered = stats.answered + 1, wrong = stats.wrong + 1)
        }
        saveStats()
        syncManager.answer(isCorrect)
        if (!isCorrect) syncManager.wrong(question, true)
    }

    fun wrongQuestions(setID: String): List<QuizQuestion> =
        wrongs[setID].orEmpty().mapNotNull(::question)

    fun resolveWrongImmediately(question: QuizQuestion, reviewSetID: String) {
        val current = wrongs[reviewSetID].orEmpty()
        if (question.id !in current) return
        val next = current - question.id
        wrongs = if (next.isEmpty()) wrongs - reviewSetID else wrongs + (reviewSetID to next)
        saveWrongs()
        syncManager.wrong(question, false)
    }

    fun clearWrongs(setID: String) {
        wrongs = wrongs - setID
        saveWrongs()
        syncManager.clearWrongSet(setID)
    }

    fun clearAllWrongs() {
        val setIDs = wrongs.filterValues { it.isNotEmpty() }.keys.toList()
        wrongs = emptyMap()
        saveWrongs()
        setIDs.forEach { syncManager.clearWrongSet(it) }
    }

    fun saveScore(setID: String, correct: Int, wrong: Int, total: Int) {
        val answered = correct + wrong
        val pct = if (answered == 0) 0 else ((correct.toDouble() / answered) * 100).toInt()
        val value = LastScore(pct, correct, wrong, total, System.currentTimeMillis())
        lastScores = lastScores + (setID to value)
        saveScores()
        syncManager.score(setID, value)
    }

    fun applyRemoteSnapshot(
        remoteStats: AppStats,
        remoteFavorites: Set<String>,
        remoteWrongs: Map<String, Set<String>>,
        remoteSaved: List<SavedTest>,
        remoteScores: Map<String, LastScore>
    ) {
        stats = remoteStats
        favorites = remoteFavorites
        wrongs = remoteWrongs
        savedTests = remoteSaved
        lastScores = remoteScores
        saveStats()
        saveStringSet("favorites_v2", favorites)
        saveWrongs()
        saveSavedTests()
        saveScores()
    }

    fun newSession(
        set: QuizSet,
        questions: List<QuizQuestion>,
        scoreSetID: String? = null,
        wrongReviewSetID: String? = null
    ): QuizSession {
        val shuffledQuestions = questions.shuffled()
        val runtime = shuffledQuestions.map { q ->
            RuntimeQuestion(q, q.options.indices.shuffled())
        }
        return QuizSession(
            set = set,
            runtimeQuestions = runtime,
            scoreSetID = scoreSetID,
            wrongReviewSetID = wrongReviewSetID
        )
    }

    fun resumeSession(item: SavedTest): QuizSession? {
        val set = setFor(item.setID) ?: return null
        val runtime = item.questions.mapNotNull { state ->
            val q = question(state.questionID) ?: return@mapNotNull null
            RuntimeQuestion(
                source = q,
                optionOrder = state.optionOrder.ifEmpty { q.options.indices.toList() },
                eliminatedOriginalIndices = state.eliminated.toMutableSet()
            )
        }
        if (runtime.isEmpty()) return null

        return QuizSession(
            set = set,
            runtimeQuestions = runtime,
            index = item.currentIndex.coerceIn(0, runtime.lastIndex),
            correct = item.correct,
            wrong = item.wrong,
            selectedDisplayIndex = item.currentSelectedDisplayIndex,
            answered = item.currentAnswered,
            savedID = item.id,
            scoreSetID = item.scoreSetID,
            wrongReviewSetID = item.wrongReviewSetID
        )
    }

    fun saveSession(session: QuizSession): String {
        val id = session.savedID ?: UUID.randomUUID().toString()
        session.savedID = id

        saveTest(
            SavedTest(
                id = id,
                setID = session.set.scoreKey,
                title = session.set.title,
                questions = session.runtimeQuestions.map {
                    SavedQuestionState(
                        questionID = it.source.id,
                        optionOrder = it.optionOrder,
                        eliminated = it.eliminatedOriginalIndices.toList()
                    )
                },
                currentIndex = session.index,
                correct = session.correct,
                wrong = session.wrong,
                currentSelectedDisplayIndex = session.selectedDisplayIndex,
                currentAnswered = session.answered,
                scoreSetID = session.scoreSetID,
                wrongReviewSetID = session.wrongReviewSetID,
                savedAt = System.currentTimeMillis()
            )
        )
        return id
    }

    fun finishSession(session: QuizSession) {
        session.scoreSetID?.let {
            saveScore(it, session.correct, session.wrong, session.runtimeQuestions.size)
        }
        session.wrongReviewSetID?.let(::clearWrongs)
        session.savedID?.let(::deleteSaved)
    }

    fun buildOfficialExam(): QuizSet {
        fun q(setId: String) = setFor(setId)?.questions.orEmpty()
        // 6 Eylül provasına "Anayasa • Kaynak Soruları (108)" dahil edilmez.
        val anatomy = q("anayasaek").shuffled().take(10)
        val gkExtra = listOf("gkek-geo1","gkek-geo2","gkek-hist1","gkek-hist2","gkek-koy").flatMap { q(it) }
        val selected = buildList {
            addAll(anatomy)
            addAll(q("tarih").shuffled().take(2))
            addAll(q("cografya").shuffled().take(2))
            addAll(q("sivil-yasa").shuffled().take(6))
            addAll(q("personel").shuffled().take(6))
            addAll(q("siginak").shuffled().take(5))
            addAll(q("teskilat").shuffled().take(6))
            addAll(q("atama").shuffled().take(4))
            addAll(q("afet").shuffled().take(3))
            addAll(q("gk409").shuffled().take(2))
            addAll(gkExtra.shuffled().take(4))
        }.shuffled().take(50)
        return QuizSet(
            id = "official-2026-09-06",
            title = "6 Eylül • Gerçek Sınav Provası",
            subtitle = "50 soru • Yasa ağırlıklı • Kıbrıs Tarihi + Coğrafya + Genel Kültür = 10",
            category = StudyCategory.REAL,
            questions = selected,
            iconText = "🎯"
        )
    }

    private fun buildSets(): List<QuizSet> {
        val out = mutableListOf<QuizSet>()

        val gk = loadQuestions("reis_genel_kultur_251.json", "REİS Genel Kültür", "reis-genel-kultur")
        if (gk.isNotEmpty()) {
            out += QuizSet("gk409", "REİS Genel Kültür", "${gk.size} soru", StudyCategory.GENERAL, gk, "★")
        }

        val realDefs = listOf(
            Triple("real_exam_questions.json", "Sivil Savunma Teşkilatı Personel Yasası", "personel"),
            Triple("real_exam_sivil_savunma_yasasi_20.json", "Sivil Savunma Yasası", "sivil-yasa"),
            Triple("real_exam_siginak_yasasi_13.json", "Sığınak Yasası", "siginak"),
            Triple("real_exam_atama_disiplin_13.json", "Atama ve Disiplin", "atama"),
            Triple("real_exam_teskilat_donatim_30.json", "Teşkilat ve Donatım", "teskilat"),
            Triple("real_exam_anayasa_108.json", "Anayasa • Kaynak Soruları", "anayasa108"),
            Triple("real_exam_anayasa_ek_30.json", "Anayasa • EK Sınav (66)", "anayasaek")
        )
        realDefs.forEach { (file, title, id) ->
            val qs = loadQuestions(file, title, id)
            if (qs.isNotEmpty()) out += QuizSet(id, title, "${qs.size} soru", StudyCategory.REAL, qs, "✓")
        }

        val gkEkDefs = listOf(
            Triple("genel_kultur_ek_cografya_test1.json", "Kıbrıs Coğrafyası Test I", "gkek-geo1"),
            Triple("genel_kultur_ek_cografya_test2.json", "Kıbrıs Coğrafyası Test II", "gkek-geo2"),
            Triple("genel_kultur_ek_tarih_test1.json", "Kıbrıs Tarihi Test I", "gkek-hist1"),
            Triple("genel_kultur_ek_tarih_test2.json", "Kıbrıs Tarihi Test II", "gkek-hist2"),
            Triple("genel_kultur_ek_koy_tarihi_eserler.json", "Köy İsimleri ve Tarihi Eserler", "gkek-koy")
        )
        gkEkDefs.forEach { (file, title, id) ->
            val qs = loadQuestions(file, title, id)
            if (qs.isNotEmpty()) out += QuizSet(id, title, "${qs.size} soru • Genel Kültür EK", StudyCategory.REAL, qs, "EK")
        }

        val geo = loadQuestions("kibris_cografyasi_30.json", "Kıbrıs Coğrafyası", "cografya")
        if (geo.isNotEmpty()) out += QuizSet("cografya", "Kıbrıs Coğrafyası", "${geo.size} soru", StudyCategory.GEOGRAPHY, geo, "⌖")

        val hist = loadQuestions("kibris_tarihi_30_yuzeysel.json", "Kıbrıs Tarihi", "tarih")
        if (hist.isNotEmpty()) out += QuizSet("tarih", "Kıbrıs Tarihi", "${hist.size} soru", StudyCategory.HISTORY, hist, "◷")

        val afet = loadQuestions("afet_reis6.json", "Afet Yönetimi • REİS", "afet")
        if (afet.isNotEmpty()) out += QuizSet("afet", "Afet Yönetimi • REİS", "${afet.size} soru", StudyCategory.DISASTER, afet, "◆")

        return out
    }

    private fun loadQuestions(file: String, defaultTitle: String, defaultSetID: String): List<QuizQuestion> {
        return try {
            val text = context.assets.open(file).bufferedReader().use { it.readText() }
            val root = text.trim()
            val rows: JSONArray = if (root.startsWith("[")) {
                JSONArray(root)
            } else {
                val obj = JSONObject(root)
                when {
                    obj.has("questions") -> obj.getJSONArray("questions")
                    obj.has("multipleChoice") -> obj.getJSONArray("multipleChoice")
                    else -> JSONArray()
                }
            }

            buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val stem = firstString(row, "stem", "prompt", "question", "soru")
                    if (stem.isBlank()) continue

                    val options = firstArray(row, "options", "choices", "secenekler")
                    var correctIndex = firstInt(row, "correctIndex", "answerIndex", "correct_index") ?: 0
                    val answerText = firstString(row, "answerText", "correctAnswer", "cevapText", "cevap")
                        .ifBlank { options.getOrNull(correctIndex).orEmpty() }

                    if (options.isEmpty() || correctIndex !in options.indices) continue

                    val number = firstInt(row, "number", "no") ?: (i + 1)
                    add(
                        QuizQuestion(
                            id = firstString(row, "id").ifBlank { "$defaultSetID-$number" },
                            number = number,
                            stem = stem,
                            options = options,
                            correctIndex = correctIndex,
                            answerText = answerText,
                            explanation = firstString(row, "explanation", "note"),
                            setTitle = firstString(row, "setTitle", "subjectTitle").ifBlank { defaultTitle },
                            setID = firstString(row, "setID", "subjectID").ifBlank { defaultSetID },
                            constitutionArticle = firstString(row, "constitutionArticle"),
                            constitutionFeedback = firstString(row, "constitutionFeedback")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            if (obj.has(key) && !obj.isNull(key)) {
                val v = obj.opt(key)
                if (v is String && v.isNotBlank()) return v
                if (v != null && v !is JSONObject && v !is JSONArray) return v.toString()
            }
        }
        return ""
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int? {
        keys.forEach { key ->
            if (obj.has(key) && !obj.isNull(key)) {
                val v = obj.opt(key)
                when (v) {
                    is Number -> return v.toInt()
                    is String -> v.toIntOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun firstArray(obj: JSONObject, vararg keys: String): List<String> {
        keys.forEach { key ->
            val arr = obj.optJSONArray(key) ?: return@forEach
            return (0 until arr.length()).map { arr.opt(it)?.toString().orEmpty() }
        }
        return emptyList()
    }

    private val prefs get() = context.getSharedPreferences("sivil_savunma_v2", Context.MODE_PRIVATE)

    private fun saveStringSet(key: String, value: Set<String>) {
        prefs.edit().putString(key, JSONArray(value.toList()).toString()).apply()
    }

    private fun loadStringSet(key: String): Set<String> {
        return try {
            val arr = JSONArray(prefs.getString(key, "[]"))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun saveStats() {
        val o = JSONObject()
            .put("answered", stats.answered)
            .put("correct", stats.correct)
            .put("wrong", stats.wrong)
        prefs.edit().putString("stats_v2", o.toString()).apply()
    }

    private fun loadStats(): AppStats {
        return try {
            val o = JSONObject(prefs.getString("stats_v2", "{}"))
            AppStats(o.optInt("answered"), o.optInt("correct"), o.optInt("wrong"))
        } catch (_: Exception) {
            AppStats()
        }
    }

    private fun saveScores() {
        val root = JSONObject()
        lastScores.forEach { (k, v) ->
            root.put(k, JSONObject()
                .put("percent", v.percent)
                .put("correct", v.correct)
                .put("wrong", v.wrong)
                .put("total", v.total)
                .put("date", v.date))
        }
        prefs.edit().putString("scores_v2", root.toString()).apply()
    }

    private fun loadScores(): Map<String, LastScore> {
        return try {
            val root = JSONObject(prefs.getString("scores_v2", "{}"))
            root.keys().asSequence().associateWith { k ->
                val o = root.getJSONObject(k)
                LastScore(o.optInt("percent"), o.optInt("correct"), o.optInt("wrong"), o.optInt("total"), o.optLong("date"))
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveWrongs() {
        val root = JSONObject()
        wrongs.forEach { (k, ids) -> root.put(k, JSONArray(ids.toList())) }
        prefs.edit().putString("wrongs_v2", root.toString()).apply()
    }

    private fun loadWrongs(): Map<String, Set<String>> {
        return try {
            val root = JSONObject(prefs.getString("wrongs_v2", "{}"))
            root.keys().asSequence().associateWith { k ->
                val arr = root.getJSONArray(k)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveSavedTests() {
        val arr = JSONArray()
        savedTests.forEach { item ->
            val qArr = JSONArray()
            item.questions.forEach { q ->
                qArr.put(JSONObject()
                    .put("questionID", q.questionID)
                    .put("optionOrder", JSONArray(q.optionOrder))
                    .put("eliminated", JSONArray(q.eliminated)))
            }
            arr.put(JSONObject()
                .put("id", item.id)
                .put("setID", item.setID)
                .put("title", item.title)
                .put("questions", qArr)
                .put("currentIndex", item.currentIndex)
                .put("correct", item.correct)
                .put("wrong", item.wrong)
                .put("currentSelectedDisplayIndex", item.currentSelectedDisplayIndex ?: JSONObject.NULL)
                .put("currentAnswered", item.currentAnswered)
                .put("scoreSetID", item.scoreSetID ?: JSONObject.NULL)
                .put("wrongReviewSetID", item.wrongReviewSetID ?: JSONObject.NULL)
                .put("savedAt", item.savedAt))
        }
        prefs.edit().putString("saved_v2", arr.toString()).apply()
    }

    fun savedTestToJson(item: SavedTest): JSONObject {
        val questionIDs = JSONArray(item.questions.map { it.questionID })
        val eliminated = JSONObject()
        val optionOrders = JSONObject()
        item.questions.forEach { q ->
            eliminated.put(q.questionID, JSONArray(q.eliminated))
            optionOrders.put(q.questionID, JSONArray(q.optionOrder))
        }
        return JSONObject()
            .put("title", item.title)
            .put("setID", item.setID)
            .put("questionIDs", questionIDs)
            .put("currentIndex", item.currentIndex)
            .put("correct", item.correct)
            .put("wrong", item.wrong)
            .put("eliminated", eliminated)
            .put("optionOrders", optionOrders)
            .put("currentSelectedDisplayIndex", item.currentSelectedDisplayIndex ?: JSONObject.NULL)
            .put("currentAnswered", item.currentAnswered)
            .put("scoreSetID", item.scoreSetID ?: JSONObject.NULL)
            .put("wrongReviewSetID", item.wrongReviewSetID ?: JSONObject.NULL)
            .put("savedAtUnixMs", item.savedAt)
    }

    fun savedTestFromJson(o: JSONObject, testID: String = o.optString("id")): SavedTest? {
        return try {
            val ids = o.optJSONArray("questionIDs") ?: JSONArray()
            val eliminatedObj = o.optJSONObject("eliminated") ?: JSONObject()
            val ordersObj = o.optJSONObject("optionOrders") ?: JSONObject()
            val states = (0 until ids.length()).mapNotNull { i ->
                val qid = ids.optString(i)
                val q = question(qid) ?: return@mapNotNull null
                val order = ordersObj.optJSONArray(qid).toIntList().ifEmpty { q.options.indices.toList() }
                val elim = eliminatedObj.optJSONArray(qid).toIntList()
                SavedQuestionState(qid, order, elim)
            }
            if (states.isEmpty()) return null
            SavedTest(
                id = testID,
                setID = o.optString("setID"),
                title = o.optString("title", "Kayıtlı Test"),
                questions = states,
                currentIndex = o.optInt("currentIndex"),
                correct = o.optInt("correct"),
                wrong = o.optInt("wrong"),
                currentSelectedDisplayIndex = if (o.isNull("currentSelectedDisplayIndex")) null else o.optInt("currentSelectedDisplayIndex"),
                currentAnswered = o.optBoolean("currentAnswered"),
                scoreSetID = if (o.isNull("scoreSetID")) null else o.optString("scoreSetID"),
                wrongReviewSetID = if (o.isNull("wrongReviewSetID")) null else o.optString("wrongReviewSetID"),
                savedAt = o.optLong("savedAtUnixMs", System.currentTimeMillis())
            )
        } catch (_: Exception) { null }
    }

    private fun loadSavedTests(): List<SavedTest> {
        return try {
            val arr = JSONArray(prefs.getString("saved_v2", "[]"))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val qArr = o.optJSONArray("questions") ?: JSONArray()
                val states = (0 until qArr.length()).mapNotNull { j ->
                    val q = qArr.optJSONObject(j) ?: return@mapNotNull null
                    SavedQuestionState(
                        questionID = q.optString("questionID"),
                        optionOrder = q.optJSONArray("optionOrder").toIntList(),
                        eliminated = q.optJSONArray("eliminated").toIntList()
                    )
                }
                SavedTest(
                    id = o.optString("id"),
                    setID = o.optString("setID"),
                    title = o.optString("title"),
                    questions = states,
                    currentIndex = o.optInt("currentIndex"),
                    correct = o.optInt("correct"),
                    wrong = o.optInt("wrong"),
                    currentSelectedDisplayIndex = if (o.isNull("currentSelectedDisplayIndex")) null else o.optInt("currentSelectedDisplayIndex"),
                    currentAnswered = o.optBoolean("currentAnswered"),
                    scoreSetID = if (o.isNull("scoreSetID")) null else o.optString("scoreSetID"),
                    wrongReviewSetID = if (o.isNull("wrongReviewSetID")) null else o.optString("wrongReviewSetID"),
                    savedAt = o.optLong("savedAt")
                )
            }.sortedByDescending { it.savedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).map { optInt(it) }
    }
}
