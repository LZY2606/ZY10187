package com.windtunnel.zeroledger.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 根路径直接返回操作页面（不依赖各版本欢迎页自动发现行为）。 */
@Controller
public class PageController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index() throws IOException {
        byte[] body = new ClassPathResource("static/index.html").getContentAsByteArray();
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(new String(body, StandardCharsets.UTF_8));
    }
}
