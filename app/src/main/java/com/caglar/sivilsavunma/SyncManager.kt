package com.caglar.sivilsavunma

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask

class SyncManager(private val repo: StudyRepository) {
    private val main = Handler(Looper.getMainLooper())
    private var timer: Timer? = null

    val enabled: Boolean
        get() = SyncConfig.SUPABASE_URL.isNotBlank() &&
                SyncConfig.ANON_KEY.isNotBlank() &&
                !SyncConfig.SUPABASE_URL.contains("CHANGE_ME") &&
                !SyncConfig.ANON_KEY.contains("CHANGE_ME")

    fun start() {
        if (!enabled) return
        rpc("study_sync_seed_stats", JSONObject().put("p_code", SyncConfig.SYNC_CODE)
            .put("p_answered", repo.stats.answered).put("p_correct", repo.stats.correct).put("p_wrong", repo.stats.wrong))
        repo.favorites.forEach { id -> repo.question(id)?.let { favorite(it, true) } }
        repo.wrongs.values.flatten().forEach { id -> repo.question(id)?.let { wrong(it, true) } }
        repo.savedTests.forEach { saved(it, true) }
        repo.lastScores.forEach { (setID, value) -> score(setID, value) }
        pull()
        timer?.cancel()
        timer = Timer("study-sync", true).apply {
            schedule(object : TimerTask() { override fun run() = pull() }, 3000L, 3000L)
        }
    }

    fun answer(correct: Boolean) = rpc("study_sync_answer", JSONObject()
        .put("p_code", SyncConfig.SYNC_CODE).put("p_correct", correct))

    fun favorite(q: QuizQuestion, present: Boolean) = rpc("study_sync_favorite", JSONObject()
        .put("p_code", SyncConfig.SYNC_CODE).put("p_question_id", q.id)
        .put("p_set_id", q.setID).put("p_title", q.setTitle).put("p_present", present))

    fun wrong(q: QuizQuestion, present: Boolean) = rpc("study_sync_wrong", JSONObject()
        .put("p_code", SyncConfig.SYNC_CODE).put("p_question_id", q.id)
        .put("p_set_id", q.setID).put("p_present", present))

    fun clearWrongSet(setID: String) = rpc("study_sync_clear_wrong_set", JSONObject()
        .put("p_code", SyncConfig.SYNC_CODE).put("p_set_id", setID))

    fun saved(item: SavedTest, present: Boolean) = rpc("study_sync_saved", JSONObject()
        .put("p_code", SyncConfig.SYNC_CODE).put("p_test_id", item.id)
        .put("p_payload", repo.savedTestToJson(item)).put("p_present", present))

    fun deleteSaved(id: String) = rpc("study_sync_saved", JSONObject()
        .put("p_code", SyncConfig.SYNC_CODE).put("p_test_id", id)
        .put("p_payload", JSONObject()).put("p_present", false))

    fun score(setID: String, value: LastScore) = rpc("study_sync_score", JSONObject()
        .put("p_code", SyncConfig.SYNC_CODE).put("p_set_id", setID)
        .put("p_payload", JSONObject().put("percent", value.percent).put("correct", value.correct)
            .put("wrong", value.wrong).put("total", value.total).put("date", value.date)))

    fun pull() {
        if (!enabled) return
        rpc("study_sync_snapshot", JSONObject().put("p_code", SyncConfig.SYNC_CODE)) { root ->
            val statsObj = root.optJSONObject("stats") ?: JSONObject()
            val stats = AppStats(statsObj.optInt("answered"), statsObj.optInt("correct"), statsObj.optInt("wrong"))

            val favs = mutableSetOf<String>()
            val favArr = root.optJSONArray("favorites") ?: JSONArray()
            for (i in 0 until favArr.length()) {
                val r = favArr.optJSONObject(i) ?: continue
                if (r.optBoolean("present")) favs += r.optString("question_id")
            }

            val wrongs = mutableMapOf<String, MutableSet<String>>()
            val wrongArr = root.optJSONArray("wrongs") ?: JSONArray()
            for (i in 0 until wrongArr.length()) {
                val r = wrongArr.optJSONObject(i) ?: continue
                if (!r.optBoolean("present")) continue
                wrongs.getOrPut(r.optString("set_id")) { mutableSetOf() }.add(r.optString("question_id"))
            }

            val saved = mutableListOf<SavedTest>()
            val savedArr = root.optJSONArray("saved_tests") ?: JSONArray()
            for (i in 0 until savedArr.length()) {
                val r = savedArr.optJSONObject(i) ?: continue
                if (!r.optBoolean("present")) continue
                repo.savedTestFromJson(r.optJSONObject("payload") ?: JSONObject(), r.optString("test_id"))?.let(saved::add)
            }
            saved.sortByDescending { it.savedAt }

            val scores = mutableMapOf<String, LastScore>()
            val scoreArr = root.optJSONArray("scores") ?: JSONArray()
            for (i in 0 until scoreArr.length()) {
                val r = scoreArr.optJSONObject(i) ?: continue
                val p = r.optJSONObject("payload") ?: continue
                scores[r.optString("set_id")] = LastScore(
                    p.optInt("percent"), p.optInt("correct"), p.optInt("wrong"), p.optInt("total"), p.optLong("date")
                )
            }

            main.post { repo.applyRemoteSnapshot(stats, favs, wrongs.mapValues { it.value.toSet() }, saved, scores) }
        }
    }

    private fun rpc(name: String, body: JSONObject, completion: ((JSONObject) -> Unit)? = null) {
        if (!enabled) return
        Thread {
            try {
                val base = SyncConfig.SUPABASE_URL.trimEnd('/')
                val conn = URL("$base/rest/v1/rpc/$name").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("apikey", SyncConfig.ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer ${SyncConfig.ANON_KEY}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (completion != null && text.isNotBlank()) {
                    val parsed = if (text.trim().startsWith("{")) JSONObject(text) else {
                        val arr = JSONArray(text); arr.optJSONObject(0) ?: JSONObject()
                    }
                    completion(parsed)
                }
                conn.disconnect()
            } catch (_: Exception) { }
        }.start()
    }
}
