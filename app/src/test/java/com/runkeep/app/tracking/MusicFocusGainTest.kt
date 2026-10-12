package com.runkeep.app.tracking

import android.media.AudioManager
import com.runkeep.core.MusicFocusPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MusicFocusGainTest {
    @Test fun `two policies map to real Android focus gains`() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, musicFocusGain(MusicFocusPolicy.DUCK))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, musicFocusGain(MusicFocusPolicy.PAUSE))
    }
}
