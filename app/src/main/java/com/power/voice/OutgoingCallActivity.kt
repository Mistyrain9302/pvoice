package com.power.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.linphone.core.*

class OutgoingCallActivity : AppCompatActivity() {
    private lateinit var core: Core
    private lateinit var audioManager: AudioManager
    private lateinit var registerLayout: LinearLayout

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            findViewById<TextView>(R.id.call_status).text = message

            when (state) {
                Call.State.Connected, Call.State.StreamsRunning -> {
                    findViewById<Button>(R.id.hang_up).isEnabled = true
                    enableSpeakerMode(true)  // Enable speaker mode when call is connected
                    enableAEC()  // Enable AEC when call is connected
                }
                Call.State.Released -> {
                    resetUI()
                    sendCallEndBroadcast()  // 전화 종료 후 브로드캐스트 전송
                    navigateToMainActivity()  // MainActivity로 이동
                    enableSpeakerMode(false)  // Disable speaker mode when call is ended
                }
                Call.State.OutgoingInit -> {
                    // First state an outgoing call will go through
                }
                Call.State.OutgoingProgress -> {
                    // Right after outgoing init
                }
                Call.State.OutgoingRinging -> {
                    // This state will be reached upon reception of the 180 RINGING
                }
                Call.State.Error -> {
                    // Handle call error
                    resetUI()
                    navigateToMainActivity()
                }
                else -> { /* Do nothing for other states */ }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.outgoing_call_activity)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initCore()

        findViewById<Button>(R.id.call).setOnClickListener {
            val remoteSipUri = findViewById<EditText>(R.id.remote_address).text.toString()
            makeCall(remoteSipUri)
        }

        findViewById<Button>(R.id.hang_up).setOnClickListener {
            hangUp()
        }

        findViewById<Button>(R.id.volume_up).setOnClickListener {
            adjustVolume(AudioManager.ADJUST_RAISE)
        }

        findViewById<Button>(R.id.volume_down).setOnClickListener {
            adjustVolume(AudioManager.ADJUST_LOWER)
        }

        registerLayout = findViewById(R.id.register_layout)
        findViewById<Button>(R.id.toggle_register_layout).setOnClickListener {
            toggleRegisterLayout()
        }

        updateVolumeDisplay()
        resetUI()

        // Check if started with a SIP URI to call
        intent.getStringExtra("REMOTE_SIP_URI")?.let {
            makeCall(it)
        }
    }

    private fun initCore() {
        val factory = Factory.instance()
        factory.setDebugMode(true, "Linphone Debug")
        core = factory.createCore(null, null, this)
        core.addListener(coreListener)
        core.start()

        // SIP 계정 설정
        val username = "12789"
        val password = "1234"
        val domain = "192.168.10.112"

        // AuthInfo 설정
        val authInfo = Factory.instance().createAuthInfo(username, null, password, null, null, domain, null)
        core.addAuthInfo(authInfo)

        // AccountParams 설정
        val accountParams = core.createAccountParams()
        val identity = Factory.instance().createAddress("sip:$username@$domain")
        accountParams.identityAddress = identity

        val address = Factory.instance().createAddress("sip:$domain")
        address?.transport = TransportType.Udp
        accountParams.serverAddress = address

        // 계정 추가
        val account = core.createAccount(accountParams)
        core.addAccount(account)

        // 기본 Account 설정
        core.defaultAccount = account

        // AEC 설정
        core.config.setString("sound", "ec_filter", "MSWebRTCAEC")
        core.config.setBool("sound", "echocancellation", true)
        core.config.setInt("sound", "ec_delay", 100)

        // 소프트웨어 AEC 설정
        core.mediastreamerFactory.setDeviceInfo(android.os.Build.MANUFACTURER, android.os.Build.MODEL, android.os.Build.DEVICE, org.linphone.mediastream.Factory.DEVICE_HAS_BUILTIN_AEC_CRAPPY, 0, 0)
        core.reloadSoundDevices()
    }


    private fun makeCall(remoteSipUri: String) {
        // Linphone Core가 초기화되었는지 확인
        if (core.callsNb == 0) {
            initCore()
        }

        // 기존 세션 종료
        core.currentCall?.terminate()

        val remoteAddress = Factory.instance().createAddress(remoteSipUri) ?: return
        val params = core.createCallParams(null) ?: return

        core.inviteAddressWithParams(remoteAddress, params)

        // 오디오 장치 설정
        val audioDevices = core.audioDevices
        val speakerDevice = audioDevices.find { it.type == AudioDevice.Type.Speaker }

        if (speakerDevice != null) {
            core.outputAudioDevice = speakerDevice
        }
    }

    private fun hangUp() {
        core.currentCall?.terminate()
    }

    private fun enableSpeakerMode(enable: Boolean) {
        audioManager.isSpeakerphoneOn = enable
    }

    private fun enableAEC() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    private fun resetUI() {
        findViewById<EditText>(R.id.remote_address).isEnabled = true
        findViewById<Button>(R.id.call).isEnabled = true
        findViewById<Button>(R.id.hang_up).isEnabled = false
    }

    private fun updateVolumeDisplay() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        findViewById<TextView>(R.id.current_volume).text = currentVolume.toString()
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, 0)
        updateVolumeDisplay()
    }

    private fun sendCallEndBroadcast() {
        val broadcastIntent = Intent("com.power.voice.CALL_ENDED")
        sendBroadcast(broadcastIntent)
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }

    private fun toggleRegisterLayout() {
        if (registerLayout.visibility == View.VISIBLE) {
            registerLayout.visibility = View.GONE
            findViewById<Button>(R.id.toggle_register_layout).text = "Show Registration Options"
        } else {
            registerLayout.visibility = View.VISIBLE
            findViewById<Button>(R.id.toggle_register_layout).text = "Hide Registration Options"
        }
    }

    override fun onDestroy() {
        core.removeListener(coreListener)
        core.stop()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, remoteSipUri: String) {
            val intent = Intent(context, OutgoingCallActivity::class.java).apply {
                putExtra("REMOTE_SIP_URI", remoteSipUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 필요에 따라 추가
            }
            context.startActivity(intent)
        }
    }
}
