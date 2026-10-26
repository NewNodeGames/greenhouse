package org.androidaudioplugin.greenhouse.device

import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.greenhouse.ui.model.ParameterType
import org.androidaudioplugin.greenhouse.ui.model.inferredType
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Picks new values for the parameters of a plugin at random, to explore sounds. The values are chosen by what
 * each parameter is: a continuous one (spread over its range, or over its octaves if the range is very wide,
 * like the cutoff of a filter), a whole number, one of a list of options, or a switch.
 *
 * The strength says how far from where a parameter is the new value can fall: at 100 percent anywhere in its
 * range, at less, within that share of the range around the current value. The parameters that set the
 * overall level or switch the sound off are left as they are ([PROTECTED_NAME_PARTS]).
 */
object ParameterRandomizer {
    /** A parameter whose name has one of these (in any case) is left alone: a volume that jumps or a plugin that goes mute is no exploration. */
    val PROTECTED_NAME_PARTS = listOf("master", "output", "volume", "bypass", "mute", "solo", "power", "enable", "active", "on/off")

    // A positive range this many times wider from its start to its end is spread by octaves, not by value
    private const val LOGARITHMIC_RANGE_RATIO = 100.0

    // A switch changes with this share of the strength: at full strength it ends up on or off equally often
    private const val SWITCH_CHANGE_SHARE = 0.5

    fun isProtected(parameter: ParameterInformation): Boolean {
        val name = parameter.name.lowercase()

        return PROTECTED_NAME_PARTS.any { name.contains(it) }
    }

    /**
     * The new value of every parameter that can change, as pairs ready to send to the plugin. [currentValues] are
     * the values now, by parameter id (a missing one is at its default). [strength] is from 0 to 1; with none,
     * nothing changes.
     */
    fun randomize(
        parameters: List<ParameterInformation>,
        currentValues: Map<Int, Double>,
        strength: Double,
        random: Random
    ): List<Pair<ParameterInformation, Double>> {
        val amount = strength.coerceIn(0.0, 1.0)

        if (amount <= 0.0) {
            return emptyList()
        }

        val changes = ArrayList<Pair<ParameterInformation, Double>>()

        for (parameter in parameters) {
            if (isProtected(parameter) || parameter.maximumValue <= parameter.minimumValue) {
                continue
            }

            val current = (currentValues[parameter.id] ?: parameter.defaultValue)
                .coerceIn(parameter.minimumValue, parameter.maximumValue)

            val value = when (val type = parameter.inferredType) {
                is ParameterType.BoolType -> randomSwitch(parameter, current, amount, random)
                is ParameterType.EnumType -> randomOption(type, current, amount, random)
                is ParameterType.IntType -> randomWhole(parameter, current, amount, random)
                is ParameterType.FloatType -> randomContinuous(parameter, current, amount, random)
            }

            changes.add(parameter to value)
        }

        return changes
    }

    private fun randomSwitch(parameter: ParameterInformation, current: Double, amount: Double, random: Random): Double {
        if (random.nextDouble() >= amount * SWITCH_CHANGE_SHARE) {
            return current
        }

        val middle = (parameter.minimumValue + parameter.maximumValue) / 2.0

        return if (current >= middle) parameter.minimumValue else parameter.maximumValue
    }

    private fun randomOption(type: ParameterType.EnumType, current: Double, amount: Double, random: Random): Double {
        if (random.nextDouble() >= amount) {
            return current
        }

        return type.options[random.nextInt(type.options.size)].value.toDouble()
    }

    private fun randomWhole(parameter: ParameterInformation, current: Double, amount: Double, random: Random): Double {
        val fraction = varyFraction(fractionOf(parameter, current, isLogarithmic = false), amount, random)
        val value = parameter.minimumValue + fraction * (parameter.maximumValue - parameter.minimumValue)

        return value.roundToInt().toDouble().coerceIn(parameter.minimumValue, parameter.maximumValue)
    }

    private fun randomContinuous(parameter: ParameterInformation, current: Double, amount: Double, random: Random): Double {
        val isLogarithmic = isLogarithmic(parameter)
        val fraction = varyFraction(fractionOf(parameter, current, isLogarithmic), amount, random)

        return valueAt(parameter, fraction, isLogarithmic)
    }

    /** Where a value is in its range, from 0 to 1. */
    private fun fractionOf(parameter: ParameterInformation, value: Double, isLogarithmic: Boolean): Double {
        val min = parameter.minimumValue
        val max = parameter.maximumValue

        return if (isLogarithmic) ln(value / min) / ln(max / min) else (value - min) / (max - min)
    }

    private fun valueAt(parameter: ParameterInformation, fraction: Double, isLogarithmic: Boolean): Double {
        val min = parameter.minimumValue
        val max = parameter.maximumValue
        val value = if (isLogarithmic) min * (max / min).pow(fraction) else min + fraction * (max - min)

        return value.coerceIn(min, max)
    }

    /** Anywhere in the range at full strength, otherwise up to [amount] of the range either side of where it is. */
    private fun varyFraction(fraction: Double, amount: Double, random: Random): Double {
        if (amount >= 1.0) {
            return random.nextDouble()
        }

        return (fraction + (random.nextDouble() * 2.0 - 1.0) * amount).coerceIn(0.0, 1.0)
    }

    private fun isLogarithmic(parameter: ParameterInformation): Boolean {
        return parameter.minimumValue > 0.0 && parameter.maximumValue / parameter.minimumValue >= LOGARITHMIC_RANGE_RATIO
    }
}
