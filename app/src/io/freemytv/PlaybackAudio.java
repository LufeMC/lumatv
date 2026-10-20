package io.freemytv;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/** Decode compressed audio before sending PCM to the TV audio output. */
final class PlaybackAudio extends DefaultRenderersFactory {
    PlaybackAudio(Context context) { super(context); setEnableDecoderFallback(true); }
    @Override protected AudioSink buildAudioSink(Context context, boolean floats, boolean playbackParameters) {
        AudioSink output = super.buildAudioSink(context, floats, playbackParameters);
        if (output == null) return null;
        return new ForwardingAudioSink(output) {
            @Override public boolean supportsFormat(Format format) { return getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED; }
            @Override public int getFormatSupport(Format format) {
                return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType) ? super.getFormatSupport(format) : AudioSink.SINK_FORMAT_UNSUPPORTED;
            }
            @Override public AudioOffloadSupport getFormatOffloadSupport(Format format) { return AudioOffloadSupport.DEFAULT_UNSUPPORTED; }
        };
    }
}
