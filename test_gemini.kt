import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import java.io.BufferedReader

fun main() {
    val apiKey = System.getenv("GEMINI_API_KEY") ?: "NO_KEY"
    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse&key=$apiKey")
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    
    val jsonPayload = """
    {
      "contents": [
        {
          "role": "user",
          "parts": [
            {
              "text": "Hi"
            }
          ]
        }
      ],
      "systemInstruction": {
        "parts": [
          {
            "text": "You are a helpful AI."
          }
        ]
      },
      "generationConfig": {
        "temperature": 0.7
      }
    }
    """.trimIndent()
    
    try {
        val writer = OutputStreamWriter(conn.outputStream)
        writer.write(jsonPayload)
        writer.flush()
        writer.close()
        
        val code = conn.responseCode
        println("Response Code: $code")
        
        val reader = if (code in 200..299) {
            BufferedReader(InputStreamReader(conn.inputStream))
        } else {
            BufferedReader(InputStreamReader(conn.errorStream))
        }
        
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            println(line)
        }
        reader.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
