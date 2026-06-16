package me.earzuchan.chatdrama.client

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() = Column(Modifier.background(Color.White).safeContentPadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    var showContent by remember { mutableStateOf(false) }
    val greeting = remember { Greeting.greet }

    BasicText("Click me!", Modifier.clickable { showContent = !showContent })

    AnimatedVisibility(showContent) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { BasicText("Hello: $greeting") } }
}