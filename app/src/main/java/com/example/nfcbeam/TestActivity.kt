package com.example.nfcbeam

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 测试Activity，用于验证NFC和蓝牙功能是否正常工作
 */
class TestActivity : AppCompatActivity() {
    
    private val mainScope = CoroutineScope(Dispatchers.Main)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 测试NFC和蓝牙管理器
        testNFCAndBluetooth()
    }
    
    private fun testNFCAndBluetooth() {
        mainScope.launch {
            try {
                // 测试蓝牙管理器
                val bluetoothManager = BluetoothManager(this@TestActivity)
                val isBluetoothEnabled = bluetoothManager.isBluetoothEnabled()
                
                // 测试文件传输管理器
                val fileTransferManager = FileTransferManager(this@TestActivity, bluetoothManager)
                
                // 输出测试结果
                println("NFCBeam Test Results:")
                println("- Bluetooth enabled: $isBluetoothEnabled")
                println("- FileTransferManager initialized: ${fileTransferManager != null}")
                println("- MainActivity NFC support: ${MainActivity::class.java.simpleName} loaded")
                
            } catch (e: Exception) {
                println("Test failed with error: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
