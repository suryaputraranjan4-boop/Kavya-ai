import java.util.Locale

fun main() {
    val original = "मेरी favorite movie Inception याद रखो"
    val lower = original.lowercase(Locale.ROOT)
    val suffixTriggers = listOf(" yaad rakho", " yaad rakhna", " याद रखो", " याद रखना")
    for (suffix in suffixTriggers) {
        val suffixLower = suffix.trim().lowercase(Locale.ROOT)
        if (lower.endsWith(suffixLower)) {
            val rawFact = original.substring(0, original.length - suffixLower.length).trim()
            println("MATCHED! rawFact='$rawFact'")
            return
        }
    }
    println("NOT MATCHED")
}
