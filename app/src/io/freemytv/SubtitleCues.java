package io.freemytv;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An add-on subtitle file parsed by the app (SRT, WebVTT, ASS/SSA without styling) and shown by our own scheduler, so
 * switching subtitles never re-prepares the player. Times are in the file's clock; the player maps media time with
 * {@link #toFile} (manual offset, and the scale that fixes 25 fps subtitles on a 23.976 fps file).
 */
final class SubtitleCues {
    final long[] start, end;
    final String[] text;
    /** Shift in ms (positive = later) and speed factor: shown at file time × scale + offset. */
    long offset;
    double scale = 1;

    SubtitleCues(List<long[]> times, List<String> lines) {
        int n = times.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(times.get(a)[0], times.get(b)[0]));
        start = new long[n]; end = new long[n]; text = new String[n];
        for (int i = 0; i < n; i++) { long[] t = times.get(order[i]); start[i] = t[0]; end[i] = t[1]; text[i] = lines.get(order[i]); }
    }

    int size() { return start.length; }

    /** Media time (ms) → file time. */
    long toFile(long media) { return Math.round((media - offset) / scale); }

    /** File time → media time. */
    long toMedia(long file) { return Math.round(file * scale) + offset; }

    /** Index of the first cue showing at this media time, or -1. Cues may overlap; see {@link #visible}. */
    int at(long media) {
        long t = toFile(media);
        int lo = 0, hi = start.length - 1, last = -1;
        while (lo <= hi) { int mid = (lo + hi) >>> 1; if (start[mid] <= t) { last = mid; lo = mid + 1; } else hi = mid - 1; }
        for (int i = last; i >= 0 && i > last - 8; i--) if (end[i] > t) return i;
        return -1;
    }

    /** Collects the text of every cue showing at this media time into out (no allocation when nothing changed). */
    void visible(long media, List<String> out) {
        out.clear();
        long t = toFile(media);
        int lo = 0, hi = start.length - 1, last = -1;
        while (lo <= hi) { int mid = (lo + hi) >>> 1; if (start[mid] <= t) { last = mid; lo = mid + 1; } else hi = mid - 1; }
        for (int i = Math.max(0, last - 7); i <= last; i++) if (end[i] > t) out.add(text[i]);
    }

    /** Media time of the next change (a cue starting or ending) after this media time, or Long.MAX_VALUE. */
    long nextChange(long media) {
        long t = toFile(media), next = Long.MAX_VALUE;
        int lo = 0, hi = start.length - 1, first = start.length;
        while (lo <= hi) { int mid = (lo + hi) >>> 1; if (start[mid] > t) { first = mid; hi = mid - 1; } else lo = mid + 1; }
        if (first < start.length) next = start[first];
        for (int i = Math.max(0, first - 8); i < first; i++) if (end[i] > t && end[i] < next) next = end[i];
        return next == Long.MAX_VALUE ? next : toMedia(next);
    }

    long lastEnd() { long max = 0; for (long e : end) max = Math.max(max, e); return max; }

    static final double PAL = 25.0 / (24000.0 / 1001.0);

    /**
     * Speed fix: subtitles timed for a 23.976 fps release drift later and later on the 4% faster 25 fps cut (and the
     * other way round). When the last line lands after the video ends, by no more than that ratio, the subtitles are
     * for the slower cut: shrink. The inverse (lines ending early) is normal because of credits, so that case is left
     * to {@link SubtitleSync}, which tries both speeds against the dialogue.
     */
    static double driftScale(long lastCueEnd, long durationMs) {
        if (lastCueEnd <= 0 || durationMs < 10 * 60000L) return 1;
        double ratio = (double) lastCueEnd / durationMs;
        if (ratio > 1.005 && ratio < PAL + 0.025) return 1 / PAL;
        return 1;
    }

    // --- Parsing

    private static final Pattern SRT_TIME = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    private static final Pattern VTT_TIME = Pattern.compile("(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{1,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{1,3})");
    private static final Pattern TAGS = Pattern.compile("<[^>]{0,80}>|\\{\\\\[^}]{0,200}\\}|\\{[^}]{0,40}\\}");

    static SubtitleCues parse(String body) {
        if (body == null) return null;
        String text = body.startsWith("﻿") ? body.substring(1) : body;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String head = text.length() > 2000 ? text.substring(0, 2000) : text;
        SubtitleCues cues;
        if (head.trim().startsWith("WEBVTT")) cues = vtt(text);
        else if (head.contains("[Script Info]") || head.contains("[Events]") || text.contains("\nDialogue:")) cues = ass(text);
        else cues = srt(text);
        return cues == null || cues.size() == 0 ? null : cues;
    }

    private static long ms(String h, String m, String s, String f) {
        long frac = Long.parseLong(f); if (f.length() == 1) frac *= 100; else if (f.length() == 2) frac *= 10;
        return ((h == null ? 0 : Long.parseLong(h)) * 3600 + Long.parseLong(m) * 60 + Long.parseLong(s)) * 1000 + frac;
    }

    static String clean(String line) {
        String t = TAGS.matcher(line).replaceAll("").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        StringBuilder out = new StringBuilder();
        for (String part : t.split("\n")) { String p = part.trim(); if (!p.isEmpty()) { if (out.length() > 0) out.append('\n'); out.append(p); } }
        return out.toString();
    }

    private static SubtitleCues srt(String text) {
        List<long[]> times = new ArrayList<>(); List<String> lines = new ArrayList<>();
        String[] rows = text.split("\n");
        for (int i = 0; i < rows.length && times.size() < 20000; i++) {
            Matcher m = SRT_TIME.matcher(rows[i]);
            if (!m.find()) continue;
            long a = ms(m.group(1), m.group(2), m.group(3), m.group(4)), b = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder cue = new StringBuilder();
            int j = i + 1;
            while (j < rows.length && !rows[j].trim().isEmpty() && !SRT_TIME.matcher(rows[j]).find()) { cue.append(rows[j]).append('\n'); j++; }
            i = j - 1;
            String clean = clean(cue.toString());
            if (b > a && !clean.isEmpty()) { times.add(new long[]{a, b}); lines.add(clean); }
        }
        return new SubtitleCues(times, lines);
    }

    private static SubtitleCues vtt(String text) {
        List<long[]> times = new ArrayList<>(); List<String> lines = new ArrayList<>();
        String[] rows = text.split("\n");
        for (int i = 0; i < rows.length && times.size() < 20000; i++) {
            Matcher m = VTT_TIME.matcher(rows[i]);
            if (!m.find()) continue;
            long a = ms(m.group(1), m.group(2), m.group(3), m.group(4)), b = ms(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder cue = new StringBuilder();
            int j = i + 1;
            while (j < rows.length && !rows[j].trim().isEmpty()) { cue.append(rows[j]).append('\n'); j++; }
            i = j - 1;
            String clean = clean(cue.toString());
            if (b > a && !clean.isEmpty()) { times.add(new long[]{a, b}); lines.add(clean); }
        }
        return new SubtitleCues(times, lines);
    }

    private static final Pattern ASS_TIME = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})[.:](\\d{1,3})");

    private static SubtitleCues ass(String text) {
        List<long[]> times = new ArrayList<>(); List<String> lines = new ArrayList<>();
        int startField = 1, endField = 2, textField = 9, fields = 10;
        boolean events = false;
        for (String row : text.split("\n")) {
            String line = row.trim();
            if (line.startsWith("[")) { events = line.equalsIgnoreCase("[Events]"); continue; }
            if (!events) continue;
            if (line.toLowerCase(Locale.ROOT).startsWith("format:")) {
                String[] names = line.substring(7).split(",");
                fields = names.length;
                for (int i = 0; i < names.length; i++) {
                    String n = names[i].trim().toLowerCase(Locale.ROOT);
                    if (n.equals("start")) startField = i; else if (n.equals("end")) endField = i; else if (n.equals("text")) textField = i;
                }
                continue;
            }
            if (!line.startsWith("Dialogue:") || times.size() >= 20000) continue;
            String[] parts = line.substring(9).split(",", fields);
            if (parts.length <= Math.max(textField, Math.max(startField, endField))) continue;
            Matcher a = ASS_TIME.matcher(parts[startField].trim()), b = ASS_TIME.matcher(parts[endField].trim());
            if (!a.find() || !b.find()) continue;
            long from = ms(a.group(1), a.group(2), a.group(3), a.group(4)), to = ms(b.group(1), b.group(2), b.group(3), b.group(4));
            String clean = clean(parts[textField]);
            if (to > from && !clean.isEmpty()) { times.add(new long[]{from, to}); lines.add(clean); }
        }
        return new SubtitleCues(times, lines);
    }

    // --- Download

    /** Downloads and parses a subtitle file (HTTPS, or a file in the app's cache for tests). Off the UI thread. */
    static SubtitleCues load(Context context, String address) throws Exception {
        byte[] bytes;
        if (address.startsWith("file:")) {
            java.io.File file = new java.io.File(android.net.Uri.parse(address).getPath()).getCanonicalFile();
            if (!file.getPath().startsWith(context.getCacheDir().getCanonicalPath()) || file.length() > 4 * 1024 * 1024) throw new Exception("file");
            bytes = java.nio.file.Files.readAllBytes(file.toPath());
        } else {
            if (!MediaHttp.https(address)) throw new Exception("address");
            bytes = download(address, 0);
        }
        return parse(decode(bytes));
    }

    private static byte[] download(String address, int redirects) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "FreeMyTV/1.0");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400 && redirects < 3) {
                String next = connection.getHeaderField("Location");
                if (next == null) throw new Exception("redirect");
                String target = new URL(new URL(address), next).toString();
                if (!MediaHttp.https(target)) throw new Exception("redirect");
                return download(target, redirects + 1);
            }
            if (code != 200) throw new Exception("status");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) {
                    if (output.size() + count > 4 * 1024 * 1024 || Thread.currentThread().isInterrupted()) throw new Exception("size");
                    output.write(block, 0, count);
                }
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    /** UTF-8 (with or without BOM) or UTF-16 when marked; otherwise Windows-1252, common for older subtitle files. */
    static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 255) == 0xFF && (bytes[1] & 255) == 0xFE) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && (bytes[0] & 255) == 0xFE && (bytes[1] & 255) == 0xFF) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            try { return new String(bytes, Charset.forName("windows-1252")); } catch (Exception ignored) { return new String(bytes, StandardCharsets.ISO_8859_1); }
        }
    }
}
