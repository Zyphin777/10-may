package com.example.offlineroutingapp.network

import java.net.DatagramPacket
import java.net.DatagramSocket

object UdpReceiver {

    fun startListening(onPeerFound: (ip: String) -> Unit, port: Int = 9999) {
        Thread {
            try {
                val socket = DatagramSocket(port)
                val buffer = ByteArray(1024)

                println("UDP Receiver started...")

                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val message = String(packet.data, 0, packet.length)
                    val senderIp = packet.address.hostAddress

                    if (message.startsWith("HELLO")) {
                        println("Found peer: $senderIp")
                        onPeerFound(senderIp)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}