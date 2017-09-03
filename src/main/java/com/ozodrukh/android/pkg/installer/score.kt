package com.ozodrukh.android.pkg.installer

/**
 * This is a port of Joshaven Potter's string_score.js: String Scoring Algorithm 0.1.22
 * <p/>
 * http://joshaven.com/string_score
 * https://github.com/joshaven/string_score
 * <p/>
 * Ported By: Francisco Trigo Martinez
 * Date: 3 december 2016
 */

/**
 * This is a port of Joshaven Potter's string_score.js: String Scoring Algorithm 0.1.22
 * <p/>
 * http://joshaven.com/string_score
 * https://github.com/joshaven/string_score
 * <p/>
 * score is a Kotlin String extension function. It returns a Double value that represents the scoring value.
 * @property word is the String that contains the
 * @property fuzziness allows mismatched info to still score the string (optional, default value 0.0)
 */
fun String.score(word: String, fuzziness: Double = 0.0): Double = when (word) {
// If the string is equal to the word, perfect match.
    this -> 1.0
// if it's not a perfect match and is empty return 0
    "" -> 0.0
// Let's computeScore the result
    else -> computeScore(this, word, fuzziness)
}


private fun computeScore(string: String, word: String, fuzziness: Double): Double {

    val lString = string.toLowerCase()
    val lWord = word.toLowerCase()

    var idxOf: Int
    var startAt = 0
    var charScore: Double
    var runningScore: Double = 0.0
    val fuzzyFactor: Double = 1 - fuzziness // Cache fuzzyFactor for speed increase
    var fuzzies: Double = 1.0

    for (index in word.indices) {

        // Find next first case-insensitive match of a character.
        idxOf = lString.indexOf(lWord[index], startAt)

        if (idxOf == -1) {
            // if there is fuzziness > evaluate the fuzzies values.
            // If there is not fuzzines means that the character does not exists, so we do not have a match.
            if (fuzziness > 0.0) fuzzies += fuzzyFactor else return 0.0
        } else {
            charScore = if (startAt == idxOf) 0.7 else if (idxOf.minus(1) >= 0 && string[idxOf - 1].toString() == " ") 0.9 else 0.1

            if (string[idxOf] == word[index]) charScore += 0.1
            runningScore += charScore
            startAt = idxOf + 1
        }
    }

    // Reduce penalty for longer strings.
    var finalScore = 0.5 * (runningScore / string.length + runningScore / word.length) / fuzzies
    finalScore += if ((word.toLowerCase()[0] == string.toLowerCase()[0]) && (finalScore < 0.85)) 0.15 else 0.0
    return finalScore
}