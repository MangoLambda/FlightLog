package com.example.flightlog.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val GondolaLiftIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "GondolaLift",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
        ) {
            moveTo(3f, 3f)
            horizontalLineTo(21f)
            verticalLineTo(5f)
            horizontalLineTo(13f)
            verticalLineTo(7.1f)
            curveTo(16.4f, 7.6f, 18.5f, 10.3f, 18.5f, 13.7f)
            verticalLineTo(17f)
            curveTo(18.5f, 18.7f, 17.2f, 20f, 15.5f, 20f)
            horizontalLineTo(8.5f)
            curveTo(6.8f, 20f, 5.5f, 18.7f, 5.5f, 17f)
            verticalLineTo(13.7f)
            curveTo(5.5f, 10.3f, 7.6f, 7.6f, 11f, 7.1f)
            verticalLineTo(5f)
            horizontalLineTo(3f)
            close()
            moveTo(8f, 13f)
            horizontalLineTo(11f)
            verticalLineTo(10f)
            curveTo(9.5f, 10.3f, 8.4f, 11.4f, 8f, 13f)
            close()
            moveTo(13f, 10f)
            verticalLineTo(13f)
            horizontalLineTo(16f)
            curveTo(15.6f, 11.4f, 14.5f, 10.3f, 13f, 10f)
            close()
            moveTo(8f, 15f)
            verticalLineTo(17f)
            curveTo(8f, 17.6f, 8.4f, 18f, 9f, 18f)
            horizontalLineTo(15f)
            curveTo(15.6f, 18f, 16f, 17.6f, 16f, 17f)
            verticalLineTo(15f)
            close()
        }
    }.build()
}
