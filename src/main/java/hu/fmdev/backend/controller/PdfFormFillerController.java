package hu.fmdev.backend.controller;

import hu.fmdev.backend.dto.PdfRequest;
import hu.fmdev.backend.service.PdfFormFillerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pdf")
public class PdfFormFillerController {

    @Autowired
    private PdfFormFillerService pdfFormFillerService;

    @PostMapping("/fill")
    @CrossOrigin
    public ResponseEntity<byte[]> fillForm(@RequestBody PdfRequest pdfRequest) {
        byte[] filledPdf = pdfFormFillerService.fillPdfForm(pdfRequest.getTemplatePath(), pdfRequest.getFormData());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("filename", "filled-form.pdf");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(filledPdf);
    }
}