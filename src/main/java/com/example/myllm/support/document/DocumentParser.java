package com.example.myllm.support.document;

import org.springframework.web.multipart.MultipartFile;

public interface DocumentParser {

    String mode();

    boolean supports(String extension);

    ParsedDocument parse(MultipartFile file);

    default ParsedDocument parse(MultipartFile file, ParseProgressListener listener) {
        return parse(file);
    }
}
