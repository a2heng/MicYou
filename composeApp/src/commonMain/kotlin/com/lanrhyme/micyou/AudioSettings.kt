package com.lanrhyme.micyou

enum class SampleRate(val value: Int) {
    Rate16000(16000),
    Rate44100(44100),
    Rate48000(48000)
}

enum class ChannelCount(val value: Int, val label: String) {
    Mono(1, "Mono"),
    Stereo(2, "Stereo")
}

enum class AudioFormat(val value: Int, val label: String) {
    PCM_8BIT(3, "8-bit PCM"), // AudioFormat.ENCODING_PCM_8BIT = 3
    PCM_16BIT(2, "16-bit PCM"), // AudioFormat.ENCODING_PCM_16BIT = 2
    PCM_FLOAT(4, "32-bit Float") // AudioFormat.ENCODING_PCM_FLOAT = 4
}

