package app.eikon.gallery.data.places

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lookup logic on a tiny fixture, plus a sanity check of the real bundled GeoNames assets. */
class GazetteerTest {
    private val cities = listOf(
        "3169070\tRome\t41.892\t12.511\tIT\t07\t2318895\trom|roma|romma",
        "3173435\tMilan\t45.464\t9.190\tIT\t09\t1236837\tmilano|milan",
        "3172394\tNaples\t40.852\t14.268\tIT\t04\t989111\tnapoli|naples",
        "5128581\tNew York City\t40.714\t-74.006\tUS\tNY\t8175133\tnew york|nyc",
        "2988507\tParis\t48.853\t2.349\tFR\tIle-de-France\t2138551\tparigi",
        "9999991\tRoma\t-26.57\t148.79\tAU\tQLD\t6000\t",
    )
    private val regions = listOf("IT.07\tLazio", "IT.16\tTuscany", "IT.09\tLombardy")
    private val aliases = listOf("IT.16\ttoscana", "IT.09\tlombardia")

    private val gazetteer = Gazetteer.build(
        cities.asSequence(), regions.asSequence(), aliases.asSequence(), listOf(Locale.ITALIAN, Locale.ENGLISH),
    )

    @Test
    fun coordinatesResolveToTheNearestCity() {
        assertEquals("Rome", gazetteer.nearest(41.9, 12.5)!!.name)
        assertEquals("Milan", gazetteer.nearest(45.5, 9.2)!!.name)
        assertEquals("Paris", gazetteer.nearest(48.9, 2.3)!!.name)
    }

    @Test
    fun aPointBetweenCitiesPicksTheCloserOne() {
        // Halfway-ish along the Rome to Naples road, but nearer Naples.
        assertEquals("Naples", gazetteer.nearest(41.1, 14.0)!!.name)
    }

    @Test
    fun aCityJustOutsideTheNearestCellsIsNotMissedTowardsThePoles() {
        // At 60 degrees north a degree of longitude is about 55 km. Vik is 27 km east and 66 km north of the point, in the point's own cell row; Hamn is 57 km away but two cells east.
        val north = Gazetteer.build(
            sequenceOf("1\tVik\t60.6\t10.5\tNO\t01\t20000\t", "2\tHamn\t60.0\t12.01\tNO\t01\t20000\t"),
            emptySequence(), emptySequence(), listOf(Locale.ENGLISH),
        )

        assertEquals("Hamn", north.nearest(60.0, 10.99)!!.name)
    }

    @Test
    fun aCityIsFoundByItsIdWithoutScanningAndAMissingOneIsNull() {
        assertEquals("Milan", gazetteer.city(3173435)!!.name)
        assertNull(gazetteer.city(42))
    }

    @Test
    fun aPointFarFromEveryCityIsUnknown() {
        assertNull(gazetteer.nearest(0.0, 0.0)) // mid-Atlantic
        assertNull(gazetteer.nearest(41.9, 12.5, maxKm = 0.1))
    }

    @Test
    fun theSameNameResolvesToEveryMatchingCity() {
        val roma = gazetteer.match("roma")!!
        assertEquals(setOf(3169070L, 9999991L), roma.cityIds)
    }

    @Test
    fun alternateNamesAndMultiWordNamesMatch() {
        assertEquals(setOf(3173435L), gazetteer.match("milano")!!.cityIds)
        assertEquals(setOf(5128581L), gazetteer.match("new york")!!.cityIds)
        assertEquals(setOf(2988507L), gazetteer.match("parigi")!!.cityIds)
    }

    @Test
    fun regionsAreFoundByOfficialNameAndByLocalAlias() {
        assertEquals(setOf("IT.16"), gazetteer.match("tuscany")!!.regionKeys)
        assertEquals(setOf("IT.16"), gazetteer.match("toscana")!!.regionKeys)
        assertEquals(setOf("IT.07"), gazetteer.match("lazio")!!.regionKeys)
    }

    @Test
    fun countriesAreFoundInTheConfiguredLanguages() {
        assertEquals(setOf("IT"), gazetteer.match("italia")!!.countryCodes)
        assertEquals(setOf("IT"), gazetteer.match("italy")!!.countryCodes)
        assertEquals(setOf("FR"), gazetteer.match("francia")!!.countryCodes)
    }

    @Test
    fun unknownWordsMatchNothing() {
        assertNull(gazetteer.match("cane"))
        assertNull(gazetteer.match(""))
    }

    @Test
    fun regionKeyJoinsCountryAndAdmin1() {
        assertEquals("IT.07", gazetteer.nearest(41.9, 12.5)!!.regionKey)
    }

    @Test
    fun distanceIsAboutRightOnAKnownPair() {
        // Rome to Milan is roughly 477 km as the crow flies.
        val km = Gazetteer.haversineKm(41.892, 12.511, 45.464, 9.190)
        assertTrue("km=$km", km in 470.0..485.0)
    }

    // --- the real bundled data ---------------------------------------------------------------

    private fun real(): Gazetteer {
        val dir = File("src/main/assets/places")
        return Gazetteer.build(
            File(dir, "cities.tsv").readLines().asSequence(),
            File(dir, "regions.tsv").readLines().asSequence(),
            File(dir, "aliases.tsv").readLines().asSequence(),
            listOf(Locale.ITALIAN, Locale.ENGLISH),
        )
    }

    @Test
    fun theBundledDataLoadsAndKnowsFamousPlaces() {
        val real = real()
        assertTrue("cities=${real.cityCount}", real.cityCount > 30_000)
        assertEquals("Rome", real.nearest(41.9028, 12.4964)!!.name)
        assertEquals("Milan", real.nearest(45.4642, 9.19)!!.name)
        assertNotNull(real.match("roma")!!.cityIds.firstOrNull())
        assertEquals(setOf("IT"), real.match("italia")!!.countryCodes)
        assertEquals(setOf("IT.16"), real.match("toscana")!!.regionKeys)
        assertEquals(setOf("IT.15"), real.match("sicilia")!!.regionKeys)
    }

    @Test
    fun aCityIsFoundUnderItsItalianAndEnglishNamesInTheBundledData() {
        val real = real()
        val rome = real.nearest(41.9028, 12.4964)!!.id
        listOf("rome", "roma").forEach { assertTrue("$it", rome in real.match(it)!!.cityIds) }
        val florence = real.nearest(43.7696, 11.2558)!!.id
        listOf("florence", "firenze").forEach { assertTrue("$it", florence in real.match(it)!!.cityIds) }
    }
}
