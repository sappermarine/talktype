package app.talktype

/**
 * Rewrites spelled-out numbers as digits: "twenty five" -> "25", "three point five" -> "3.5",
 * "five five five one two one two" -> "5551212", "twenty twenty six" -> "2026", "ten percent" -> "10%",
 * "fifty dollars" -> "$50".
 *
 * Two different numbers said back to back keep the first as a word ("two twelve inch pipes" ->
 * "two 12 inch pipes"), which is the usual style rule. A lone "one" stays a word unless it is
 * clearly a quantity ("one percent", "page one", "one or two"), because it is so often a pronoun.
 */
object Numbers {
    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)
    private val ORDINAL_UNITS = mapOf(
        "first" to "1st", "second" to "2nd", "third" to "3rd", "fourth" to "4th", "fifth" to "5th",
        "sixth" to "6th", "seventh" to "7th", "eighth" to "8th", "ninth" to "9th",
    )

    /** "one third", "three quarters": a number in front of these is a fraction, so it stays in words. */
    private val FRACTIONS = setOf(
        "half", "halves", "third", "thirds", "fourth", "fourths", "quarter", "quarters", "fifth", "fifths",
        "sixth", "sixths", "seventh", "sevenths", "eighth", "eighths", "ninth", "ninths", "tenth", "tenths",
    )

    /** Words after "one" that make it a quantity. */
    private val MEASURES = setOf(
        "percent", "am", "pm", "a.m", "p.m", "o'clock", "dollar", "dollars", "buck", "bucks", "cent", "cents",
        "second", "seconds", "minute", "minutes", "hour", "hours", "week", "weeks", "month", "months", "year", "years",
        "mile", "miles", "foot", "feet", "inch", "inches", "yard", "yards", "pound", "pounds", "lb", "lbs",
        "ton", "tons", "gallon", "gallons", "ounce", "ounces", "degree", "degrees", "k", "x",
    )

    /** Words before "one" that make it a label ("page one"). */
    private val LABELS = setOf(
        "number", "page", "step", "chapter", "version", "option", "room", "unit", "suite", "apartment", "apt",
        "line", "item", "phase", "level", "grade", "gate", "building", "lot", "bay", "route", "highway", "exit",
        "floor", "part", "section", "week", "day", "figure", "table", "tier", "plan",
    )
    private val CONNECTORS = setOf("or", "to", "and", "through")
    private val TIME_BEFORE = setOf("at", "by", "around", "before", "after", "until", "till", "from", "to")
    private val TIME_AFTER = setOf("am", "pm", "a.m", "p.m", "a", "p")

    private val TOKEN = Regex("""[A-Za-z]+(?:'[A-Za-z]+)?|\d+(?:[.,:]\d+)*|\s+|.""", RegexOption.DOT_MATCHES_ALL)

    private enum class Last { NONE, UNIT, TEEN, TENS, HUNDRED, SCALE, AND }

    /** One parsed number covering tokens [start, end). */
    private class Num(
        val start: Int,
        val end: Int,
        val value: Long,
        val words: Int,
        /** A single digit word ("five"), the building block of phone numbers and codes. */
        val single: Boolean,
        /** Set when the digits are not just [value]: "3.5", "23rd". */
        val text: String? = null,
        /** Fractions stay spelled out. */
        val keep: Boolean = false,
    ) {
        val plain get() = text == null && !keep
        fun digits() = text ?: if (value < 10_000) value.toString() else "%,d".format(value)
    }

    fun toDigits(input: String): String {
        val t = TOKEN.findAll(input).map { it.value }.toList()
        val out = StringBuilder()
        var i = 0
        while (i < t.size) {
            val first = parse(t, i, allowOh = false)
            if (first == null) {
                out.append(t[i++])
                continue
            }
            // Gather numbers said back to back, separated by nothing but spaces.
            val group = mutableListOf(first)
            while (true) {
                val next = nextWord(t, group.last().end) ?: break
                if (!t.subList(group.last().end, next).all { it.isBlank() }) break
                group.add(parse(t, next, allowOh = group.all { it.single }) ?: break)
            }
            out.append(render(t, group))
            i = group.last().end
        }
        return symbols(out.toString())
    }

    private const val AMOUNT = """(\d[\d,]*(?:\.\d+)?)"""
    private val PERCENT = Regex("""$AMOUNT\s+percent\b""", RegexOption.IGNORE_CASE)
    private val DOLLARS_CENTS = Regex("""(\d[\d,]*)\s+dollars?\s+and\s+(\d{1,2})\s+cents?\b""", RegexOption.IGNORE_CASE)
    private val DOLLARS = Regex("""$AMOUNT\s+dollars?\b""", RegexOption.IGNORE_CASE)

    /** "25 percent" -> "25%", "305 dollars" -> "$305", "5 dollars and 50 cents" -> "$5.50". */
    private fun symbols(text: String): String {
        var s = PERCENT.replace(text) { it.groupValues[1] + "%" }
        s = DOLLARS_CENTS.replace(s) { "$" + it.groupValues[1] + "." + it.groupValues[2].padStart(2, '0') }
        return DOLLARS.replace(s) { "$" + it.groupValues[1] }
    }

    private fun isWord(s: String) = s[0].isLetter()

    private fun nextWord(t: List<String>, from: Int): Int? {
        var j = from
        while (j < t.size && !isWord(t[j]) && !t[j][0].isDigit()) j++
        return j.takeIf { it < t.size && isWord(t[it]) }
    }

    /** The next word after [from] if only spaces (or a hyphen, when [hyphen]) lie in between. */
    private fun adjacentWord(t: List<String>, from: Int, hyphen: Boolean = false): Int? {
        var j = from
        while (j < t.size && (t[j].isBlank() || (hyphen && t[j] == "-"))) j++
        return j.takeIf { it < t.size && isWord(t[it]) }
    }

    private fun parse(t: List<String>, start: Int, allowOh: Boolean): Num? {
        if (!isWord(t[start])) return null
        var total = 0L
        var current = 0L
        var last = Last.NONE
        var lastScale = Long.MAX_VALUE
        var hundredUsed = false
        var words = 0
        var end = -1
        var j = start

        // "a hundred", "a thousand"
        if (t[j].lowercase() == "a") {
            val n = adjacentWord(t, j + 1) ?: return null
            val w = t[n].lowercase()
            if (w != "hundred" && w != "thousand") return null
            current = 1
            last = Last.UNIT
            words = 1
            j = n
        } else if (allowOh && (t[j].lowercase() == "oh" || t[j].lowercase() == "o")) {
            return Num(start, start + 1, 0, 1, single = true)
        }

        while (true) {
            val w = t[j].lowercase()
            val starts = last == Last.NONE || last == Last.HUNDRED || last == Last.SCALE || last == Last.AND
            when {
                w in UNITS && (starts || last == Last.TENS) -> {
                    // "zero" never combines: "twenty zero" is two numbers.
                    if (w == "zero" && last != Last.NONE) break
                    current += UNITS.getValue(w)
                    last = Last.UNIT
                }
                w in TEENS && starts -> {
                    current += TEENS.getValue(w)
                    last = Last.TEEN
                }
                w in TENS && starts -> {
                    current += TENS.getValue(w)
                    last = Last.TENS
                }
                w == "hundred" && !hundredUsed && current in 1..99 &&
                    (last == Last.UNIT || last == Last.TEEN || last == Last.TENS) -> {
                    current *= 100
                    hundredUsed = true
                    last = Last.HUNDRED
                }
                w in SCALES && current > 0 && last != Last.AND && SCALES.getValue(w) < lastScale -> {
                    lastScale = SCALES.getValue(w)
                    total += current * lastScale
                    current = 0
                    hundredUsed = false
                    last = Last.SCALE
                }
                w == "and" && (last == Last.HUNDRED || last == Last.SCALE) -> {
                    val n = adjacentWord(t, j + 1)?.let { t[it].lowercase() }
                    if (n == null || (n !in UNITS && n !in TEENS && n !in TENS) || n == "zero") break
                    last = Last.AND
                }
                else -> break
            }
            if (last != Last.AND) {
                words++
                end = j + 1
            }
            if (w == "zero") break
            j = adjacentWord(t, j + 1, hyphen = last == Last.TENS) ?: break
        }
        if (end < 0 || (words == 1 && t[start].lowercase() == "a")) return null
        val value = total + current
        val single = words == 1 && t[start].lowercase() in UNITS

        val after = adjacentWord(t, end, hyphen = true)
        val afterWord = after?.let { t[it].lowercase() }
        if (afterWord != null) {
            // "twenty third" -> 23rd
            if (last == Last.TENS && afterWord in ORDINAL_UNITS) {
                val ordinal = ORDINAL_UNITS.getValue(afterWord)
                return Num(start, after + 1, value, words + 1, false, text = "${value / 10}$ordinal")
            }
            if (afterWord in FRACTIONS && afterWord != "second") return Num(start, end, value, words, single, keep = true)
            // "three point five" -> 3.5
            if (afterWord == "point") {
                val decimals = StringBuilder()
                var k = adjacentWord(t, after + 1)
                var decimalEnd = -1
                while (k != null) {
                    val d = t[k].lowercase()
                    decimals.append(if (d == "oh") 0 else UNITS[d] ?: break)
                    decimalEnd = k + 1
                    k = adjacentWord(t, k + 1)
                }
                if (decimalEnd > 0) return Num(start, decimalEnd, value, words, false, text = "$value.$decimals")
            }
        }
        return Num(start, end, value, words, single)
    }

    private fun original(t: List<String>, n: Num) = t.subList(n.start, n.end).joinToString("")

    private fun render(t: List<String>, group: List<Num>): String {
        if (group.size == 1) {
            val n = group[0]
            val asWords = n.keep || (n.single && n.value == 1L && !oneIsQuantity(t, n))
            return if (asWords) original(t, n) else n.digits()
        }
        // Phone numbers, codes, zip codes: "five five five one two one two"
        if (group.size >= 3 && group.all { it.single }) return group.joinToString("") { it.value.toString() }
        if (group.size == 2 && group.all { it.plain }) {
            val (a, b) = group
            // Years: "nineteen ninety nine", "twenty twenty six"
            if (a.words == 1 && (a.value == 19L || a.value == 20L) && b.value in 10..99) return "${a.value}${b.value}"
            // Clock times, when the words around it say so: "at seven thirty", "ten fifteen pm"
            if (a.value in 1..12 && b.value in 10..59) {
                val before = previousWord(t, a.start)
                val after = adjacentWord(t, b.end)?.let { t[it].lowercase() }
                if (before in TIME_BEFORE || after in TIME_AFTER) return "${a.value}:${b.value}"
            }
            // Two separate numbers in a row: spell the first, digits for the second.
            return original(t, a) + " " + b.digits()
        }
        return group.joinToString(" ") { if (it.keep) original(t, it) else it.digits() }
    }

    private fun previousWord(t: List<String>, start: Int): String? {
        var j = start - 1
        while (j >= 0 && t[j].isBlank()) j--
        return t.getOrNull(j)?.takeIf { isWord(it) }?.lowercase()
    }

    private fun numberish(w: String?) =
        w != null && (w[0].isDigit() || w in UNITS || w in TEENS || w in TENS)

    /** Whether a lone "one" is a count or label rather than a pronoun ("this one", "no one"). */
    private fun oneIsQuantity(t: List<String>, n: Num): Boolean {
        val after = adjacentWord(t, n.end)?.let { t[it].lowercase() }
        val before = previousWord(t, n.start)
        if (after in MEASURES || before in LABELS) return true

        // Part of a list or range of numbers: "one or two", "one, two, three", "five to one"
        fun step(from: Int, dir: Int): String? {
            var j = from
            while (j in t.indices && (t[j].isBlank() || t[j] == ",")) j += dir
            return t.getOrNull(j)?.lowercase()
        }
        var next = step(n.end, 1)
        if (next in CONNECTORS) next = step(t.indexOfFirstFrom(n.end) { it.lowercase() == next } + 1, 1)
        if (numberish(next)) return true
        var prev = step(n.start - 1, -1)
        if (prev in CONNECTORS) prev = step(t.indexOfLastBefore(n.start) { it.lowercase() == prev } - 1, -1)
        return numberish(prev)
    }

    private inline fun List<String>.indexOfFirstFrom(from: Int, p: (String) -> Boolean): Int {
        for (i in from until size) if (p(this[i])) return i
        return size
    }

    private inline fun List<String>.indexOfLastBefore(before: Int, p: (String) -> Boolean): Int {
        for (i in before - 1 downTo 0) if (p(this[i])) return i
        return -1
    }
}
