package org.androidaudioplugin.greenhouse

import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.greenhouse.device.ParameterRandomizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class ParameterRandomizerTest {

    companion object {
        private const val DRAWS = 400
        private const val MANY_DRAWS = 4000
        private const val EDGE_TOLERANCE = 1e-9
        // The cutoff of a filter from 20 Hz to 20 kHz: halfway between its ends by octaves is the geometric mean
        private const val CUTOFF_MIDDLE_HZ = 632.4555
    }

    private val resonance = ParameterInformation(1, "Resonance", 0.0, 1.0, 0.2)
    private val cutoff = ParameterInformation(2, "Cutoff", 20.0, 20000.0, 1000.0)
    private val octave = ParameterInformation(3, "Octave", 0.0, 7.0, 0.0)
    private val sync = ParameterInformation(4, "Sync", 0.0, 1.0, 0.0)

    /** The new value of one parameter, given its current one. */
    private fun draw(parameter: ParameterInformation, current: Double, strength: Double, random: Random): Double {
        val changes = ParameterRandomizer.randomize(listOf(parameter), mapOf(parameter.id to current), strength, random)

        return changes.single().second
    }

    @Test
    fun parametersThatSetTheLevelOrSwitchOffTheSoundAreLeftAlone() {
        val parameters = listOf(
            resonance,
            ParameterInformation(5, "Master Volume", 0.0, 1.0, 0.8),
            ParameterInformation(6, "Bypass", 0.0, 1.0, 0.0),
            ParameterInformation(7, "OUTPUT LEVEL", 0.0, 1.0, 0.5),
            ParameterInformation(8, "Osc 1 Mute", 0.0, 1.0, 0.0),
            ParameterInformation(9, "Filter Cutoff", 20.0, 20000.0, 1000.0)
        )

        val changed = ParameterRandomizer.randomize(parameters, emptyMap(), 1.0, Random(1))

        // The name is looked at in any case; what is not a level or a switch of the sound changes
        assertEquals(listOf(1, 9), changed.map { it.first.id })
    }

    @Test
    fun aParameterWithNoRangeIsNotChanged() {
        val fixed = ParameterInformation(10, "Fixed", 1.0, 1.0, 1.0)

        assertTrue(ParameterRandomizer.randomize(listOf(fixed), emptyMap(), 1.0, Random(1)).isEmpty())
    }

    @Test
    fun withNoStrengthNothingChanges() {
        val parameters = listOf(resonance, cutoff, octave, sync)

        assertTrue(ParameterRandomizer.randomize(parameters, emptyMap(), 0.0, Random(1)).isEmpty())
        assertTrue(ParameterRandomizer.randomize(parameters, emptyMap(), -0.5, Random(1)).isEmpty())
    }

    @Test
    fun everyParameterThatCanChangeComesBack() {
        val changed = ParameterRandomizer.randomize(listOf(resonance, cutoff, octave, sync), emptyMap(), 1.0, Random(1))

        assertEquals(listOf(1, 2, 3, 4), changed.map { it.first.id })
    }

    @Test
    fun aSubtleChangeStaysWithinAQuarterOfTheRangeAroundTheCurrentValue() {
        val random = Random(1)
        val values = List(DRAWS) { draw(resonance, 0.5, 0.25, random) }

        // 0.25 of the range of 0 to 1, either side of 0.5
        assertTrue(values.all { it >= 0.25 - EDGE_TOLERANCE && it <= 0.75 + EDGE_TOLERANCE })
        // And it does move: the values are not all the same
        assertTrue(values.toSet().size > 10)
    }

    @Test
    fun theValueNeverLeavesTheRange() {
        val random = Random(2)

        // At the top of the range the change can only go down, and at the bottom only up
        assertTrue(List(DRAWS) { draw(resonance, 1.0, 0.5, random) }.all { it >= 0.5 - EDGE_TOLERANCE && it <= 1.0 })
        assertTrue(List(DRAWS) { draw(resonance, 0.0, 0.5, random) }.all { it >= 0.0 && it <= 0.5 + EDGE_TOLERANCE })
    }

    @Test
    fun aParameterWithoutACurrentValueStartsFromItsDefault() {
        val random = Random(3)

        // The default of the resonance is 0.2: a quarter of the range around it is 0 to 0.45
        val values = List(DRAWS) {
            ParameterRandomizer.randomize(listOf(resonance), emptyMap(), 0.25, random).single().second
        }

        assertTrue(values.all { it >= 0.0 && it <= 0.45 + EDGE_TOLERANCE })
    }

    @Test
    fun atFullStrengthTheWholeRangeIsOpen() {
        val random = Random(4)
        val values = List(DRAWS) { draw(resonance, 0.5, 1.0, random) }

        assertTrue(values.all { it >= 0.0 && it <= 1.0 })
        assertTrue(values.min() < 0.1)
        assertTrue(values.max() > 0.9)
    }

    @Test
    fun aVeryWideRangeIsSpreadByOctavesNotByValue() {
        val random = Random(5)
        val values = List(MANY_DRAWS) { draw(cutoff, 1000.0, 1.0, random) }

        assertTrue(values.all { it >= 20.0 && it <= 20000.0 })
        // By octaves half of the values are below the geometric mean; by value almost all would be above it
        val below = values.count { it < CUTOFF_MIDDLE_HZ }.toDouble() / MANY_DRAWS
        assertTrue("below the middle: $below", below > 0.45 && below < 0.55)
    }

    @Test
    fun wholeNumbersStayWholeAndInsideTheirRange() {
        val random = Random(6)
        val values = List(DRAWS) { draw(octave, 3.0, 1.0, random) }

        assertTrue(values.all { abs(it - it.roundToInt()) < EDGE_TOLERANCE && it >= 0.0 && it <= 7.0 })
        // Every kind of value comes up at full strength
        assertTrue(values.toSet().size >= 6)

        // A quarter of the range of 7 is 1.75: from 3 that is 1 to 5 once rounded
        val subtle = List(DRAWS) { draw(octave, 3.0, 0.25, random) }
        assertTrue(subtle.all { it >= 1.0 && it <= 5.0 })
    }

    @Test
    fun aSwitchChangesWithHalfTheStrength() {
        val random = Random(7)

        // Full strength: it ends on or off about equally often from where it is
        val full = List(MANY_DRAWS) { draw(sync, 0.0, 1.0, random) }
        assertTrue(full.all { it == 0.0 || it == 1.0 })
        val fullOn = full.count { it == 1.0 }.toDouble() / MANY_DRAWS
        assertTrue("on at full strength: $fullOn", fullOn > 0.45 && fullOn < 0.55)

        // A quarter of the strength flips it an eighth of the time
        val subtleOn = List(MANY_DRAWS) { draw(sync, 0.0, 0.25, random) }.count { it == 1.0 }.toDouble() / MANY_DRAWS
        assertTrue("on at a quarter: $subtleOn", subtleOn > 0.09 && subtleOn < 0.16)
    }

    @Test
    fun theSameSeedGivesTheSameSound() {
        val parameters = listOf(resonance, cutoff, octave, sync)

        val first = ParameterRandomizer.randomize(parameters, emptyMap(), 1.0, Random(99))
        val again = ParameterRandomizer.randomize(parameters, emptyMap(), 1.0, Random(99))
        val other = ParameterRandomizer.randomize(parameters, emptyMap(), 1.0, Random(100))

        assertEquals(first.map { it.second }, again.map { it.second })
        assertTrue(first.map { it.second } != other.map { it.second })
    }
}
