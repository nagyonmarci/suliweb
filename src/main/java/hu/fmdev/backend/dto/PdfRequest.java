package hu.fmdev.backend.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Setter
@Getter
public class PdfRequest {
    private String templatePath;
    private Map<String, String> formData;

}