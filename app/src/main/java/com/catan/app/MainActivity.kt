package com.catan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.catan.app.net.GameClient
import com.catan.app.ui.ConnectScreen
import com.catan.app.ui.GameScreen
import com.catan.app.ui.LobbyScreen

/** Holds the connection across configuration changes so a rotation does not drop the game. */
class CatanViewModel : ViewModel() {
    val client = GameClient()

    override fun onCleared() {
        super.onCleared()
        client.leave()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CatanApp()
            }
        }
    }
}

@Composable
fun CatanApp(model: CatanViewModel = viewModel()) {
    val client = remember { model.client }

    val connection by client.connection.collectAsState()
    val lobby by client.lobby.collectAsState()
    val view by client.view.collectAsState()
    val notice by client.notice.collectAsState()

    val currentView = view
    val currentLobby = lobby

    when {
        currentView != null -> GameScreen(
            view = currentView,
            notice = notice,
            onAct = { client.act(it) },
            onDismissNotice = { client.clearNotice() },
        )

        currentLobby != null -> LobbyScreen(
            lobby = currentLobby,
            onStart = { client.startGame() },
            onLeave = { client.leave() },
        )

        else -> ConnectScreen(
            connection = connection,
            onHost = { server, name -> client.host(server, name) },
            onJoin = { server, code, name -> client.join(server, code, name) },
        )
    }
}
