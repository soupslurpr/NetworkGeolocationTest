package dev.soupslurpr.networkgeolocation

import WpsOuterClass
import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.soupslurpr.networkgeolocation.ui.theme.NetworkGeolocationTestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetworkGeolocationTestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val wifiManager = LocalContext.current.getSystemService(WifiManager::class.java)
                    var coords by rememberSaveable { mutableStateOf("") }
                    val appleWifiPositioningServiceConnectionCoroutineScope =
                        rememberCoroutineScope { Dispatchers.IO }

                    Column(modifier = Modifier.padding(innerPadding)) {
                        Button(
                            onClick = {
                                wifiManager.startScan()

                                // use the Log-Distance Path Loss model to
                                // get the estimated distance for each access point:
                                // 10^((-30-(-level))/30)

                                val aps = wifiManager.scanResults

                                // sort aps by highest to lowest RSSI
                                aps.sortByDescending { it.level }

                                appleWifiPositioningServiceConnectionCoroutineScope.launch {
                                    try {
                                        val url = URL("https://gs-loc.apple.com/clls/wloc")
                                        val connection = url.openConnection() as HttpsURLConnection

                                        val bssidList = listOf(
                                            WpsOuterClass.BSSID.newBuilder().setMac(aps[0].BSSID)
                                                .build(),
                                            WpsOuterClass.BSSID.newBuilder().setMac(aps[1].BSSID)
                                                .build()
                                        )

                                        val locationRequest = WpsOuterClass.Wps.newBuilder()
                                            .addAllBssids(bssidList)
                                            .setUnknown1(0)
                                            .setNumberOfResults(100)
                                            .build()

                                        try {
                                            connection.requestMethod = "POST"
                                            connection.setRequestProperty(
                                                "Content-Type",
                                                "application/x-www-form-urlencoded"
                                            )
                                            connection.doOutput = true

                                            val message = ByteArrayOutputStream()
                                            val dataOutputStream = DataOutputStream(message)
                                            locationRequest.writeDelimitedTo(dataOutputStream)
                                            dataOutputStream.close()

                                            connection.outputStream.use { outputStream ->
                                                var request = byteArrayOf()

                                                // TODO: Support other locales
                                                val locale = "en_US"
                                                val identifier = "com.apple.locationd" // TODO: test empty identifier
                                                val version = "8.4.1.12H321" // TODO: test empty version

                                                request += (1).toShort().toBeBytes()
                                                request += locale.length.toShort().toBeBytes()
                                                request += locale.toByteArray()
                                                request += identifier.length.toShort().toBeBytes()
                                                request += identifier.toByteArray()
                                                request += version.length.toShort().toBeBytes()
                                                request += version.toByteArray()
                                                request += (0).toShort().toBeBytes()
                                                request += (1).toShort().toBeBytes()
                                                request += (0).toShort().toBeBytes()
                                                request += (0).toByte()

                                                request += message.toByteArray()

                                                outputStream.write(request)
                                            }

                                            val responseCode = connection.responseCode
                                            if (responseCode == HttpsURLConnection.HTTP_OK) {
                                                connection.inputStream.use { inputStream ->
                                                    inputStream.skip(10)
                                                    val response =
                                                        WpsOuterClass.Wps.parseFrom(inputStream)

                                                    println(response)

                                                    val matchedBssids =
                                                        aps.filter { ap ->
                                                            response.bssidsList.any { responseBssid ->
                                                                (responseBssid.mac == ap.BSSID) && (responseBssid.positioningInfo.latitude != -18000000000)
                                                            }
                                                        }.associateWith { ap ->
                                                            val responseBssid =
                                                                response.bssidsList.first { it.mac == ap.BSSID }
                                                            responseBssid
                                                        }

                                                    var validCoordRange = null

                                                    for (e in matchedBssids.entries) {
                                                        val centerOfCircle = GeoCoordinate(
                                                            e.value.positioningInfo.latitude.toDouble() * (10).toDouble().pow(-8),
                                                            e.value.positioningInfo.longitude.toDouble() * (10).toDouble().pow(-8)
                                                        )

                                                        // TODO: Account for accuracy (maybe by adding accuracy level to radius)
                                                        val radius = (10).toDouble().pow((-30-(e.key.level))/30)

                                                        


                                                        if (validCoordRange == null) {
                                                            validCoordRange
                                                        }
                                                    }
                                                }
                                            } else {
                                                println("Response failed: $responseCode")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(null, "connection exception", e)
                                        } finally {
                                            connection.disconnect()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(null, "connection exception", e)
                                    }
                                }
                            }
                        ) {
                            Text(text = "Update scan results")
                        }
                        Text(text = coords)
                    }
                }
            }
        }
    }
}

fun Short.toBeBytes(): ByteArray {
    return byteArrayOf(
        ((this.toInt() shr 8) and 0xFF).toByte(),
        (this.toInt() and 0xFF).toByte()
    )
}

data class GeoCoordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90.0 and 90.0." }
        require(longitude in -180.0..180.0) { "Longitude must be between -180.0 and 180.0." }
    }
}