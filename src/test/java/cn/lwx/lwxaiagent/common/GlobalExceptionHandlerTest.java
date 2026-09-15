package cn.lwx.lwxaiagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    @Test void missingResourceIs404NotAnInternalFailure() {
        var result = new GlobalExceptionHandler().handleNotFound(new NoResourceFoundException(HttpMethod.GET, "tenant/token"));
        assertEquals(404, result.code());
        assertFalse(result.message().contains("tenant/token"));
    }
}
