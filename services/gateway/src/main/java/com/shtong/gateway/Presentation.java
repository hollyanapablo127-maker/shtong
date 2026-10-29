package com.shtong.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class Presentation {
    private static final JsonNode DATA;
    static {
        try (var input = Presentation.class.getResourceAsStream("/presentation/account-access.zh-CN.json")) {
            if (input == null) throw new IOException("Missing presentation catalog");
            DATA = new ObjectMapper().readTree(input);
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
    static String label(String code) { return DATA.path("labels").path(code).asText(code); }
    static String template(String key) { return DATA.path("template").path(key).asText(); }
    static List<String> headers() {
        List<String> headers = new ArrayList<>();
        DATA.path("template").path("headers").forEach(value -> headers.add(value.asText()));
        return headers;
    }
    private Presentation() {}
}
