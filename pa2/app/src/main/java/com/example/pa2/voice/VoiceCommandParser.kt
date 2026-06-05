package com.example.pa2.voice

import com.example.pa2.detector.CocoLabels

/**
 * Parses free-form speech transcripts into a structured command.
 *
 * The required commands from the assignment are:
 *   - "find/target/detect <object>"   → TargetSet
 *   - "clear target"                   → ClearTarget
 *   - "switch to fp16/int8/fp32"       → SwitchPrecision
 *
 * Beyond the required set, the parser also recognises several
 * system-level commands that affect runtime behaviour. Each is
 * documented in [Result] and demonstrated in the report:
 *
 *   - CountQuery       — "how many X do you see?"
 *                          → app counts current detections of class X
 *                            and speaks the answer via TextToSpeech.
 *
 *   - ToggleAnnounce   — "announce when found" / "stop announcing"
 *                          → toggles audio TTS announcement when the
 *                            target appears, so the user can keep their
 *                            eyes off-screen (e.g. accessibility).
 *
 *   - Pause / Resume   — "pause" / "resume"
 *                          → pauses or resumes inference without
 *                            having to reach for the on-screen button.
 *
 *   - Screenshot       — "take a screenshot" / "capture"
 *                          → saves the current annotated frame.
 *
 *   - FilterOnly       — "only show <X>"
 *                          → hides every non-target box for an
 *                            uncluttered view in busy scenes.
 *   - ShowAll          — "show all" / "show everything" → undoes filter.
 *
 *   - SwitchToFastest  — "use the fastest" / "switch to fastest"
 *                          → automatically picks the precision with the
 *                            highest rolling FPS so far. Demonstrates
 *                            data-driven precision selection.
 *
 *   - ResetStats       — "reset stats" / "reset benchmark"
 *                          → wipes the dashboard counters so the user
 *                            can start a fresh measurement window.
 */
object VoiceCommandParser {

    sealed class Result {
        data class TargetSet(val classId: Int, val label: String, val rawTranscript: String) : Result()
        data class ClearTarget(val rawTranscript: String) : Result()
        data class SwitchPrecision(val precision: String, val rawTranscript: String) : Result()
        data class CountQuery(val classId: Int, val label: String, val rawTranscript: String) : Result()
        data class ToggleAnnounce(val enable: Boolean, val rawTranscript: String) : Result()
        data class Pause(val rawTranscript: String) : Result()
        data class Resume(val rawTranscript: String) : Result()
        data class Screenshot(val rawTranscript: String) : Result()
        data class FilterOnly(val classId: Int, val label: String, val rawTranscript: String) : Result()
        data class ShowAll(val rawTranscript: String) : Result()
        data class SwitchToFastest(val rawTranscript: String) : Result()
        data class ResetStats(val rawTranscript: String) : Result()
        data class Unsupported(val rawTranscript: String, val reason: String) : Result()
    }

    private val FIND_VERBS = listOf(
        "find me a ", "find me ", "find a ", "find the ", "find ",
        "target the ", "target a ", "target ",
        "detect the ", "detect a ", "detect ",
        "search for a ", "search for the ", "search for ",
        "look for a ", "look for the ", "look for ",
        "show me the ", "show me a ", "show me ",
        "where is the ", "where is a ", "where is ", "where's ",
        "spot a ", "spot the ", "spot "
    )

    private val ONLY_VERBS = listOf(
        "only show me a ", "only show me the ", "only show me ",
        "only show a ", "only show the ", "only show ",
        "show only a ", "show only the ", "show only ",
        "filter to ", "just the ", "just "
    )

    private val CLEAR_PHRASES = listOf(
        "clear target", "clear the target", "no target", "remove target",
        "stop targeting", "cancel target", "reset target"
    )

    private val PAUSE_PHRASES = listOf(
        "pause", "freeze", "stop inference", "stop detecting", "stop"
    )
    private val RESUME_PHRASES = listOf(
        "resume", "continue", "start inference", "start detecting", "start", "go"
    )

    private val SHOWALL_PHRASES = listOf(
        "show all", "show everything", "show every", "remove filter", "clear filter", "unfilter"
    )

    private val SCREENSHOT_PHRASES = listOf(
        "screenshot", "take a screenshot", "take screenshot",
        "capture", "capture screen", "save screen", "save frame"
    )

    private val FASTEST_PHRASES = listOf(
        "fastest", "use the fastest", "use fastest", "switch to fastest",
        "switch to the fastest", "best speed", "best fps"
    )

    private val RESET_STATS_PHRASES = listOf(
        "reset stats", "reset the stats", "reset benchmark", "reset metrics",
        "clear stats", "clear metrics"
    )

    private val ANNOUNCE_ON_PHRASES = listOf(
        "announce when found", "announce target", "tell me when you find",
        "tell me when found", "speak when found", "voice on", "announcements on"
    )
    private val ANNOUNCE_OFF_PHRASES = listOf(
        "stop announcing", "no announcements", "voice off", "announcements off",
        "be quiet", "silent mode"
    )

    private val SYNONYMS: Map<String, String> = mapOf(
        "phone" to "cell phone", "cellphone" to "cell phone",
        "mobile" to "cell phone", "mobile phone" to "cell phone",
        "smartphone" to "cell phone",
        "television" to "tv", "monitor" to "tv", "screen" to "tv",
        "sofa" to "couch", "settee" to "couch",
        "kitty" to "cat",
        "puppy" to "dog", "doggie" to "dog", "doggy" to "dog",
        "auto" to "car", "automobile" to "car",
        "bike" to "bicycle", "cycle" to "bicycle",
        "motorbike" to "motorcycle",
        "plane" to "airplane", "aeroplane" to "airplane",
        "fridge" to "refrigerator",
        "computer" to "laptop", "notebook" to "laptop",
        "people" to "person", "man" to "person", "woman" to "person",
        "guy" to "person", "lady" to "person", "human" to "person",
        "water bottle" to "bottle",
        "mug" to "cup", "coffee cup" to "cup",
        "plant" to "potted plant", "flower" to "potted plant",
        "remote control" to "remote", "controller" to "remote",
        "key board" to "keyboard"
    )

    fun parse(transcript: String): Result {
        val raw = transcript.trim()
        if (raw.isEmpty()) return Result.Unsupported(raw, "Empty transcript")
        var text = raw.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Direct command matches (no verb stripping).
        if (CLEAR_PHRASES.any { text.contains(it) }) return Result.ClearTarget(raw)
        if (ANNOUNCE_OFF_PHRASES.any { text.contains(it) }) return Result.ToggleAnnounce(false, raw)
        if (ANNOUNCE_ON_PHRASES.any { text.contains(it) }) return Result.ToggleAnnounce(true, raw)
        if (SCREENSHOT_PHRASES.any { text.contains(it) }) return Result.Screenshot(raw)
        if (SHOWALL_PHRASES.any { text.contains(it) }) return Result.ShowAll(raw)
        if (RESET_STATS_PHRASES.any { text.contains(it) }) return Result.ResetStats(raw)
        if (FASTEST_PHRASES.any { text.contains(it) }) return Result.SwitchToFastest(raw)
        // Pause / resume: substring match. Order matters — "stop targeting"
        // is already caught by CLEAR_PHRASES above, so a bare "stop" here is
        // safe. "go" is intentionally exact-only because it appears in many
        // unrelated phrases.
        val isExactGo = (text == "go" || text == "go now")
        if (isExactGo) return Result.Resume(raw)
        if (PAUSE_PHRASES.any { it != "stop" && text.contains(it) }
            || text == "stop") return Result.Pause(raw)
        if (RESUME_PHRASES.any { it != "go" && text.contains(it) }) return Result.Resume(raw)

        // "switch to fp16" / "use int8" / etc.
        Regex("\\b(fp32|fp16|int8)\\b").find(text)?.let { m ->
            return Result.SwitchPrecision(m.value.uppercase(), raw)
        }

        // "how many X do you see"
        Regex("how many (?:.*? )?([a-z ]+?)(?: do you see| are there| can you see)?$")
            .find(text)?.groupValues?.get(1)?.trim()?.let { obj ->
                resolveClass(obj)?.let { (id, label) ->
                    return Result.CountQuery(id, label, raw)
                }
            }

        // "only show <X>" / "show only <X>" / "filter to X"
        for (v in ONLY_VERBS) {
            if (text.startsWith(v)) {
                val obj = text.removePrefix(v).trim()
                resolveClass(obj)?.let { (id, label) ->
                    return Result.FilterOnly(id, label, raw)
                }
                break
            }
        }

        // "find <X>" etc.
        for (v in FIND_VERBS) {
            if (text.startsWith(v)) {
                val obj = text.removePrefix(v).trim()
                resolveClass(obj)?.let { (id, label) ->
                    return Result.TargetSet(id, label, raw)
                }
                break
            }
        }

        // Bare class name as a last resort ("bottle")
        resolveClass(text)?.let { (id, label) ->
            return Result.TargetSet(id, label, raw)
        }

        return Result.Unsupported(raw, "No matching command")
    }

    /**
     * Resolve a free-text noun phrase to a (classId, canonicalLabel) pair.
     * Order: (1) exact synonym, (2) multi-word COCO label match,
     * (3) last-token fallback over single-word COCO labels and synonyms.
     */
    private fun resolveClass(s: String): Pair<Int, String>? {
        if (s.isBlank()) return null

        SYNONYMS[s]?.let { canonical ->
            val id = CocoLabels.indexOf(canonical)
            if (id >= 0) return id to canonical
        }
        val sortedSyn = SYNONYMS.keys.sortedByDescending { it.length }
        for (k in sortedSyn) {
            if (s == k || s.contains(" $k") || s.startsWith("$k ")) {
                val canonical = SYNONYMS[k]!!
                val id = CocoLabels.indexOf(canonical)
                if (id >= 0) return id to canonical
            }
        }
        val multi = CocoLabels.LABELS.filter { it.contains(' ') }
            .sortedByDescending { it.length }
        for (label in multi) {
            if (s.contains(label)) return CocoLabels.indexOf(label) to label
        }
        val tokens = s.split(' ').filter { it.isNotBlank() }
        for (tok in tokens.asReversed()) {
            CocoLabels.LABELS.firstOrNull { it == tok }?.let { lbl ->
                return CocoLabels.indexOf(lbl) to lbl
            }
            SYNONYMS[tok]?.let { canonical ->
                val id = CocoLabels.indexOf(canonical)
                if (id >= 0) return id to canonical
            }
        }
        return null
    }
}
