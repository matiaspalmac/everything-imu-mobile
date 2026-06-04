package cl.matiaspalma.everythingimu.wear.setup

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.PickerState
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberPickerState
import kotlinx.coroutines.launch

/**
 * Universal fallback host entry: four octet wheels + a port wheel. Pure Compose,
 * no system services, no keyboard — works on de-Googled / AOSP watches where the
 * Data Layer sync is unavailable. Crown rotation drives the focused wheel.
 */
@Composable
fun HostPickerScreen(
    initialHost: String,
    initialPort: Int,
    onSave: (host: String, port: Int) -> Unit,
) {
    val seedOctets = remember(initialHost) { HostAddress.initialOctets(initialHost) }
    val seedPort = remember(initialPort) { HostAddress.initialPort(initialPort) }

    val octetStates = List(4) { i ->
        rememberPickerState(
            initialNumberOfOptions = 256,
            initiallySelectedOption = seedOctets[i],
        )
    }
    // Option index 0 maps to port 1, so seed/read are offset by MIN_PORT.
    val portState = rememberPickerState(
        initialNumberOfOptions = HostAddress.MAX_PORT - HostAddress.MIN_PORT + 1,
        initiallySelectedOption = seedPort - HostAddress.MIN_PORT,
    )

    val focusRequesters = remember { List(5) { FocusRequester() } }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Server IP", style = MaterialTheme.typography.caption1)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            octetStates.forEachIndexed { i, state ->
                OctetWheel(
                    state = state,
                    focusRequester = focusRequesters[i],
                    modifier = Modifier.weight(1f),
                    label = "octet ${i + 1}",
                )
                if (i < octetStates.lastIndex) {
                    Text(".", style = MaterialTheme.typography.title3)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Port ", style = MaterialTheme.typography.caption1)
            Box(modifier = Modifier.width(64.dp).height(60.dp)) {
                Picker(
                    state = portState,
                    contentDescription = "port",
                    modifier = Modifier
                        .focusRequester(focusRequesters[4])
                        .focusable(),
                    onSelected = { scope.launch { focusRequesters[4].requestFocus() } },
                ) { optionIndex ->
                    Text((optionIndex + HostAddress.MIN_PORT).toString())
                }
            }
        }

        Button(onClick = {
            val octets = IntArray(4) { octetStates[it].selectedOption }
            val host = HostAddress.format(octets)
            val port = HostAddress.coercePort(portState.selectedOption + HostAddress.MIN_PORT)
            onSave(host, port)
        }) { Text("Save") }
    }
}

@Composable
private fun OctetWheel(
    state: PickerState,
    focusRequester: FocusRequester,
    modifier: Modifier,
    label: String,
) {
    val scope = rememberCoroutineScope()
    Picker(
        state = state,
        contentDescription = label,
        modifier = modifier
            .height(60.dp)
            .focusRequester(focusRequester)
            .focusable(),
        onSelected = { scope.launch { focusRequester.requestFocus() } },
    ) { optionIndex ->
        Text(optionIndex.toString())
    }
}
