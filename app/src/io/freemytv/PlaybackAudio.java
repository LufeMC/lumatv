package io.freemytv;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import java.nio.ByteBuffer;

/**
 * Decode compressed audio before sending PCM to the TV audio output. The decoded PCM also passes, read-only, through
 * SubtitleSync while it is listening (the first minutes of an add-on subtitle).
 */
final class PlaybackAudio extends DefaultRenderersFactory {
    private final SubtitleSync listener;
    PlaybackAudio(Context context) { this(context, null); }
    PlaybackAudio(Context context, SubtitleSync listener) { super(context); this.listener = listener; setEnableDecoderFallback(true); }
    @Override protected AudioSink buildAudioSink(Context context, boolean floats, boolean playbackParameters) {
        AudioSink output = super.buildAudioSink(context, floats, playbackParameters);
        if (output == null) return null;
        final SubtitleSync sync = listener;
        return new ForwardingAudioSink(output) {
            @Override public boolean supportsFormat(Format format) { return getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED; }
            @Override public int getFormatSupport(Format format) {
                return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType) ? super.getFormatSupport(format) : AudioSink.SINK_FORMAT_UNSUPPORTED;
            }
            @Override public AudioOffloadSupport getFormatOffloadSupport(Format format) { return AudioOffloadSupport.DEFAULT_UNSUPPORTED; }
            @Override public void configure(Format format, int specifiedBufferSize, int[] outputChannels) throws ConfigurationException {
                if (sync != null) sync.configure(format.pcmEncoding == Format.NO_VALUE ? C.ENCODING_PCM_16BIT : format.pcmEncoding, format.channelCount, format.sampleRate);
                super.configure(format, specifiedBufferSize, outputChannels);
            }
            @Override public void setOutputStreamOffsetUs(long outputStreamOffsetUs) {
                if (sync != null) sync.streamOffset(outputStreamOffsetUs);
                super.setOutputStreamOffsetUs(outputStreamOffsetUs);
            }
            @Override public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount) throws InitializationException, WriteException {
                if (sync != null && sync.active()) sync.feed(buffer, presentationTimeUs);
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
            }
        };
    }
}
