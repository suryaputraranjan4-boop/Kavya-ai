package com.example.scraper.maps

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Normalized data model for business entities discovered via Google Maps Scraper Kit.
 * Only populates fields actually returned by the scraper; never fabricates missing information.
 */
data class ScrapedBusiness(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val rating: Float = 0.0f,
    val reviewCount: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val socialProfiles: Map<String, String> = emptyMap(),
    val source: String = "Google Maps Scraper",
    val placeUrl: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category)
        put("address", address)
        put("phone", phone)
        put("email", email)
        put("website", website)
        put("rating", rating.toDouble())
        put("reviewCount", reviewCount)
        if (latitude != null) put("latitude", latitude)
        if (longitude != null) put("longitude", longitude)
        val socialsObj = JSONObject()
        socialProfiles.forEach { (k, v) -> socialsObj.put(k, v) }
        put("socialProfiles", socialsObj)
        put("source", source)
        put("placeUrl", placeUrl)
    }

    fun toCsvRow(): String {
        return listOf(
            escapeCsv(name),
            escapeCsv(category),
            "%.1f".format(rating),
            reviewCount.toString(),
            escapeCsv(phone),
            escapeCsv(website),
            escapeCsv(email),
            escapeCsv(address),
            escapeCsv(placeUrl)
        ).joinToString(",")
    }

    private fun escapeCsv(value: String): String {
        val s = value.trim()
        return if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            "\"${s.replace("\"", "\"\"")}\""
        } else {
            s
        }
    }

    companion object {
        fun csvHeader(): String {
            return "Name,Category,Rating,Reviews,Phone,Website,Email,Address,PlaceUrl"
        }

        /**
         * Robust parser accommodating different scraper output naming variations
         * (e.g., Mahanaicoach/google-maps-scraper-kit and standard JSON schemas).
         */
        fun fromFlexibleJson(obj: JSONObject): ScrapedBusiness {
            val name = obj.optString("name", obj.optString("title", obj.optString("business_name", "Unknown Business")))
            val category = obj.optString("category", obj.optString("type", obj.optString("industry", "")))
            val address = obj.optString("address", obj.optString("full_address", obj.optString("formatted_address", "")))
            val phone = obj.optString("phone", obj.optString("phone_number", obj.optString("tel", obj.optString("telephone", ""))))
            val email = obj.optString("email", obj.optString("emails", ""))
            val website = obj.optString("website", obj.optString("url", obj.optString("link", obj.optString("site", ""))))
            val placeUrl = obj.optString("place_url", obj.optString("maps_url", obj.optString("google_maps_link", "")))

            val rawRating = obj.optDouble("rating", obj.optDouble("stars", obj.optDouble("score", 0.0)))
            val rating = rawRating.toFloat().coerceIn(0.0f, 5.0f)

            val reviewCount = obj.optInt("review_count", obj.optInt("reviews", obj.optInt("reviews_count", obj.optInt("user_ratings_total", 0))))

            var lat: Double? = if (obj.has("latitude")) obj.optDouble("latitude") else null
            var lng: Double? = if (obj.has("longitude")) obj.optDouble("longitude") else null

            // Check nested location object
            val locObj = obj.optJSONObject("location") ?: obj.optJSONObject("coordinates")
            if (locObj != null) {
                if (lat == null && locObj.has("lat")) lat = locObj.optDouble("lat")
                if (lng == null && (locObj.has("lng") || locObj.has("lon"))) {
                    lng = locObj.optDouble("lng", locObj.optDouble("lon"))
                }
            }

            val socials = mutableMapOf<String, String>()
            val socialsObj = obj.optJSONObject("social_profiles") ?: obj.optJSONObject("socials")
            if (socialsObj != null) {
                socialsObj.keys().forEach { k ->
                    socials[k] = socialsObj.optString(k)
                }
            }

            return ScrapedBusiness(
                name = name.trim(),
                category = category.trim(),
                address = address.trim(),
                phone = phone.trim(),
                email = email.trim(),
                website = website.trim(),
                rating = rating,
                reviewCount = reviewCount,
                latitude = lat,
                longitude = lng,
                socialProfiles = socials,
                source = "Google Maps Scraper",
                placeUrl = placeUrl.trim()
            )
        }
    }
}
