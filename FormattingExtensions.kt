package com.example

/**
 * Extension functions for common formatting operations.
 */

fun Double.formatCurrency(): String = String.format("$%.2f", this)

fun Double.formatPercent(): String = String.format("%.2f%%", this)

fun Double.formatOdds(): String = String.format("%.2f", this)

fun Int.formatRounds(): String = this.toString()

fun <T> List<T>.combineIndices(indices: Set<Int>): List<T> = 
    this.filterIndexed { index, _ -> indices.contains(index) }

fun String.formatDateString(): String = this // Already in YYYY-MM-DD format

fun Boolean.toMoneyColor() = this // true = positive (green), false = negative (red)
