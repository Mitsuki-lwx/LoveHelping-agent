package cn.lwx.lwxaiagent.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void okWithData() {
        Result<String> r = Result.ok("hello");
        assertEquals(200, r.code());
        assertEquals("success", r.message());
        assertEquals("hello", r.data());
    }

    @Test
    void okWithoutData() {
        Result<String> r = Result.ok();
        assertEquals(200, r.code());
        assertNull(r.data());
    }

    @Test
    void failWithCodeAndMessage() {
        Result<String> r = Result.fail(400, "bad request");
        assertEquals(400, r.code());
        assertEquals("bad request", r.message());
        assertNull(r.data());
    }

    @Test
    void failDefault400() {
        Result<String> r = Result.fail("something wrong");
        assertEquals(400, r.code());
        assertEquals("something wrong", r.message());
    }

    @Test
    void errorIs500() {
        Result<String> r = Result.error("server error");
        assertEquals(500, r.code());
        assertEquals("server error", r.message());
    }
}
