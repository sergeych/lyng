package net.sergeych.lying

//fun buildDoubleFromParts(
//    integerPart: Long,
//    decimalPart: Long,
//    exponent: Int
//): Double {
//    // Handle zero decimal case efficiently
//    val numDecimalDigits = if (decimalPart == 0L) 0 else decimalPart.toString().length
//
//    // Calculate decimal multiplier (10^-digits)
//    val decimalMultiplier = 10.0.pow(-numDecimalDigits)
//
//    // Combine integer and decimal parts
//    val baseValue = integerPart.toDouble() + decimalPart.toDouble() * decimalMultiplier
//
//    // Apply exponent
//    return baseValue * 10.0.pow(exponent)
//}