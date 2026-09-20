package com.example.scraper.maps

import java.util.Locale

data class GoogleMapsScraperQuery(
    val query: String,
    val category: String = "",
    val location: String = "",
    val keywords: List<String> = emptyList(),
    val limit: Int = 15,
    val requestedFields: List<String> = listOf("name", "category", "address", "phone", "website", "rating"),
    val extractEmail: Boolean = true,
    val extractSocial: Boolean = true
) {
    fun toSearchTerm(): String {
        return buildString {
            if (category.isNotBlank()) append(category)
            if (location.isNotBlank()) {
                if (isNotEmpty()) append(" in ")
                append(location)
            }
            if (isEmpty()) append(query)
        }.trim()
    }
}

/**
 * Natural language parser for Google Maps business research queries.
 * Supports English, Hindi, and Hinglish.
 */
object GoogleMapsQueryParser {

    private val CATEGORY_SYNONYMS = mapOf(
        "cafe" to listOf("cafe", "cafes", "coffee shop", "coffee"),
        "restaurant" to listOf("restaurant", "restaurants", "dhaba", "khana", "dining", "eatery", "bhojnalaya"),
        "gym" to listOf("gym", "gyms", "fitness", "workout", "fitness center"),
        "dentist" to listOf("dentist", "dentists", "dental clinic", "teeth"),
        "hospital" to listOf("hospital", "hospitals", "clinic", "doctor", "dispensary"),
        "hotel" to listOf("hotel", "hotels", "resort", "lodging", "stay"),
        "bakery" to listOf("bakery", "bakeries", "cake shop", "pastry"),
        "salon" to listOf("salon", "salons", "parlour", "barber", "haircut", "spa"),
        "school" to listOf("school", "schools", "college", "coaching", "classes", "academy"),
        "pharmacy" to listOf("pharmacy", "chemist", "medical store", "dawa")
    )

    private val COMMON_CITIES = listOf(
        "Surat", "Ahmedabad", "Mumbai", "Delhi", "Bangalore", "Bengaluru", "Pune",
        "Hyderabad", "Chennai", "Kolkata", "Jaipur", "Lucknow", "Kanpur", "Nagpur",
        "Indore", "Thane", "Bhopal", "Visakhapatnam", "Vadodara", "Ghaziabad",
        "Ludhiana", "Agra", "Nashik", "Faridabad", "Meerut", "Rajkot", "Varanasi",
        "Srinagar", "Aurangabad", "Dhanbad", "Amritsar", "Navi Mumbai", "Allahabad",
        "Ranchi", "Howrah", "Coimbatore", "Jabalpur", "Gwalior", "Vijayawada",
        "Jodhpur", "Madurai", "Raipur", "Kota", "Chandigarh", "Guwahati", "Solapur"
    )

    fun parse(rawInput: String): GoogleMapsScraperQuery {
        val trimmed = rawInput.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Extract limit (e.g. "20 cafes", "top 10", "5 dentists")
        val limitRegex = Regex("(?:top\\s+|\\b)(\\d{1,2})\\s*(?:businesses|places|results|cafes|restaurants|gyms|items)?")
        val limitMatch = limitRegex.find(lower)
        val extractedLimit = limitMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 50) ?: 15

        // 2. Extract Category
        var identifiedCategory = ""
        for ((cat, synonyms) in CATEGORY_SYNONYMS) {
            if (synonyms.any { lower.contains(Regex("\\b$it\\b")) }) {
                identifiedCategory = cat
                break
            }
        }

        // 3. Extract Location
        var identifiedLocation = ""
        for (city in COMMON_CITIES) {
            if (lower.contains(city.lowercase(Locale.ROOT))) {
                identifiedLocation = city
                break
            }
        }

        // If location wasn't in city list, inspect "in <Place>", "near <Place>", "<Place> me/mein"
        if (identifiedLocation.isBlank()) {
            val inMatch = Regex("\\b(?:in|at|near|around)\\s+([A-Za-z]+)").find(trimmed)
            if (inMatch != null) {
                identifiedLocation = inMatch.groupValues[1]
            } else {
                val hinglishMatch = Regex("([A-Za-z]+)\\s+(?:me|mein|ke pass|me se)\\b", RegexOption.IGNORE_CASE).find(trimmed)
                if (hinglishMatch != null) {
                    val candidate = hinglishMatch.groupValues[1]
                    if (!candidate.equals("find", ignoreCase = true) && !candidate.equals("search", ignoreCase = true)) {
                        identifiedLocation = candidate
                    }
                }
            }
        }

        // 4. Requested fields check
        val fields = mutableListOf("name", "category", "address")
        if (lower.contains("phone") || lower.contains("number") || lower.contains("contact")) {
            fields.add("phone")
        }
        if (lower.contains("website") || lower.contains("site") || lower.contains("url")) {
            fields.add("website")
        }
        if (lower.contains("rating") || lower.contains("star") || lower.contains("review")) {
            fields.add("rating")
        }
        if (lower.contains("email") || lower.contains("mail")) {
            fields.add("email")
        }

        // Fallback: If no category was explicitly recognized, clean the prompt for query
        val cleanQuery = if (identifiedCategory.isBlank() && identifiedLocation.isBlank()) {
            trimmed.replace(Regex("(?i)(find|search|karo|batao|dikhao|nikalo|please|kripya)"), "").trim()
        } else {
            trimmed
        }

        return GoogleMapsScraperQuery(
            query = cleanQuery,
            category = identifiedCategory,
            location = identifiedLocation,
            limit = extractedLimit,
            requestedFields = fields.distinct(),
            extractEmail = lower.contains("email") || lower.contains("mail"),
            extractSocial = lower.contains("social") || lower.contains("instagram")
        )
    }
}
