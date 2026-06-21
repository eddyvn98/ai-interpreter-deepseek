package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.ui.InterpreterApp
import com.example.ui.InterpreterViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  private val viewModel: InterpreterViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val isDarkMode by viewModel.isDarkMode.collectAsState()
      MyApplicationTheme(darkTheme = isDarkMode) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          InterpreterApp(viewModel = viewModel)
        }
      }
    }
  }
}
