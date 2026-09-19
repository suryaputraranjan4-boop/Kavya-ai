import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val apiKey = System.getenv("GEMINI_API_KEY") ?: "NO_KEY"
    if (apiKey == "NO_KEY") {
        println("NO API KEY")
        exitProcess(1)
    }
    
    val models = listOf("gemini-2.0-flash", "gemini-2.0-flash-exp")
    for (model in models) {
        val url = URL("https://generativelanguage.googleapis.com/v1alpha/models/\$model:generateContent?key=\$apiKey")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        
        val json = """
        {
          "contents": [
            {
              "role": "user",
              "parts": [{"text": "Hello world"}]
            }
          ],
          "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
              "voiceConfig": {
                "prebuiltVoiceConfig": {
                  "voiceName": "Kore"
                }
              }
            }
          }
        }
        """.trimIndent()
        
        try {
            conn.outputStream.use { os ->
                val input = json.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            val responseCode = conn.responseCode
            println("\$model -> \$responseCode")
            if (responseCode >= 400) {
                val error = conn.errorStream.bufferedReader().use { it.readText() }
                println("Error: \$error")
            } else {
                println("Success")
                break
            }
        } catch (e: Exception) {
            println("Exception on \$model: \${e.message}")
        }
    }
}
