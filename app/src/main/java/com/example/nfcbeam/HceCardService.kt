package com.example.nfcbeam

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.util.Arrays

class HceCardService : HostApduService() {
    
    companion object {
        private const val TAG = "HceCardService"
        
        // APDU指令
        private val SELECT_AID_APDU = byteArrayOf(
            0x00.toByte(), // CLA
            0xA4.toByte(), // INS
            0x04.toByte(), // P1
            0x00.toByte(), // P2
            0x07.toByte(), // Length
            0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06 // AID
        )
        
        // 成功响应
        private val SELECT_OK_SW = byteArrayOf(0x90.toByte(), 0x00.toByte())
        
        // 蓝牙信息AID
        private val BLUETOOTH_AID = byteArrayOf(
            0xF0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06
        )
    }
    
    private var bluetoothInfo: String = ""
    
    fun setBluetoothInfo(info: String) {
        this.bluetoothInfo = info
        Log.d(TAG, "设置蓝牙信息: $info")
    }
    
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        commandApdu?.let { apdu ->
            Log.d(TAG, "收到APDU指令: ${Arrays.toString(apdu)}")
            
            // 检查是否是SELECT AID指令
            if (Arrays.equals(apdu, SELECT_AID_APDU)) {
                Log.d(TAG, "SELECT AID指令匹配")
                return SELECT_OK_SW
            }
            
            // 检查是否是读取蓝牙信息的指令
            if (apdu.size >= 5 && apdu[0] == 0x00.toByte() && apdu[1] == 0xB0.toByte()) {
                Log.d(TAG, "读取蓝牙信息指令")
                if (bluetoothInfo.isNotEmpty()) {
                    val response = bluetoothInfo.toByteArray(Charsets.UTF_8)
                    val result = ByteArray(response.size + 2)
                    System.arraycopy(response, 0, result, 0, response.size)
                    result[response.size] = 0x90.toByte()
                    result[response.size + 1] = 0x00.toByte()
                    Log.d(TAG, "返回蓝牙信息: $bluetoothInfo")
                    return result
                } else {
                    Log.w(TAG, "蓝牙信息为空")
                    return byteArrayOf(0x6A.toByte(), 0x82.toByte()) // 文件未找到
                }
            }
        }
        
        // 未知指令
        Log.w(TAG, "未知APDU指令")
        return byteArrayOf(0x6D.toByte(), 0x00.toByte()) // 指令不支持
    }
    
    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE服务停用，原因: $reason")
        when (reason) {
            DEACTIVATION_LINK_LOSS -> Log.d(TAG, "NFC连接丢失")
            DEACTIVATION_DESELECTED -> Log.d(TAG, "卡片被取消选择")
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HCE服务创建")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HCE服务销毁")
    }
}
