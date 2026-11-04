package com.lw.homenas;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Rules shared by native transport, queue and JVM tests. All lengths remain long. */
public final class Protocol {
    private Protocol() {}
    public static String endpoint(String value, boolean allowHttp) {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || (allowHttp && scheme.equals("http")))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), "", null, null).toASCIIString();
        } catch (Exception error) {
            throw new IllegalArgumentException("请输入完整服务器地址，如 https://192.168.1.100:9443；HTTP需显式开启联调选项");
        }
    }
    public static long offset(String value, long total) throws IOException {
        try {
            if (!value.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
            long offset = Long.parseLong(value);
            if (offset > total) throw new NumberFormatException();
            return offset;
        } catch (RuntimeException error) { throw new IOException("服务器确认偏移不合法"); }
    }
    public static boolean assetId(String value) { return value != null && value.matches("[a-f0-9]{48}"); }
    public static String fileName(String name) {
        if(name==null||name.isEmpty())return "文件.bin";
        if(name.getBytes(StandardCharsets.UTF_8).length<=512)return name;
        int dot=name.lastIndexOf('.');String suffix=dot>0&&name.length()-dot<=13?name.substring(dot):"";
        int limit=512-suffix.getBytes(StandardCharsets.UTF_8).length,used=0;StringBuilder result=new StringBuilder();
        for(int at=0;at<name.length();) {
            int point=name.codePointAt(at);String part=new String(Character.toChars(point));int bytes=part.getBytes(StandardCharsets.UTF_8).length;
            if(used+bytes>limit)break;result.append(part);used+=bytes;at+=Character.charCount(point);
        }
        return result+suffix;
    }
    public static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte b : data) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    public static String hash(byte[] bytes, int count) {
        try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(bytes, 0, count); return hex(digest.digest()); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    public static String key(String input) { return hash(input.getBytes(StandardCharsets.UTF_8), input.getBytes(StandardCharsets.UTF_8).length); }
    public static void skip(InputStream stream, long count) throws IOException {
        byte[] discard = new byte[65536];
        while (count > 0) {
            long skipped = stream.skip(count);
            if (skipped <= 0) { int read = stream.read(discard, 0, (int)Math.min(count, discard.length)); if (read < 0) throw new IOException("文件长度已改变"); skipped = read; }
            count -= skipped;
        }
    }
}
