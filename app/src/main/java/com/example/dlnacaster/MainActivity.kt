```kotlin
package com.example.dlnacaster

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)

        scanButton.setOnClickListener {
            statusText.text = "جاري البحث عن الرسيفر في الشبكة..."
            searchDlnaDevices()
        }
    }

    private fun searchDlnaDevices() {
        thread {
            try {
                val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

                val socket = DatagramSocket()
                socket.broadcast = true
                val sendData = ssdpMessage.toByteArray()
                val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName("239.255.255.250"), 1900)
                
                socket.send(sendPacket)
                socket.soTimeout = 4000 // انتظار الاستجابة لمدة 4 ثوانٍ

                val buffer = ByteArray(1024)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                
                socket.receive(receivePacket)
                val response = String(receivePacket.data, 0, receivePacket.length)
                val remoteIp = receivePacket.address.hostAddress

                runOnUiThread {
                    statusText.text = "تم العثور على الرسيفر!\nIP: $remoteIp"
                }
                socket.close()
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "لم يتم العثور على أي رسيفر تلقائياً.\nتأكد أن الهاتف والرسيفر على نفس شبكة Wi-Fi."
                }
            }
        }
    }
}
```
