package com.github.wellch4n.oops.interfaces.rest;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the CLI bundled with this backend, behind the normal OpenAPI token authentication. */
@RestController
@RequestMapping("/openapi/skill/oops.py")
public class SkillScriptController {

    private final byte[] source;
    private final String hash;

    public SkillScriptController() throws IOException, NoSuchAlgorithmException {
        try (InputStream stream = new ClassPathResource("skill/oops.py").getInputStream()) {
            source = stream.readAllBytes();
        }
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    }

    @GetMapping(value = "/hash", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> hash() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(hash);
    }

    @GetMapping(produces = "text/plain;charset=UTF-8")
    public ResponseEntity<byte[]> source() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(source);
    }
}
