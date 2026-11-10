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

    private lateinit var statusTextView: TextView
    private lateinit var scanButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // تصميم الواجهة البرمجية البسيطة
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        scanButton = Button(this).apply {
            text = "البحث عن الرسيفر (Scan DLNA DMR)"
        }

        statusTextView = TextView(this).apply {
            text = "حالة الاتصال: جاهز للبحث..."
            textSize = 16f
            setPadding(0, 30, 0, 0)
        }

        layout.addView(scanButton)
        layout.addView(statusTextView)
        setContentView(layout)

        scanButton.setOnClickListener {
            statusTextView.text = "جاري البحث عن أجهزة الرسيفر في الشبكة..."
            discoverDLNADevices()
        }
    }

    // البحث عن الرسيفر باستخدام بروتوكول SSDP عبر الـ Multicast
    private fun discoverDLNADevices() {
        thread {
            try {
                val ssdpQuery = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

                val socket = DatagramSocket()
                socket.soTimeout = 4000
                val group = InetAddress.getByName("239.255.255.250")

                val sendPacket = DatagramPacket(
                    ssdpQuery.toByteArray(),
                    ssdpQuery.length,
                    group,
                    1900
                )
                socket.send(sendPacket)

                val buffer = ByteArray(1024)
                val receivePacket = DatagramPacket(buffer, buffer.size)

                socket.receive(receivePacket)
                val response = String(receivePacket.data, 0, receivePacket.length)

                runOnUiThread {
                    if (response.contains("MediaRenderer")) {
                        statusTextView.text = "تم العثور على الرسيفر!\nIP الجهاز: ${receivePacket.address.hostAddress}"
                    } else {
                        statusTextView.text = "تم استلام رد ولكن لم يتم التعرف على الرسيفر."
                    }
                }
                socket.close()

            } catch (e: Exception) {
                runOnUiThread {
                    statusTextView.text = "لم يتم العثور على أي رسيفر، تأكد أن الهواتف والرسيفر متصلان بنفس شبكة الـ Wi-Fi."
                }
            }
        }
    }
}
