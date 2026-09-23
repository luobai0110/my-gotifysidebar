package com.github.luobai0110;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class GotifyMessageTest {

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void 解析完整的推送消息() throws Exception {
        String json = """
                {"id":25,"appid":5,"title":"my title","message":"my message","priority":2,
                 "date":"2018-02-27T19:36:10.5045044+01:00",
                 "extras":{"client::display":{"contentType":"text/markdown"}}}
                """;

        GotifyMessage message = mapper.readValue(json, GotifyMessage.class);

        assertEquals(25L, message.id());
        assertEquals(5L, message.appid());
        assertEquals("my title", message.title());
        assertEquals("my message", message.message());
        assertEquals(2, message.priority());
        assertEquals(OffsetDateTime.parse("2018-02-27T19:36:10.5045044+01:00"), message.date());
        assertEquals("text/markdown",
                ((Map<?, ?>) message.extras().get("client::display")).get("contentType"));
    }

    @Test
    void 忽略未知字段并允许缺失可选字段() throws Exception {
        String json = """
                {"id":1,"appid":2,"message":"only body","unknownField":"ignored"}
                """;

        GotifyMessage message = mapper.readValue(json, GotifyMessage.class);

        assertEquals(1L, message.id());
        assertNull(message.title());
        assertNull(message.date());
        assertNull(message.extras());
    }
}
