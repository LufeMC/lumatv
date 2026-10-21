package io.freemytv;

import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.*;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Read container headers without scanning samples or allocating a decoder. */
final class SourceHeader implements ExtractorOutput {
    final List<Format> formats = new ArrayList<>();
    long duration;
    private boolean tracksComplete, mapRead;
    private static final class Ready extends RuntimeException {}
    private void complete() { if (tracksComplete && mapRead) throw new Ready(); }
    @Override public void endTracks() { tracksComplete = true; complete(); }
    @Override public void seekMap(SeekMap map) { duration = Math.max(0, map.getDurationUs() / 1000); mapRead = true; complete(); }
    @Override public TrackOutput track(int id, int type) {
        return new TrackOutput() {
            private final DummyTrackOutput sink = new DummyTrackOutput();
            @Override public void format(Format format) { formats.add(format); }
            @Override public int sampleData(androidx.media3.common.DataReader input, int size, boolean end, int part) throws IOException { return sink.sampleData(input, size, end, part); }
            @Override public void sampleData(ParsableByteArray data, int size, int part) { sink.sampleData(data, size, part); }
            @Override public void sampleMetadata(long time, int flags, int size, int offset, CryptoData crypto) {}
        };
    }
    private static DefaultExtractorInput input(PreviewReader reader, long position) {
        long[] cursor = {position};
        return new DefaultExtractorInput((data, offset, length) -> {
            int count = reader.readAt(cursor[0], data, offset, length);
            if (count > 0) cursor[0] += count;
            return count;
        }, position, reader.getSize());
    }
    static SourceHeader read(PreviewReader reader) throws IOException {
        SourceHeader header = new SourceHeader();
        Extractor selected = null;
        MatroskaExtractor matroska = new MatroskaExtractor(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES) {
            private long scale = 1000000;
            private double ticks = -1;
            @Override protected void integerElement(int id, long value) throws androidx.media3.common.ParserException {
                super.integerElement(id, value); if (id == 0x2AD7B1) scale = value;
            }
            @Override protected void floatElement(int id, double value) throws androidx.media3.common.ParserException {
                super.floatElement(id, value); if (id == 0x4489) ticks = value;
            }
            @Override protected void endMasterElement(int id) throws androidx.media3.common.ParserException {
                super.endMasterElement(id);
                if (id == 0x1549A966 && ticks >= 0) {
                    header.duration = (long) (ticks * scale / 1000000.0); header.mapRead = true; header.complete();
                }
            }
        };
        for (Extractor candidate : new Extractor[]{matroska, new Mp4Extractor(), new FragmentedMp4Extractor()}) {
            boolean match;
            try { match = candidate.sniff(input(reader, 0)); }
            catch (Exception error) { candidate.release(); throw error; }
            if (match) { selected = candidate; break; }
            candidate.release();
        }
        if (selected == null) throw new IOException("unknown_container");
        try {
            selected.init(header); DefaultExtractorInput input = input(reader, 0); PositionHolder seek = new PositionHolder();
            for (int step = 0; step < 2048; step++) {
                int result = selected.read(input, seek);
                if (result == Extractor.RESULT_END_OF_INPUT) break;
                if (result == Extractor.RESULT_SEEK) input = input(reader, seek.position);
            }
        } catch (Ready complete) {
            return header;
        } finally { selected.release(); }
        if (!header.tracksComplete || !header.mapRead) throw new IOException("incomplete_header");
        return header;
    }
}
