package com.caglar.sivilsavunma

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = StudyRepository(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF4554C7),
                    secondary = Color(0xFF30A982),
                    tertiary = Color(0xFFE14E73),
                    background = Color(0xFFF6F7FB),
                    surface = Color.White
                )
            ) {
                SivilSavunmaApp(repository)
            }
        }
    }
}
