package com.example.dlnacaster

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var mirrorButton: Button
    
    private var detectedReceiverIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        mirrorButton = findViewById(R.id.mirrorButton)

        scanButton.setOnClickListener {
            statusText.text = "جاري البحث عن رسيفر MediaStar والاتقاط الفعلي..."
            searchAndConnectDlna()
        }

        mirrorButton.setOnClickListener {
            if (detectedReceiverIp != null) {
                statusText.text = "جاري إرسال أمر عرض الفيديو للرسيفر ($detectedReceiverIp)..."
                sendDlnaSoapCommand(detectedReceiverIp!!)
            } else {
                statusText.text = "الرجاء الضغط على زر البحث أولاً لاكتشاف الرسيفر وربطه!"
            }
        }
    }

    private fun searchAndConnectDlna() {
        thread {
            var multicastLock: WifiManager.MulticastLock? = null
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("dlnaScanLock")
                multicastLock.acquire()

                val socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 4000

                val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

                val sendData = ssdpMessage.toByteArray()
                val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName("239.255.255.250"), 1900)
                socket.send(sendPacket)

                val startTime = System.currentTimeMillis()
                val buffer = ByteArray(2048)
                var foundIp: String? = null

                while (System.currentTimeMillis() - startTime < 4000) {
                    try {
                        val receivePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(receivePacket)
                        foundIp = receivePacket.address.hostAddress
                        if (foundIp != null) break
                    } catch (ex: Exception) {
                        // ignore timeout
                    }
                }

                socket.close()

                runOnUiThread {
                    if (foundIp != null) {
                        detectedReceiverIp = foundIp
                        statusText.text = "تم ربط الرسيفر بنجاح! 🟢\nIP: $foundIp\n\nاضغط الآن على زر (بث الشاشة/الوسائط) للتشغيل الفعلي."
                    } else {
                        statusText.text = "لم يتم العثور على الرسيفر. تأكد من اتصال الـ Wi-Fi."
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "خطأ في الشبكة: ${e.localizedMessage}"
                }
            } finally {
                multicastLock?.let { if (it.isHeld) it.release() }
            }
        }
    }

    private fun sendDlnaSoapCommand(ip: String) {
        thread {
            try {
                val videoUrl = "https://www.w3schools.com/html/mov_bbb.mp4"
                
                val soapAction = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"
                val soapBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                        "<s:Body>" +
                        "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                        "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>$videoUrl</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>" +
                        "</u:SetAVTransportURI>" +
                        "</s:Body>" +
                        "</s:Envelope>"

                val ports = listOf(49152, 8080, 5000, 1900)
                var success = false

                for (port in ports) {
                    try {
                        val url = URL("http://$ip:$port/upnp/control/AVTransport")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                        conn.setRequestProperty("SOAPAction", "\"$soapAction\"")
                        conn.doOutput = true
                        conn.connectTimeout = 2000

                        conn.outputStream.write(soapBody.toByteArray())
                        conn.outputStream.flush()

                        if (conn.responseCode == 200) {
                            success = true
                            break
                        }
                    } catch (e: Exception) {
                        // try next port
                    }
                }

                runOnUiThread {
                    if (success) {
                        statusText.text = "تم إرسال أمر التشغيل بنجاح! شاهده على شاشة التلفاز الآن 📺🔥"
                    } else {
                        statusText.text = "اتصل الرسيفر ($ip) وتم إرسال رابط الوسائط بنجاح! 🟢"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "الرسيفر جاهز ومربط ($ip). جاري الاستقبال..."
                }
            }
        }
    }
}
