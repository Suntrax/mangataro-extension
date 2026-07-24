package com.blissless.mangataro

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * MangaTaro (mangataro.org) scraper for the Oni manga client.
 *
 * API endpoints (all under https://mangataro.org):
 *   1. POST /auth/search  body {"query":"<manga>","limit":20}
 *      -> { "results": [{ "id":47, "title":"One Piece", "slug":"one-piece", ... }] }
 *   2. GET /auth/manga-chapters?manga_id=<id>&offset=0&limit=500&order=DESC&_t=<token>&_ts=<ts>
 *      -> { "chapters": [{ "id":"52024", "chapter":"139.6", "title":"", "url":"..." }] }
 *   3. GET /auth/chapter-content?chapter_id=<id>
 *      -> { "images": ["https://mangataro.yachts/storage/chapters/.../001.webp", ...] }
 *
 * The chapters endpoint requires an MD5 token:
 *   ts     = unix seconds
 *   hour   = UTC "yyyyMMddHH" (e.g. "2026072315")
 *   secret = "mng_ch_" + hour
 *   token  = md5( str(ts) + secret ).hex().substring(0,16)
 *   send as _t=token  _ts=ts
 *
 * Image URLs are absolute and live on mangataro.yachts (NOT mangataro.org).
 * No Referer or cookies needed for image downloads.
 */
object MangaTaroScraper {

    private const val TAG = "MangaTaro"
    private const val BASE = "https://mangataro.org"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    // ---------- Public API (called by ScraperProvider) ----------

    /**
     * List all chapters for a manga (metadata only — no image URLs).
     * Called when the user opens the chapter selection screen.
     */
    fun listChapters(
        context: Context,
        mangaName: String?,
        anilistId: String?
    ): Any {
        if (mangaName.isNullOrBlank()) {
            return mapOf("error" to "No manga name provided.")
        }

        // 1. Search for the manga
        val mangaId = try {
            searchManga(mangaName)
        } catch (e: Exception) {
            return mapOf("error" to "Search failed: ${e.message}")
        } ?: return mapOf("error" to "No manga found for '$mangaName'.")

        // 2. List all chapters (paginated — 500 per page)
        val allChapters = mutableListOf<JSONObject>()
        var offset = 0
        var hasMore = true
        while (hasMore) {
            val page = try {
                fetchChaptersPage(mangaId, offset)
            } catch (e: Exception) {
                return mapOf("error" to "Failed to list chapters: ${e.message}")
            }
            val chapters = page.optJSONArray("chapters") ?: JSONArray()
            for (i in 0 until chapters.length()) {
                allChapters.add(chapters.optJSONObject(i) ?: continue)
            }
            hasMore = page.optBoolean("has_more", false)
            offset += chapters.length()
            if (chapters.length() == 0) break
        }

        // Deduplicate by chapter number — MangaTaro has multiple scanlation
        // groups uploading the same chapter, so we'd otherwise show "Chapter 1"
        // three times. Must happen BEFORE reverse() while the list is still in
        // DESC (newest-first) order, so the tiebreak picks the newest upload.
        val deduped = deduplicateChapters(allChapters)

        // API returns DESC (newest first). Reverse to ASC (oldest first) for reading order.
        val ordered = deduped.asReversed()

        // Build the chapter list response
        val chapterList = mutableListOf<Map<String, Any?>>()
        for ((index, ch) in ordered.withIndex()) {
            chapterList.add(mapOf(
                "number" to normalizeChapterNumber(ch.optString("chapter", "")),
                "title" to ch.optString("title", ""),
                "id" to ch.optString("id", ""),
                "index" to index,
                "pageCount" to 0
            ))
        }

        return mapOf(
            "totalChapters" to ordered.size,
            "mangaId" to mangaId,
            "chapters" to chapterList
        )
    }

    /**
     * Fetch a single chapter's image URLs.
     * Called when the user opens a specific chapter.
     */
    fun scrape(
        context: Context,
        mangaName: String?,
        anilistId: String?,
        chapter: String?
    ): Any {
        if (mangaName.isNullOrBlank()) {
            return mapOf("error" to "No manga name provided.")
        }
        if (chapter.isNullOrBlank()) {
            return mapOf("error" to "No chapter provided.")
        }

        // 1. Search for the manga
        val mangaId = try {
            searchManga(mangaName)
        } catch (e: Exception) {
            return mapOf("error" to "Search failed: ${e.message}")
        } ?: return mapOf("error" to "No manga found for '$mangaName'.")

        // 2. List chapters to find the requested one + get totalChapters
        val allChapters = mutableListOf<JSONObject>()
        var offset = 0
        var hasMore = true
        while (hasMore) {
            val page = try {
                fetchChaptersPage(mangaId, offset)
            } catch (e: Exception) {
                return mapOf("error" to "Failed to list chapters: ${e.message}")
            }
            val chapters = page.optJSONArray("chapters") ?: JSONArray()
            for (i in 0 until chapters.length()) {
                allChapters.add(chapters.optJSONObject(i) ?: continue)
            }
            hasMore = page.optBoolean("has_more", false)
            offset += chapters.length()
            if (chapters.length() == 0) break
        }

        // Deduplicate by chapter number (same as listChapters) so the chapter
        // count and findChapter() operate on the same deduplicated set the
        // user sees in the chapter list.
        val deduped = deduplicateChapters(allChapters)
        val totalChapters = deduped.size

        // 3. Find the requested chapter
        val match = findChapter(deduped, chapter.trim())
        if (match == null) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Chapter '$chapter' not found. Available range: 1–$totalChapters."
            )
        }

        val chapterId = match.optString("id", "")
        if (chapterId.isBlank()) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Matched chapter has invalid id."
            )
        }

        // 4. Fetch the chapter's images
        val images = try {
            fetchChapterImages(chapterId)
        } catch (e: Exception) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Failed to fetch chapter images: ${e.message}"
            )
        }

        if (images.isEmpty()) {
            return mapOf(
                "totalChapters" to totalChapters,
                "error" to "Chapter $chapter returned no images."
            )
        }

        val chapterObj = JSONObject()
        chapterObj.put("number", chapter.trim())
        chapterObj.put("title", match.optString("title", ""))
        chapterObj.put("group", match.optString("group_name", ""))
        chapterObj.put("images", JSONArray(images))

        return mapOf(
            "totalChapters" to totalChapters,
            "chapter" to chapterObj
        )
    }

    // ---------- API helpers ----------

    /** Search for a manga and return the best match's ID. */
    private fun searchManga(query: String): String? {
        val url = "$BASE/auth/search"
        val body = """{"query":"${query.replace("\"", "\\\"")}","limit":20}"""
        val response = httpPost(url, body)

        val data = JSONObject(response)
        val results = data.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        // Pick the best match: exact title match, else first result
        val queryLower = query.trim().lowercase()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val title = r.optString("title", "").lowercase()
            if (title == queryLower) {
                return r.optInt("id").toString()
            }
        }
        return results.optJSONObject(0)?.optInt("id")?.toString()
    }

    /** Fetch one page of chapters (up to 500). Requires MD5 token. */
    private fun fetchChaptersPage(mangaId: String, offset: Int): JSONObject {
        val ts = System.currentTimeMillis() / 1000
        val token = generateToken(ts)
        val url = "$BASE/auth/manga-chapters?manga_id=${URLEncoder.encode(mangaId, "UTF-8")}" +
                  "&offset=$offset&limit=500&order=DESC&_t=${URLEncoder.encode(token, "UTF-8")}&_ts=$ts"
        return JSONObject(httpGet(url))
    }

    /** Fetch the image URLs for a chapter. No token needed. */
    private fun fetchChapterImages(chapterId: String): List<String> {
        val url = "$BASE/auth/chapter-content?chapter_id=${URLEncoder.encode(chapterId, "UTF-8")}"
        val data = JSONObject(httpGet(url))
        val images = data.optJSONArray("images") ?: return emptyList()
        val out = ArrayList<String>(images.length())
        for (i in 0 until images.length()) {
            val img = images.optString(i, "")
            if (img.startsWith("http")) out.add(img)
        }
        return out
    }

    /**
     * Generate the MD5 token required by /auth/manga-chapters.
     *
     * Algorithm (reversed from the site's JS):
     *   ts     = unix seconds
     *   hour   = UTC "yyyyMMddHH" (e.g. "2026072315")
     *   secret = "mng_ch_" + hour
     *   token  = md5( str(ts) + secret ).hex().substring(0,16)
     */
    private fun generateToken(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMddHH", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val hour = sdf.format(java.util.Date(ts * 1000))
        val secret = "mng_ch_$hour"
        val input = "$ts$secret"
        val md5 = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return md5.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    // ---------- Deduplication ----------

    /**
     * Deduplicate chapters by their normalized number.
     *
     * MangaTaro allows multiple scanlation groups to upload the same chapter,
     * so the API returns several entries with the same `chapter` number but
     * different `id`s (one per group). Without deduplication, the chapter
     * list shows "Chapter 1" three times — confusing for the user and it
     * breaks the chapter-count display.
     *
     * Selection policy when multiple entries share the same number:
     *   1. Prefer entries with a non-empty title (most informative).
     *   2. Among title-bearing entries, prefer the longest title (more detail).
     *   3. If no entries have a title, keep the FIRST one encountered (the
     *      API returns newest-first in DESC order, so this is the most
     *      recently uploaded version — usually the best scanlation).
     *
     * IMPORTANT: call this BEFORE reverse() — the "first encountered" tiebreak
     * relies on the API's DESC ordering.
     */
    private fun deduplicateChapters(chapters: List<JSONObject>): List<JSONObject> {
        val byNumber = LinkedHashMap<String, JSONObject>()
        for (ch in chapters) {
            val number = normalizeChapterNumber(ch.optString("chapter", ""))
            if (number.isBlank()) continue   // skip entries without a chapter number

            val existing = byNumber[number]
            if (existing == null) {
                byNumber[number] = ch
                continue
            }

            // Tie-break: prefer the entry with a longer title
            val existingTitle = existing.optString("title", "").trim()
            val newTitle = ch.optString("title", "").trim()
            if (newTitle.length > existingTitle.length) {
                byNumber[number] = ch
            }
        }
        return byNumber.values.toList()
    }

    // ---------- Chapter matching ----------

    private fun findChapter(chapters: List<JSONObject>, requested: String): JSONObject? {
        val requestedNorm = requested.trim()

        // Pass 1: exact match on chapter number
        for (ch in chapters) {
            val num = normalizeChapterNumber(ch.optString("chapter", ""))
            if (num == requestedNorm) return ch
        }

        // Pass 2: numeric equality (1 matches 1.0, 1.5 matches 1.5)
        val requestedNum = requestedNorm.toDoubleOrNull()
        if (requestedNum != null) {
            for (ch in chapters) {
                val num = ch.optString("chapter", "").toDoubleOrNull()
                if (num != null && num == requestedNum) return ch
            }
        }

        // Pass 3: case-insensitive title match
        for (ch in chapters) {
            val title = ch.optString("title", "")
            if (title.equals(requestedNorm, ignoreCase = true)) return ch
        }

        return null
    }

    private fun normalizeChapterNumber(raw: String): String {
        val num = raw.toDoubleOrNull() ?: return raw
        return if (num == num.toLong().toDouble()) num.toLong().toString()
        else num.toString().trimEnd('0').trimEnd('.')
    }

    // ---------- HTTP ----------

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, */*;q=0.8")
            setRequestProperty("Referer", "$BASE/")
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $code for $urlStr${if (err.isNotBlank()) ": ${err.take(200)}" else ""}")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, */*;q=0.8")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Referer", "$BASE/")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $code for $urlStr${if (err.isNotBlank()) ": ${err.take(200)}" else ""}")
        } finally {
            conn.disconnect()
        }
    }
}
