package com.github.springeye.diskraidpower.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.springeye.diskraidpower.db.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onAction: (HomeAction) -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("设备列表") }, actions = {
            IconButton(onClick = {

            }) {
                Icon(Icons.Default.Add, "Add Device")
            }
        })
    }) {
        if (state.devices.isEmpty()) {
            Box(modifier = Modifier.padding(it).fillMaxSize(), contentAlignment = Alignment.Center){
                Text("没有设备，点击右上角添加设备")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(it).fillMaxSize()
            ) {
                items(state.devices) { device ->
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = device.name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Preview(name = "Home")
private fun HomeScreenPreview() {
    HomeScreen(
        state = HomeState(
            devices = listOf(
                Device(
                    id = 1,
                    name = "Device 1",
                ),
            )
        ),
        onAction = {}
    )
}

