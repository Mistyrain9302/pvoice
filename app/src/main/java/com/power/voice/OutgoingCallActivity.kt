package com.power.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.linphone.core.*

class OutgoingCallActivity : AppCompatActivity() {
    private lateinit var core: Core
    private lateinit var audioManager: AudioManager

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            findViewById<TextView>(R.id.call_status).text = message

            when (state) {
                Call.State.Connected, Call.State.StreamsRunning -> {
                    findViewById<Button>(R.id.hang_up).isEnabled = true
                    enableSpeakerMode(true)  // Enable speaker mode when call is connected
                }
                Call.State.Released -> {
                    resetUI()
                    sendCallEndBroadcast()  // 전화 종료 후 브로드캐스트 전송
                    navigateToMainActivity()  // MainActivity로 이동
                    enableSpeakerMode(false)  // Disable speaker mode when call is ended
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

        findViewById<SeekBar>(R.id.speaker_volume).apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, progress, 0)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        findViewById<SeekBar>(R.id.mic_gain).apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    // This is a placeholder for mic gain control. Actual mic gain control might not be directly available.
                    // The AudioManager class does not provide a direct way to control mic gain.
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

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
        val username = "12567"
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
        address?.transport = TransportType.Udp // 필요한 경우 TCP, TLS 등으로 변경 가능
        accountParams.serverAddress = address

        // Account 객체 생성 및 추가
        val account = core.createAccount(accountParams)
        core.addAccount(account)

        // 기본 Account 설정
        core.defaultAccount = account
    }

    private fun makeCall(remoteSipUri: String) {
        // 기존 세션 종료
        core.currentCall?.terminate()

        val remoteAddress = Factory.instance().createAddress(remoteSipUri) ?: return
        val params = core.createCallParams(null) ?: return

        core.inviteAddressWithParams(remoteAddress, params)

        // 오디오 장치 설정
        val audioDevices = core.audioDevices
        val speakerDevice = audioDevices.find { it.type == AudioDevice.Type.Speaker }

        if (speakerDevice != null) {
            core.currentCall?.outputAudioDevice = speakerDevice
        }
    }

    private fun hangUp() {
        val call = core.currentCall ?: return
        call.terminate()
        enableSpeakerMode(false)  // Disable speaker mode when call is hung up
    }

    private fun resetUI() {
        findViewById<EditText>(R.id.remote_address).isEnabled = true
        findViewById<Button>(R.id.call).isEnabled = true
        findViewById<Button>(R.id.hang_up).isEnabled = false
    }

    override fun onDestroy() {
        core.removeListener(coreListener)
        core.stop()
        super.onDestroy()
    }

    private fun sendCallEndBroadcast() {
        val intent = Intent("CALL_ENDED")
        sendBroadcast(intent)
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun enableSpeakerMode(enable: Boolean) {
        audioManager.isSpeakerphoneOn = enable
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
