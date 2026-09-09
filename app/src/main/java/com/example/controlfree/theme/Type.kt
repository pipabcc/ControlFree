package com.example.controlfree.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BrandFontFamily = FontFamily.SansSerif

private fun brandTextStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Float = 0f
) = TextStyle(
    fontFamily = BrandFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp
)

val BrandTypography = Typography(
    displayLarge = brandTextStyle(48, 56, FontWeight.Bold, -0.4f),
    displayMedium = brandTextStyle(40, 48, FontWeight.Bold, -0.3f),
    displaySmall = brandTextStyle(34, 42, FontWeight.Bold, -0.2f),
    headlineLarge = brandTextStyle(30, 38, FontWeight.Bold),
    headlineMedium = brandTextStyle(26, 34, FontWeight.Bold),
    headlineSmall = brandTextStyle(22, 30, FontWeight.SemiBold),
    titleLarge = brandTextStyle(20, 28, FontWeight.SemiBold),
    titleMedium = brandTextStyle(16, 24, FontWeight.SemiBold, 0.1f),
    titleSmall = brandTextStyle(14, 20, FontWeight.SemiBold, 0.1f),
    bodyLarge = brandTextStyle(16, 24),
    bodyMedium = brandTextStyle(14, 21),
    bodySmall = brandTextStyle(12, 18),
    labelLarge = brandTextStyle(14, 20, FontWeight.SemiBold, 0.1f),
    labelMedium = brandTextStyle(12, 17, FontWeight.Medium, 0.2f),
    labelSmall = brandTextStyle(11, 16, FontWeight.Medium, 0.25f)
)
