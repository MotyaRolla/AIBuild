package dev.aibuild.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperationLoopGuardTest {
    @Test void streamedDuplicateOpsAbortEvenWithDifferentKeyOrderAndWhitespace() {
        var guard = new OperationLoopGuard();
        StringBuilder stream = new StringBuilder("{\"ops\":[");
        for (int i=0;i<6;i++) {
            stream.append(i%2==0 ? "{\"op\":\"set\",\"pos\":[1,2,3],\"block\":\"stone\"},"
                    : "{ \"block\": \"stone\", \"pos\": [1,2,3], \"op\": \"set\"},");
            assertEquals(i==5,guard.looping(stream));
        }
    }

    @Test void repeatedMaterialAcrossDifferentCoordinatesIsAllowed() {
        var guard = new OperationLoopGuard();
        StringBuilder stream = new StringBuilder("{\"notes\":\"braces { } and escaped quote \\\" are text\",\"ops\":[");
        for (int i=0;i<100;i++) {
            String op="{\"op\":\"set\",\"pos\":["+i+",1,0],\"block\":\"stone\"},";
            for (char c:op.toCharArray()) {
                stream.append(c);
                assertFalse(guard.looping(stream));
            }
        }
    }
    @Test void identicalChildrenInDifferentModuleInstancesAreNotLoops() {
        var guard = new OperationLoopGuard();
        StringBuilder stream = new StringBuilder("{\"ops\":[");
        for (int i=0;i<20;i++) {
            stream.append("{\"op\":\"repeat\",\"offsets\":[[").append(i).append(",0,0]],\"ops\":[")
                    .append("{\"op\":\"set\",\"pos\":[0,1,0],\"block\":\"stone\"}]},");
            assertFalse(guard.looping(stream));
        }
    }
}
