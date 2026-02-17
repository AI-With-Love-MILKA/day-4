package ru.milka.ai.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.concurrent.thread

private val modelName = System.getenv("OPENAI_MODEL") ?: "gpt-4.1-mini"

private data class TemperatureResult(
    val temperature: Double,
    val response: String
)

suspend fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Set OPENAI_API_KEY environment variable")

    val client = HttpClient(CIO)
    try {
        println("День 4: температура в OpenAI Responses API")
        println("Модель: $modelName")
        println("Введи один и тот же запрос, а приложение выполнит его с temperature=0, 0.7 и 1.2.")
        println("Для выхода введи 'exit'.")
        println()

        while (true) {
            print("Запрос > ")
            val userPrompt = readlnOrNull()?.trim().orEmpty()
            if (userPrompt.equals("exit", ignoreCase = true)) break
            if (userPrompt.isBlank()) continue

            println()
            println("=== Исходный запрос ===")
            println(userPrompt)
            println()

            val temperatures = listOf(0.0, 0.7, 1.2)
            val results = temperatures.map { temperature ->
                val response = requestResponse(
                    client = client,
                    apiKey = apiKey,
                    input = userPrompt,
                    temperature = temperature,
                    maxOutputTokens = 500
                )
                TemperatureResult(temperature = temperature, response = response)
            }

            if (results.any { isUnsupportedTemperatureError(it.response) }) {
                println("Ошибка: модель '$modelName' не поддерживает temperature.")
                println("Установи модель, которая поддерживает temperature, например:")
                println("export OPENAI_MODEL=\"gpt-4.1-mini\"")
                println()
                continue
            }

            printTemperatureResponses(results)
            val analysis = buildTemperatureAnalysis(client, apiKey, userPrompt, results)
            println(analysis)
            println()
        }
    } finally {
        client.close()
    }
}

private suspend fun requestResponse(
    client: HttpClient,
    apiKey: String,
    input: String,
    instructions: String? = null,
    temperature: Double? = null,
    maxOutputTokens: Int? = null
): String {
    val requestJson = buildJsonObject {
        put("model", modelName)
        put("input", input)
        if (instructions != null) put("instructions", instructions)
        if (temperature != null) put("temperature", temperature)
        if (maxOutputTokens != null) put("max_output_tokens", maxOutputTokens)
    }

    val spinner = startLoader("Запрос к модели (temperature=${temperature ?: "default"})")
    val responseText = try {
        client.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestJson.toString())
        }.bodyAsText()
    } finally {
        spinner.stop()
    }

    return parseOutputText(responseText)
}

private suspend fun buildTemperatureAnalysis(
    client: HttpClient,
    apiKey: String,
    prompt: String,
    results: List<TemperatureResult>
): String {
    val packedResults = buildString {
        results.forEach { result ->
            appendLine("temperature=${result.temperature}")
            appendLine(result.response)
            appendLine("---")
        }
    }

    return requestResponse(
        client = client,
        apiKey = apiKey,
        temperature = 0.0,
        maxOutputTokens = 700,
        instructions = """
Ты сравниваешь ответы одной и той же LLM при разных температурах.
Нужен короткий отчет на русском языке.

Формат:
1) Точность: сравни ответы между собой, отметь, где больше риск фактических ошибок.
2) Креативность: укажи, какой ответ самый творческий и почему.
3) Разнообразие: оцени насколько ответы отличаются по структуре/формулировкам.
4) Для каких задач лучше:
- temperature=0
- temperature=0.7
- temperature=1.2

Пиши конкретно и коротко.
""".trimIndent(),
        input = """
Исходный запрос:
$prompt

Ответы:
$packedResults
""".trimIndent()
    )
}

private fun parseOutputText(responseText: String): String {
    val json = Json.parseToJsonElement(responseText).jsonObject
    val errorMessage = extractTextValue((json["error"] as? JsonObject)?.get("message"))
    if (!errorMessage.isNullOrBlank()) {
        return "Ошибка API: $errorMessage"
    }

    val topLevelOutputText = extractTextValue(json["output_text"])
    if (!topLevelOutputText.isNullOrBlank()) {
        return topLevelOutputText
    }

    val answer = buildString {
        val output = json["output"]?.jsonArray ?: return@buildString
        for (item in output) {
            val obj = item as? JsonObject ?: continue
            val itemType = extractTextValue(obj["type"])
            if (itemType == "output_text") {
                append(extractTextValue(obj["text"]).orEmpty())
                continue
            }

            if (itemType != "message") continue
            val contentArr = obj["content"]?.jsonArray ?: continue
            for (contentItem in contentArr) {
                val contentObj = contentItem as? JsonObject ?: continue
                val contentType = extractTextValue(contentObj["type"])
                if (contentType == "output_text" || contentType == "text") {
                    val text = extractTextValue(contentObj["text"])
                        ?: extractTextValue(contentObj["value"])
                    append(text.orEmpty())
                }
            }
        }
    }
    if (answer.isNotBlank()) return answer

    val status = json["status"]?.jsonPrimitive?.contentOrNull
    val outputSize = json["output"]?.jsonArray?.size ?: 0
    return "Ошибка: не удалось извлечь текст ответа (status=$status, output_items=$outputSize)"
}

private fun printTemperatureResponses(results: List<TemperatureResult>) {
    results.forEachIndexed { index, result ->
        println("=== Ответ ${index + 1}: temperature=${result.temperature} ===")
        println(result.response)
        println()
    }
}

private class Loader(private val label: String) {
    @Volatile
    private var active = true

    private val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    private val thread = thread(start = true, isDaemon = true, name = "console-loader") {
        var i = 0
        while (active) {
            val frame = frames[i % frames.size]
            val color = 31 + (i % 6)
            print("\r\u001B[1;${color}m$frame\u001B[0m $label")
            Thread.sleep(90)
            i++
        }
    }

    fun stop() {
        active = false
        thread.join()
        print("\r\u001B[1;32m✔\u001B[0m $label\n")
    }
}

private fun startLoader(label: String): Loader = Loader(label)

private fun isUnsupportedTemperatureError(text: String): Boolean {
    return text.contains("Unsupported parameter: 'temperature'", ignoreCase = true)
}

private fun extractTextValue(element: JsonElement?): String? {
    if (element == null) return null
    return when (element) {
        is JsonObject -> extractTextValue(element["value"])
        else -> runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
    }
}
