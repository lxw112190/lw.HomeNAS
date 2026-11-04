package com.lw.homenas;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import static org.junit.Assert.*;

public class ProtocolTest {
    @Test public void normalizeAndRequireExplicitHttp() {
        assertEquals("https://nas.local:9443",Protocol.endpoint(" https://NAS.local:9443/ ",false));
        assertEquals("http://127.0.0.1:8787",Protocol.endpoint("http://127.0.0.1:8787",true));
        for(String value:new String[]{"http://nas.local","file:///etc/passwd","https://u:p@nas.local","https://nas.local/api","https://nas.local?q=x","https://nas.local/#fragment","javascript:alert(1)","https://nas.local:0","https://nas.local:65536"})
            assertThrows(IllegalArgumentException.class,()->Protocol.endpoint(value,false));
    }
    @Test public void offsetsKeep64BitPrecisionAndRejectOverflow() throws Exception {
        assertEquals(5_368_709_120L,Protocol.offset("5368709120",Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE,Protocol.offset(Long.toString(Long.MAX_VALUE),Long.MAX_VALUE));
        for(String value:new String[]{"-1","01","1.5","9223372036854775808","11",""})
            assertThrows(java.io.IOException.class,()->Protocol.offset(value,10));
    }
    @Test public void hashAndNonSeekingProvider() throws Exception {
        byte[] bytes="abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",Protocol.hash(bytes,3));
        ByteArrayInputStream source=new ByteArrayInputStream(bytes){@Override public long skip(long n){return 0;}};
        Protocol.skip(source,2);assertEquals('c',source.read());assertThrows(java.io.IOException.class,()->Protocol.skip(source,10));
    }
    @Test public void longNamesKeepExtensionAndUnicodeBoundaries() {
        String normal="a".repeat(180)+".mp4";assertEquals(normal,Protocol.fileName(normal));
        String shortened=Protocol.fileName("家庭😀".repeat(100)+".mp4");
        assertTrue(shortened.endsWith(".mp4"));assertTrue(shortened.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=512);
        assertFalse(shortened.contains("\ufffd"));
    }
}
