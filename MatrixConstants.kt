package com.example

/**
 * Centralized constants for matrix calculations and UI formatting.
 */
object MatrixConstants {
    // Matrix calculation constants
    const val STAKE_PERCENTAGE = 0.0278
    const val LOSS_PERCENTAGE = 0.3
    const val PROFIT_MULTIPLIER = 2.25
    const val MATRIX_PAIR_COUNT = 6
    const val MATCH_COUNT = 4
    const val NO_COUNT_MIN = 0
    const val NO_COUNT_MAX = 4
    
    // Validation thresholds
    const val MINIMUM_ODDS = 1.0
    const val STAKE_TOLERANCE = 0.01
    const val MINIMUM_STAKE = 0.01
    
    // Matrix equation indices
    const val MATRIX_SIZE = 5
}

object MonthNames {
    val months = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
    
    fun getMonth(index: Int): String = months.getOrNull(index) ?: ""
}

object WeekDays {
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
}
