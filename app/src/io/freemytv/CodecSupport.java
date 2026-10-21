package io.freemytv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What this TV can decode, compared with the codec hints in a source's name, description and file name. Sources that
 * can't play here (Dolby Vision without a Dolby Vision decoder, AV1 without a hardware decoder, 10-bit HEVC or HDR
 * without a Main10 decoder) are left out of the automatic queue and shown greyed in the source sheet.
 */
final class CodecSupport {
    private CodecSupport() {}

    static final String DOLBY_VISION = "Dolby Vision", AV1 = "AV1", HEVC_10 = "HEVC 10-bit", HEVC = "HEVC", AVC = "H.264";

    /** The decoders this device has; tests can replace it. */
    static final class Device {
        boolean dolbyVision, av1, av1Hardware, hevc, hevcMain10, hevc4k, avc4k;
    }

    private static volatile Device device;

    static Device device() {
        Device d = device;
        if (d != null) return d;
        d = new Device();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    String mime = type.toLowerCase(Locale.ROOT);
                    if (mime.equals("video/dolby-vision")) d.dolbyVision = true;
                    if (mime.equals("video/av01")) { d.av1 = true; if (info.isHardwareAccelerated()) d.av1Hardware = true; }
                    if (mime.equals("video/avc")) {
                        try {
                            MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(type).getVideoCapabilities();
                            if (video != null && video.isSizeSupported(3840, 2160)) d.avc4k = true;
                        } catch (Exception ignored) {}
                    }
                    if (mime.equals("video/hevc")) {
                        d.hevc = true;
                        try {
                            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                            for (MediaCodecInfo.CodecProfileLevel level : caps.profileLevels)
                                if (level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                                        || level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                                        || level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) d.hevcMain10 = true;
                            if (caps.getVideoCapabilities() != null && caps.getVideoCapabilities().isSizeSupported(3840, 2160)) d.hevc4k = true;
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {
            d.hevc = d.hevcMain10 = d.hevc4k = d.avc4k = d.dolbyVision = d.av1 = d.av1Hardware = true; // unknown device: don't reject anything
        }
        device = d;
        return d;
    }

    static void setDeviceForTest(Device d) { device = d; }

    private static final Pattern DV = Pattern.compile("(?<![a-z0-9])(dolby[ ._-]?vision|dovi|dv|dvhe|dvh1)(?![a-z0-9])");
    private static final Pattern HDR = Pattern.compile("(?<![a-z0-9])(hdr|hdr10|hdr10\\+|hdr10plus|hlg)(?![a-z0-9])");
    private static final Pattern AV1_TAG = Pattern.compile("(?<![a-z0-9])av1(?![a-z0-9])");
    private static final Pattern HEVC_TAG = Pattern.compile("(?<![a-z0-9])(hevc|x265|h[ .]?265)(?![a-z0-9])");
    private static final Pattern AVC_TAG = Pattern.compile("(?<![a-z0-9])(avc|x264|h[ .]?264)(?![a-z0-9])");
    private static final Pattern TEN_BIT = Pattern.compile("(?<![a-z0-9])10[ ._-]?bits?(?![a-z0-9])");

    /** The codec named in the text, for the source sheet ("" when the text doesn't say). */
    static String codec(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (DV.matcher(t).find()) return DOLBY_VISION;
        if (AV1_TAG.matcher(t).find()) return AV1;
        if (HEVC_TAG.matcher(t).find()) return TEN_BIT.matcher(t).find() || HDR.matcher(t).find() ? HEVC_10 : HEVC;
        if (AVC_TAG.matcher(t).find()) return AVC;
        return "";
    }

    /** Why this TV can't play a source with these hints, or null when it can (or the hints don't say). */
    static String unsupported(String text, int quality) { return unsupported(text, quality, device()); }

    static String unsupported(String text, int quality, Device d) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean hdr = HDR.matcher(t).find(), tenBit = TEN_BIT.matcher(t).find();
        if (quality >= 2160 && !d.avc4k && !d.hevc4k && !d.dolbyVision && !(d.av1 && d.av1Hardware)) return "4K";
        // Dolby Vision with an HDR10 base layer (profile 8, "DV HDR10") falls back to HDR10 on other TVs.
        if (DV.matcher(t).find() && !d.dolbyVision) {
            if (!hdr) return DOLBY_VISION;
            if (!d.hevcMain10) return DOLBY_VISION;
        }
        if (AV1_TAG.matcher(t).find() && (!d.av1 || !d.av1Hardware && quality > 1080)) return AV1;
        boolean hevc = HEVC_TAG.matcher(t).find();
        if (AVC_TAG.matcher(t).find() && quality >= 2160 && !d.avc4k) return AVC;
        if (hevc && !d.hevc) return HEVC;
        if ((hevc || quality >= 2160) && (tenBit || hdr) && !d.hevcMain10) return HEVC_10;
        if (hevc && quality >= 2160 && !d.hevc4k) return HEVC;
        return null;
    }
}
