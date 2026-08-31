package com.pihub.android

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BillingCard = Color(0xFF1D1D20)
private val BillingPink = Color(0xFFE62D75)
private val BillingGreen = Color(0xFF31D66B)

@Composable
fun PiHubPlansScreen() {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    val billing = remember { PiHubBillingManager(context) }
    val plans by billing.plans.collectAsState()
    val active by billing.activeProductId.collectAsState()
    val message by billing.message.collectAsState()

    DisposableEffect(billing) {
        onDispose { billing.close() }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("PiHub Plans", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Subscribe securely through Google Play. Prices and currency are shown by Google Play for your account.",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }

        item {
            PlanFeatureList()
        }

        if (plans.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = BillingCard)) {
                    Text(
                        "Loading plans from Google Play…\nMake sure PiHub's subscription products are configured in Play Console.",
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(plans, key = { it.productId }) { plan ->
            val isActive = active == plan.productId
            Card(colors = CardDefaults.cardColors(containerColor = BillingCard)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, tint = BillingPink)
                        Spacer(Modifier.padding(4.dp))
                        Text(plan.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(plan.price, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(plan.description, color = Color.Gray, fontSize = 12.sp)
                    Button(
                        onClick = { billing.buy(activity, plan.productId) },
                        enabled = !isActive,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isActive) Color(0xFF173D24) else BillingPink)
                    ) {
                        Text(if (isActive) "Current Plan" else "Subscribe with Google Play")
                    }
                    if (isActive) Text("Active through Google Play", color = BillingGreen, fontSize = 12.sp)
                }
            }
        }

        if (message.isNotBlank()) {
            item { Text(message, color = Color.Gray, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun PlanFeatureList() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151518))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Included with paid plans", fontWeight = FontWeight.Bold)
            listOf(
                "Full Raspberry Pi management over SSH",
                "Live system monitoring and charts",
                "Processes, services, files and terminal",
                "GPIO and maintenance tools"
            ).forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = BillingGreen)
                    Spacer(Modifier.padding(4.dp))
                    Text(it, fontSize = 12.sp)
                }
            }
        }
    }
}
