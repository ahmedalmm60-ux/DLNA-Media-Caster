package com.example.dlnacaster

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
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
            statusText.text = "جاري البحث عن رسيفر MediaStar وكشف منافذه..."
            searchAndConnectDlna()
        }

        mirrorButton.setOnClickListener {
            if (detectedReceiverIp != null) {
                statusText.text = "جاري جلب وصف الرسيفر وبث الفيديو ($detectedReceiverIp)..."
                discoverAndStreamToReceiver(detectedReceiverIp!!)
            } else {
                statusText.text = "الرجاء الضغط على زر البحث أولاً لاكتشاف الرسيفر!"
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
                        statusText.text = "تم اكتشاف الرسيفر بنجاح! 🟢\nIP: $foundIp\n\nاضغط الآن على زر بدء البث."
                    } else {
                        statusText.text = "لم يتم العثور على الرسيفر. تأكد من اتصال الواي فاي."
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

    private fun discoverAndStreamToReceiver(ip: String) {
        thread {
            try {
                val ports = listOf(49152, 8080, 5000, 1900, 80, 49153, 49154)
                var controlUrlFound: String? = null

                for (port in ports) {
                    try {
                        val descUrls = listOf(
                            "http://$ip:$port/description.xml",
                            "http://$ip:$port/buffet/descs/device_description.xml",
                            "http://$ip:$port/upnp/desc.xml"
                        )
                        
                        for (descUrl in descUrls) {
                            try {
                                val url = URL(descUrl)
                                val conn = url.openConnection() as HttpURLConnection
                                conn.connectTimeout = 1000
                                conn.readTimeout = 1000
                                if (conn.responseCode == 200) {
                                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                                    val response = StringBuilder()
                                    var line: String?
                                    while (reader.readLine().also { line = it } != null) {
                                        response.append(line)
                                    }
                                    reader.close()
                                    
                                    val xml = response.toString()
                                    if (xml.contains("AVTransport")) {
                                        controlUrlFound = "http://$ip:$port/upnp/control/AVTransport"
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                // متابعة الفحص
                            }
                        }
                        if (controlUrlFound != null) break
                    } catch (e: Exception) {
                        // متابعة المنفذ التالي
                    }
                }

                val finalControlUrl = controlUrlFound ?: "http://$ip:49152/upnp/control/AVTransport"

                val videoUrl = "https://www.w3schools.com/html/mov_bbb.mp4"
                val soapActionSet = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"
                
                val setBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                        "<s:Body>" +
                        "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                        "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>$videoUrl</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>" +
                        "</u:SetAVTransportURI>" +
                        "</s:Body>" +
                        "</s:Envelope>"

                val url = URL(finalControlUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPAction", "\"$soapActionSet\"")
                conn.doOutput = true
                conn.connectTimeout = 2000

                val setBytes = setBody.toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Length", setBytes.size.toString())
                conn.outputStream.write(setBytes)
                conn.outputStream.flush()

                val responseCode = conn.responseCode
                
                if (responseCode == 200 || responseCode == 500) {
                    sendPlayAction(finalControlUrl)
                    runOnUiThread {
                        statusText.text = "🎉 تم بث الفيديو بنجاح إلى الرسيفر! استمتع بالشاشة 📺"
                    }
                } else {
                    runOnUiThread {
                        statusText.text = "⚠️ استجاب الرسيفر ولكن برمز الحالة: $responseCode"
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "❌ فشل الاتصال بخدمة الرسيفر الداخلية."
                }
            }
        }
    }

    private fun sendPlayAction(controlUrl: String) {
        try {
            val soapActionPlay = "urn:schemas-upnp-org:service:AVTransport:1#Play"
            val playBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>" +
                    "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                    "<InstanceID>0</InstanceID>" +
                    "<Speed>1</Speed>" +
                    "</u:Play>" +
                    "</s:Body>" +
                    "</s:Envelope>"

            val url = URL(controlUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"$soapActionPlay\"")
            conn.doOutput = true
            conn.connectTimeout = 2000

            val playBytes = playBody.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", playBytes.size.toString())
            conn.outputStream.write(playBytes)
            conn.outputStream.flush()
            conn.responseCode
        } catch (e: Exception) {
            // ignore
        }
    }
}
