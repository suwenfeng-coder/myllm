package com.example.myllm.support.parser;

import com.example.myllm.support.FileTextExtractor;
import com.example.myllm.support.document.DocumentParser;
import com.example.myllm.support.document.ParsedDocument;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class LocalDocumentParser implements DocumentParser {

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "doc", "docx", "csv", "json", "xml", "html", "log");

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase());
    }

    @Override
    public ParsedDocument parse(MultipartFile file) {
        return FileTextExtractor.extractDocument(file);
    }
}
