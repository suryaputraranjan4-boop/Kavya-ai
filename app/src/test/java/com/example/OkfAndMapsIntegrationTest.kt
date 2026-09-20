package com.example

import com.example.memory.okf.OkfBm25Index
import com.example.memory.okf.OkfCategory
import com.example.memory.okf.OkfKnowledgeUnit
import com.example.memory.okf.OkfWorkingMemory
import com.example.scraper.maps.GoogleMapsQuery
import com.example.scraper.maps.GoogleMapsQueryParser
import com.example.scraper.maps.MapsScraperJob
import com.example.scraper.maps.MapsScraperJobStatus
import com.example.scraper.maps.ScrapedBusiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OkfAndMapsIntegrationTest {

    @Test
    fun testGoogleMapsQueryParser_English() {
        val query = GoogleMapsQueryParser.parse("Find 20 cafes in Surat with phone and rating")
        assertEquals("cafes", query.category)
        assertEquals("Surat", query.location)
        assertEquals(20, query.limit)
        assertEquals("cafes in Surat", query.toSearchTerm())
    }

    @Test
    fun testGoogleMapsQueryParser_Hinglish() {
        val query = GoogleMapsQueryParser.parse("Surat me 15 cafes dhundo")
        assertEquals("cafes", query.category)
        assertEquals("Surat", query.location)
        assertEquals(15, query.limit)
    }

    @Test
    fun testGoogleMapsQueryParser_DentistsInAhmedabad() {
        val query = GoogleMapsQueryParser.parse("Search dentists in Ahmedabad limit 10")
        assertEquals("dentists", query.category)
        assertEquals("Ahmedabad", query.location)
        assertEquals(10, query.limit)
    }

    @Test
    fun testScrapedBusiness_NormalizationAndCsv() {
        val business = ScrapedBusiness(
            id = "test-123",
            name = "Blue Tokai Coffee Roasters",
            category = "Cafe",
            address = "Dumas Road, Surat, Gujarat",
            phone = "+91 98765 43210",
            email = "info@bluetokai.com",
            website = "https://bluetokai.com",
            rating = 4.6f,
            reviewCount = 512,
            latitude = 21.1702,
            longitude = 72.8311
        )

        val jsonObj = business.toJsonObject()
        assertEquals("Blue Tokai Coffee Roasters", jsonObj.getString("name"))
        assertEquals(4.6, jsonObj.getDouble("rating"), 0.01)
        assertEquals(512, jsonObj.getInt("reviewCount"))

        val csv = business.toCsvRow()
        assertTrue(csv.contains("Blue Tokai Coffee Roasters"))
        assertTrue(csv.contains("4.6"))
        assertTrue(csv.contains("+91 98765 43210"))
    }

    @Test
    fun testOkfKnowledgeUnit_MarkdownSerialization() {
        val unit = OkfKnowledgeUnit(
            id = "unit-cafe-pref",
            title = "Preferred Coffee Shops",
            category = OkfCategory.PREFERENCE,
            content = "User prefers artisan cold brews and quiet workspaces in Surat.",
            tags = listOf("coffee", "preferences", "surat"),
            importance = 0.85f
        )

        val markdown = unit.toMarkdown()
        assertTrue(markdown.contains("---"))
        assertTrue(markdown.contains("id: \"unit-cafe-pref\""))
        assertTrue(markdown.contains("category: PREFERENCE"))
        assertTrue(markdown.contains("User prefers artisan cold brews"))

        val parsed = OkfKnowledgeUnit.fromMarkdown(markdown)
        assertNotNull(parsed)
        assertEquals("unit-cafe-pref", parsed?.id)
        assertEquals(OkfCategory.PREFERENCE, parsed?.category)
        assertEquals("Preferred Coffee Shops", parsed?.title)
        assertTrue(parsed?.tags?.contains("coffee") == true)
    }

    @Test
    fun testOkfBm25Index_Ranking() {
        val index = OkfBm25Index()
        val doc1 = OkfKnowledgeUnit(
            id = "doc1",
            title = "Surat Cafes",
            category = OkfCategory.FACT,
            content = "Best coffee places in Surat include Blue Tokai and Starbucks."
        )
        val doc2 = OkfKnowledgeUnit(
            id = "doc2",
            title = "Mumbai Gyms",
            category = OkfCategory.FACT,
            content = "Top fitness centers and workout gyms in Bandra Mumbai."
        )

        index.buildIndex(listOf(doc1, doc2))
        val results = index.search("Surat coffee cafe")
        assertFalse(results.isEmpty())
        assertEquals("doc1", results.first().first)
    }

    @Test
    fun testOkfWorkingMemory_DefaultConstraints() {
        val wm = OkfWorkingMemory()
        assertTrue(wm.safetyConstraints.isNotEmpty())
        assertTrue(wm.safetyConstraints.any { it.contains("destructive") })
    }

    @Test
    fun testMapsScraperJob_Lifecycle() {
        val query = GoogleMapsQuery(query = "Dentists in Delhi", category = "dentists", location = "Delhi", limit = 10)
        val job = MapsScraperJob(
            id = UUID.randomUUID().toString(),
            query = query,
            status = MapsScraperJobStatus.CREATED
        )

        assertEquals(MapsScraperJobStatus.CREATED, job.status)
        assertEquals(0, job.results.size)

        val runningJob = job.copy(status = MapsScraperJobStatus.RUNNING)
        assertEquals(MapsScraperJobStatus.RUNNING, runningJob.status)
    }
}
