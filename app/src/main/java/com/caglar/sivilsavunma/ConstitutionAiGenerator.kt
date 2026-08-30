package com.caglar.sivilsavunma

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ConstitutionAiGenerator {
    data class Result(val questions: List<QuizQuestion>, val note: String)

    fun generate(
        context: Context,
        apiKey: String,
        wanted: Int,
        difficulty: String,
        progress: (String) -> Unit
    ): Result {
        val root = JSONArray(context.assets.open("anayasa_tam_chunks.json").bufferedReader().use { it.readText() })
        val allowed = buildList {
            for (i in 0 until root.length()) {
                val o = root.optJSONObject(i) ?: continue
                val title = o.optString("title")
                val text = o.optString("text")
                val article = o.optString("article")
                val all = "$title $text".lowercase()
                if ("geçici madde" in all || "gecici madde" in all) continue
                if (article.toIntOrNull() == null || text.isBlank()) continue
                add(o)
            }
        }
        val accepted = mutableListOf<QuizQuestion>()
        val seen = mutableSetOf<String>()
        var lastError = ""
        var round = 0

        while (accepted.size < wanted.coerceIn(1, 30) && round < 20) {
            round++
            progress("${accepted.size}/${wanted.coerceIn(1,30)} doğrulandı • yeni grup üretiliyor…")
            val sample = allowed.shuffled().take(minOf(44, maxOf(24, (wanted - accepted.size) * 4)))
            val corpus = sample.joinToString("\n\n---\n\n") {
                "[MADDE ${it.optString("article")}]\n${it.optString("text")}"
            }
            val normalizedCorpus = normalize(corpus)
            val requestCount = minOf(20, maxOf(6, wanted - accepted.size + 5))
            val prompt = """
KKTC Anayasası sınavı için $requestCount adet 5 şıklı soru üret.
KURALLAR:
- Yalnız aşağıdaki anayasa-4 kaynak bloklarını kullan.
- GEÇİCİ MADDELERDEN ASLA soru üretme.
- Madde numarası ezber sorusu sorma.
- Yetki, süre, sayı, çoğunluk, usul ve kurum bilgisini ölç.
- 1 doğru + 4 güçlü çeldirici olsun.
- Çeldiricilerin tamamı aşağıdaki kaynakta gerçekten geçen kurum/süre/sayı/hukuki ifadelerden seçilsin. Uydurma seçenek yasak.
- evidenceQuote doğru cevabı içeren ve kaynakta aynen geçen kısa kanıt olsun.
- Zorluk: $difficulty
- Sadece JSON döndür:
{"questions":[{"stem":"...","options":["...","...","...","...","..."],"correctIndex":0,"sourceArticle":"109","evidenceQuote":"...","explanation":"..."}]}

KAYNAK:
$corpus
""".trimIndent()
            try {
                val url = URL("https://api.openai.com/v1/responses")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("model", "gpt-5.6-terra").put("input", prompt)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code == 401) throw IllegalArgumentException("API anahtarı kabul edilmedi.")
                if (code !in 200..299) {
                    lastError = "HTTP $code"
                    Thread.sleep(minOf(4500L, 900L + round * 200L))
                    continue
                }
                val payload = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val raw = extractText(payload).replace("```json","").replace("```","").trim()
                if (raw.isBlank()) { lastError = "Boş yanıt"; continue }
                val arr = JSONObject(raw).optJSONArray("questions") ?: continue
                for (i in 0 until arr.length()) {
                    if (accepted.size >= wanted) break
                    val q = arr.optJSONObject(i) ?: continue
                    val stem = q.optString("stem").trim()
                    if (stem.isBlank() || "geçici" in stem.lowercase() || !seen.add(normalize(stem))) continue
                    val optsA = q.optJSONArray("options") ?: continue
                    if (optsA.length()!=5) continue
                    val opts = (0 until 5).map { optsA.optString(it).trim() }
                    if (opts.toSet().size != 5) continue
                    val ci=q.optInt("correctIndex",-1)
                    if (ci !in 0..4) continue
                    val evidence=q.optString("evidenceQuote").trim()
                    if (normalize(evidence).isBlank() || normalize(evidence) !in normalizedCorpus) continue
                    if (normalize(opts[ci]) !in normalizedCorpus) continue
                    // Her çeldirici de gerçek kaynakta bulunmalı.
                    if (opts.indices.filter { it!=ci }.any { normalize(opts[it]) !in normalizedCorpus }) continue
                    accepted += QuizQuestion(
                        id="ai-anayasa-${UUID.randomUUID()}",
                        number=accepted.size+1,
                        stem=stem, options=opts, correctIndex=ci,
                        answerText=opts[ci],
                        explanation=q.optString("explanation"),
                        setTitle="AI • Tam Anayasa",
                        setID="ai-anayasa",
                        constitutionArticle="Madde ${q.optString("sourceArticle")}",
                        constitutionFeedback=evidence
                    )
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                lastError=e.message ?: "Geçici bağlantı hatası"
                Thread.sleep(minOf(4000L,800L+round*180L))
                // Hata alan grup atlanır; kabul edilmiş sorular sıfırlanmaz.
                continue
            }
        }
        return Result(accepted, if (accepted.size>=wanted) "Tamamlandı" else "${accepted.size} soru doğrulandı. Son geçici hata: $lastError")
    }

    private fun extractText(payload: JSONObject): String {
        val direct=payload.optString("output_text")
        if (direct.isNotBlank()) return direct
        val out=payload.optJSONArray("output") ?: return ""
        for (i in 0 until out.length()) {
            val content=out.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val x=content.optJSONObject(j) ?: continue
                val text=x.optString("text")
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }
    private fun normalize(s:String)=s.lowercase()
        .replace(Regex("""[^\p{L}\p{N}%+/\-]+""")," ").trim().replace(Regex("""\s+""")," ")
}
