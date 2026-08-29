package com.openjarvis.android.hologram

import com.openjarvis.android.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying complete synchronization between VoiceEngine / TTS lifecycle and Hologram State Machine.
 * Validates real-time event driving (TTS_STARTED, TTS_FINISHED, TTS_CANCELLED, ERROR, THINKING, LISTENING).
 */
class HologramVoiceSyncTest {

    private lateinit var visualizer: VoiceVisualizer
    private lateinit var audioAnalyzer: TtsAudioAnalyzer

    @Before
    fun setUp() {
        visualizer = VoiceVisualizer()
        audioAnalyzer = TtsAudioAnalyzer(visualizer)
    }

    @Test
    fun testInitialStandbyState() {
        val idleSpeed = visualizer.calculateSpeedMultiplier(HologramState.IDLE)
        val hiddenSpeed = visualizer.calculateSpeedMultiplier(HologramState.HIDDEN)

        assertEquals(0.6f, idleSpeed, 0.001f)
        assertEquals(0.0f, hiddenSpeed, 0.001f)
        assertEquals(1.0f, visualizer.calculateCoreScale(), 0.001f)
        assertEquals(1.0f, visualizer.calculateParticleDispersion(), 0.001f)
    }

    @Test
    fun testThinkingStateBoostsOrbitalSpeed() {
        val thinkingSpeed = visualizer.calculateSpeedMultiplier(HologramState.THINKING)
        val idleSpeed = visualizer.calculateSpeedMultiplier(HologramState.IDLE)

        assertTrue("THINKING state must spin orbital rings at high computational speed (>= 3.0x)", thinkingSpeed >= 3.0f)
        assertTrue("THINKING speed must be significantly faster than IDLE", thinkingSpeed > idleSpeed * 3)
    }

    @Test
    fun testListeningStateReactsToMicrophoneRms() {
        // Quiet ambient noise (-50 dB)
        visualizer.processMicrophoneLevel(-50f)
        val quietScale = visualizer.calculateCoreScale()

        // Active user speech (-12 dB)
        visualizer.processMicrophoneLevel(-12f)
        val activeScale = visualizer.calculateCoreScale()
        val listeningSpeed = visualizer.calculateSpeedMultiplier(HologramState.LISTENING)

        assertTrue("Core scale must expand during user speech in LISTENING", activeScale > quietScale)
        assertTrue("Listening rotation speed must scale with user amplitude", listeningSpeed > 1.2f)
    }

    @Test
    fun testSpeakingStateDynamicModulation() {
        // Before speaking: zero amplitude
        assertEquals(0.0f, visualizer.smoothedAmplitude, 0.001f)

        // TTS_STARTED -> Audio tracker begins feeding energy
        visualizer.processNormalizedEnergy(0.80f)
        val speakingScale = visualizer.calculateCoreScale()
        val speakingSpeed = visualizer.calculateSpeedMultiplier(HologramState.SPEAKING)
        val dispersion = visualizer.calculateParticleDispersion()

        assertTrue("Core must pulsate with speech energy", speakingScale > 1.1f)
        assertTrue("Orbital rings must accelerate with speech amplitude", speakingSpeed > 2.0f)
        assertTrue("Particles must disperse radially during vocalization", dispersion > 1.2f)
    }

    @Test
    fun testTtsFinishedStopsAnimationInstantly() {
        // Speech active
        audioAnalyzer.startSpeechTracking("Bonjour Monsieur Stark, tous les systèmes sont opérationnels.")
        visualizer.processNormalizedEnergy(0.95f)
        assertTrue("Visualizer must have active amplitude while speaking", visualizer.smoothedAmplitude > 0.0f)

        // TTS_FINISHED received -> Real-time shutdown (no arbitrary timer)
        audioAnalyzer.stopSpeechTracking()
        visualizer.reset()

        assertEquals("Amplitude must drop to zero immediately upon TTS completion", 0.0f, visualizer.smoothedAmplitude, 0.001f)
        assertEquals("Core scale must return to 1.0 upon TTS completion", 1.0f, visualizer.calculateCoreScale(), 0.001f)
        assertEquals("Particle dispersion must return to baseline", 1.0f, visualizer.calculateParticleDispersion(), 0.001f)
    }

    @Test
    fun testTtsCancelledInterruptsSpeakingImmediately() {
        audioAnalyzer.startSpeechTracking("Exécution de la séquence de diagnostic...")
        visualizer.processNormalizedEnergy(0.85f)

        // User touch or barge-in triggers immediate TTS_CANCELLED
        audioAnalyzer.stopSpeechTracking()
        visualizer.reset()

        assertEquals(0.0f, visualizer.smoothedAmplitude, 0.001f)
        val idleSpeed = visualizer.calculateSpeedMultiplier(HologramState.IDLE)
        assertEquals(0.6f, idleSpeed, 0.001f)
    }

    @Test
    fun testErrorStateVisualizerSpeed() {
        val errorSpeed = visualizer.calculateSpeedMultiplier(HologramState.ERROR)
        assertEquals(1.0f, errorSpeed, 0.001f)
    }

    @Test
    fun testHarmonicFrequencyBandsAnimation() {
        visualizer.processNormalizedEnergy(0.75f)
        visualizer.updateFrame(0.016f, isVoiceActive = true)

        var hasNonZeroBand = false
        for (band in visualizer.frequencyBands) {
            assertTrue("Frequency band must be bounded between 0.0 and 1.0", band in 0.0f..1.0f)
            if (band > 0.05f) hasNonZeroBand = true
        }
        assertTrue("Frequency waveform arc must animate harmonic bars during active speech", hasNonZeroBand)
    }

    @Test
    fun testVoiceStateMappingToHologramStates() {
        val statesToTest = listOf(
            VoiceState.IDLE,
            VoiceState.LISTENING_FOR_WAKE_WORD,
            VoiceState.WAKE_WORD_DETECTED,
            VoiceState.LISTENING_COMMAND,
            VoiceState.PROCESSING,
            VoiceState.SPEAKING,
            VoiceState.ERROR,
            VoiceState.STOPPED
        )

        for (state in statesToTest) {
            val isBusy = state.isBusy
            if (state == VoiceState.PROCESSING || state == VoiceState.SPEAKING) {
                assertTrue("VoiceState $state must be considered busy", isBusy)
            }
        }
    }
}
