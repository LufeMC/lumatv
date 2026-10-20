package io.freemytv;

import android.graphics.Bitmap;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.util.List;
import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.common.model.Picture;
import org.jcodec.common.IntObjectMap;
import org.jcodec.codecs.h264.decode.*;
import org.jcodec.codecs.h264.decode.deblock.DeblockingFilter;
import org.jcodec.codecs.h264.io.model.*;

/** Decode one independent AVC frame without another Android video codec. */
final class AvcPreview {
    private final MediaFormat format;
    private final FrameReader reader = new FrameReader();
    private byte[][] planes;
    private DeblockerInput input;
    AvcPreview(MediaFormat format) throws Exception {
        this.format = format;
        ByteBuffer first = format.getByteBuffer("csd-0"), second = format.getByteBuffer("csd-1");
        if (first == null || first.remaining() + (second == null ? 0 : second.remaining()) > 16384) throw new java.io.IOException("preview_parameters");
        ByteBuffer config = ByteBuffer.allocate(first.remaining() + (second == null ? 0 : second.remaining()));
        config.put(first.duplicate()); if (second != null) config.put(second.duplicate()); config.flip();
        reader.readFrame(H264Utils.splitFrame(config));
    }
    Bitmap decode(ByteBuffer sample) throws Exception {
        int fw = format.getInteger(MediaFormat.KEY_WIDTH), fh = format.getInteger(MediaFormat.KEY_HEIGHT);
        if (fw <= 0 || fh <= 0 || fw > 1920 || fh > 1088) throw new java.io.IOException("preview_dimensions");
        if (format.containsKey(MediaFormat.KEY_ROTATION) && format.getInteger(MediaFormat.KEY_ROTATION) != 0) throw new java.io.IOException("preview_rotation");
        List<ByteBuffer> nals = new java.util.ArrayList<>(); int slices = 0;
        for (ByteBuffer nal : H264Utils.splitFrame(sample)) {
            if (!nal.hasRemaining()) continue;
            int type = nal.get(nal.position()) & 31;
            if (type == 1 || type == 2 || type == 3 || type == 4) throw new java.io.IOException("dependent_frame");
            if (type == 5) { slices++; nals.add(nal); }
        }
        // A single slice keeps JCodec on the existing background thread.
        if (slices != 1) throw new java.io.IOException("preview_slices");
        List<SliceReader> slicesRead = reader.readFrame(nals);
        if (slicesRead == null || slicesRead.size() != 1) throw new java.io.IOException("preview_slice");
        SliceReader slice = slicesRead.get(0); SliceHeader header = slice.getSliceHeader(); SeqParameterSet sps = header.sps;
        int w = (sps.picWidthInMbsMinus1 + 1) * 16, h = SeqParameterSet.getPicHeightInMbs(sps) * 16;
        if (header.sliceType != SliceType.I || w != (fw + 15) / 16 * 16 || h != (fh + 15) / 16 * 16
                || !sps.frameMbsOnlyFlag || sps.fieldPicFlag || sps.mbAdaptiveFrameFieldFlag
                || sps.bitDepthLumaMinus8 != 0 || sps.bitDepthChromaMinus8 != 0
                || sps.chromaFormatIdc != org.jcodec.common.model.ColorSpace.YUV420J
                || header.pps.constrainedIntraPredFlag || sps.qpprimeYZeroTransformBypassFlag)
            throw new java.io.IOException("preview_features");
        if (planes == null) { planes = new byte[][]{new byte[w*h], new byte[w*h/4], new byte[w*h/4]}; input = new DeblockerInput(sps); }
        for (byte[] plane : planes) java.util.Arrays.fill(plane, (byte) 0);
        for (int[] row : input.nCoeff) java.util.Arrays.fill(row, 0);
        for (int[] row : input.mbQps) java.util.Arrays.fill(row, 0);
        java.util.Arrays.fill(input.mbTypes, null); java.util.Arrays.fill(input.tr8x8Used, false);
        java.util.Arrays.fill(input.refsUsed, null); java.util.Arrays.fill(input.shs, null); input.mvs.clear();
        Frame picture = H264Decoder.createFrame(sps, planes, header.frameNum, SliceType.I, input.mvs, input.refsUsed, 0);
        // An independent I frame needs no retained reference pictures.
        new SliceDecoder(sps, new Frame[0], new IntObjectMap<Frame>(), input, picture).decodeFromReader(slice);
        for (SliceHeader decoded : input.shs) if (decoded == null) throw new java.io.IOException("preview_incomplete");
        new DeblockingFilter(w / 16, 8, input).deblockFrame(picture);
        if (picture == null) throw new java.io.IOException("preview_decode");
        int cw = picture.getCroppedWidth(), ch = picture.getCroppedHeight();
        int left = picture.getCrop() == null ? 0 : picture.getCrop().getX(), top = picture.getCrop() == null ? 0 : picture.getCrop().getY();
        int width = 320, height = Math.max(1, Math.min(240, 320 * ch / cw)); int[] pixels = new int[width * height];
        int standard = format.containsKey(MediaFormat.KEY_COLOR_STANDARD) ? format.getInteger(MediaFormat.KEY_COLOR_STANDARD) : MediaFormat.COLOR_STANDARD_BT709;
        if (standard == MediaFormat.COLOR_STANDARD_BT2020) throw new java.io.IOException("preview_hdr");
        boolean hd = standard == MediaFormat.COLOR_STANDARD_BT709;
        boolean full = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL;
        byte[][] data = picture.getData(); int stride = picture.getWidth();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int sx = left + x * cw / width, sy = top + y * ch / height;
            int l = data[0][sy * stride + sx] + 128 - (full ? 0 : 16);
            int u = data[1][sy / 2 * (stride / 2) + sx / 2], v = data[2][sy / 2 * (stride / 2) + sx / 2];
            int r = clamp(((full ? 256 : 298) * l + (full ? (hd ? 403 : 359) : (hd ? 459 : 409)) * v + 128) >> 8);
            int g = clamp(((full ? 256 : 298) * l - (full ? (hd ? 48 : 88) : (hd ? 55 : 100)) * u - (full ? (hd ? 120 : 183) : (hd ? 136 : 208)) * v + 128) >> 8);
            int b = clamp(((full ? 256 : 298) * l + (full ? (hd ? 475 : 454) : (hd ? 541 : 516)) * u + 128) >> 8);
            pixels[y * width + x] = 0xff000000 | r << 16 | g << 8 | b;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
