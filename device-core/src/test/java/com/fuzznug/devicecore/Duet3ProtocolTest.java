package com.fuzznug.devicecore;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;

public class Duet3ProtocolTest {
    static class Fake implements Duet3Protocol.Transport {
        int value = 20, count = 1;
        boolean ignoreWrite;
        List<String> calls = new ArrayList<>();
        public int transfer(int type, int request, int word, int index, byte[] data) {
            assertEquals(0, word); assertEquals(1, data.length);
            calls.add(type + ":" + request + ":" + index + ":" + (data[0] & 255));
            if (type == 0xc0) data[0] = (byte)value;
            else if (!ignoreWrite) value = data[0] & 255;
            return count;
        }
    }
    @Test public void quieterUsesFreshLevelAndCorrectEndpointZeroPacket() throws Exception {
        Fake f = new Fake();
        assertEquals(21, new Duet3Protocol(f).stepLevel(1, -1));
        assertEquals(Arrays.asList("192:51:1:0", "64:51:1:21", "192:51:1:0"), f.calls);
    }
    @Test public void failedReadNeverWrites() {
        for (int count : new int[]{-1, 0, 2}) {
            Fake f = new Fake(); f.count = count;
            assertThrows(IOException.class, () -> new Duet3Protocol(f).stepLevel(0, 1));
            assertEquals(1, f.calls.size());
        }
    }
    @Test public void ignoredWriteIsNotReportedAsSuccess() {
        Fake f = new Fake(); f.ignoreWrite = true;
        assertThrows(IOException.class, () -> new Duet3Protocol(f).writeAndVerify(0, 0x33, 21));
    }
    @Test public void malformedResponsesStopControl() {
        Fake f = new Fake(); f.value = 255;
        assertThrows(IOException.class, () -> new Duet3Protocol(f).stepLevel(0, -1));
        assertEquals(1, f.calls.size());
        f.value = 2;
        assertThrows(IOException.class, () -> new Duet3Protocol(f).toggle(0, 0x40));
        assertEquals(2, f.calls.size());
    }
    @Test public void boundaryDoesNotSendUnneededWrite() throws Exception {
        Fake f = new Fake(); f.value = 0;
        assertEquals(0, new Duet3Protocol(f).stepLevel(0, 1));
        assertEquals(1, f.calls.size());
        f.value = 64;
        assertEquals(64, new Duet3Protocol(f).stepLevel(0, -1));
        assertEquals(2, f.calls.size());
    }
    @Test public void monoAndDimRemainDistinct() throws Exception {
        Fake f = new Fake(); f.value = 0;
        assertEquals(1, new Duet3Protocol(f).toggle(0, 0x46));
        assertEquals("64:70:0:1", f.calls.get(1));
        assertEquals(0, new Duet3Protocol(f).toggle(1, 0x40));
        assertEquals("64:64:1:0", f.calls.get(4));
    }
    @Test public void rejectsUnsupportedCommandsChannelsAndValuesBeforeUsb() {
        Fake f = new Fake(); Duet3Protocol p = new Duet3Protocol(f);
        assertThrows(IllegalArgumentException.class, () -> p.read(2, 0x33));
        assertThrows(IllegalArgumentException.class, () -> p.read(0, 0xff));
        assertThrows(IllegalArgumentException.class, () -> p.writeAndVerify(0, 0x33, 65));
        assertThrows(IllegalArgumentException.class, () -> p.toggle(0, 0x33));
        assertEquals(0, f.calls.size());
    }
}
