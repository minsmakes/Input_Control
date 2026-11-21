package io.inputcontrol.ui.keyboard

internal data class Key(
    val id: Int,
    val name: String,
    val chars: String = "",
    val weight: Float = 1f,
)

private fun letters(s: String) =
    s.map { Key(it - 'a' + 4, it.uppercase(), it.toString() + it.uppercase()) }

private fun symbols(ids: IntRange, a: String, b: String) =
    ids.mapIndexed { i, id -> Key(id, a[i].toString(), "${a[i]}${b[i]}") }

private val punctuation =
    symbols(45..49, "-=[]\\", "_+{}|") + symbols(51..56, ";'\u0060,./", ":\"~<>?")

internal val qwerty =
    listOf(
        symbols(30..39, "1234567890", "!@#$%^&*()") + punctuation.take(2),
        letters("qwertyuiop") + punctuation.subList(2, 5),
        letters("asdfghjkl") + punctuation.subList(5, 7),
        listOf(Key(225, "Shift", weight = 1.45f)) +
            letters("zxcvbnm") +
            Key(42, "⌫", weight = 1.45f),
        listOf(
            Key(-2, "?123"),
            Key(224, "Ctrl"),
            Key(227, "Win"),
            Key(226, "Alt"),
            Key(-1, "한/영"),
            Key(54, ",", ",<"),
            Key(44, "Space", "  ", 3.3f),
            Key(55, ".", ".>"),
            Key(40, "Enter", weight = 1.55f),
        ),
    )
internal val editing =
    listOf(
        Key(41, "Esc"),
        Key(43, "Tab"),
        Key(76, "Delete"),
        Key(80, "←"),
        Key(82, "↑"),
        Key(81, "↓"),
        Key(79, "→"),
    )
internal val symbolRows =
    listOf(
        symbols(30..39, "!@#$%^&*()", "!@#$%^&*()"),
        punctuation,
        punctuation.map {
            it.copy(name = it.chars.last().toString(), chars = it.chars.last().toString().repeat(2))
        },
        listOf(Key(225, "Shift")) +
            "€£¥₩§©®°".map { Key(-3, it.toString(), it.toString()) } +
            Key(42, "⌫"),
        qwerty
            .last()
            .filter { it.id !in listOf(54, 55) }
            .map { if (it.id == -2) it.copy(name = "ABC") else it },
    )
internal val keypad =
    listOf(
        listOf(Key(95, "7", "77"), Key(96, "8", "88"), Key(97, "9", "99"), Key(84, "÷", "//")),
        listOf(Key(92, "4", "44"), Key(93, "5", "55"), Key(94, "6", "66"), Key(85, "×", "**")),
        listOf(Key(89, "1", "11"), Key(90, "2", "22"), Key(91, "3", "33"), Key(86, "−", "--")),
        listOf(
            Key(98, "0", "00"),
            Key(99, ".", ".."),
            Key(42, "⌫"),
            Key(87, "+", "++"),
            Key(88, "Enter"),
        ),
    )
internal val functions = (1..12).map { Key(57 + it, "F$it") }.chunked(4)
private const val latin = "qwertyuiopasdfghjklzxcvbnm"

internal fun label(key: Key, ko: Boolean, shift: Boolean): String {
    val i = if (key.id in 4..29) latin.indexOf(key.chars[0]) else -1
    if (ko && i >= 0)
        return (if (shift) "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ" else "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ")[i]
            .toString()
    if (shift && key.chars.isNotBlank()) return key.chars.last().toString()
    return if (key.id in 4..29) key.chars.first().toString() else key.name
}
