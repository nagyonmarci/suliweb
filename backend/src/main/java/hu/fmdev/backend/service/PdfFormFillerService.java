package hu.fmdev.backend.service;

import com.itextpdf.forms.PdfAcroForm;
import com.itextpdf.forms.fields.PdfFormField;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@Service
public class PdfFormFillerService {

    @Value("${app.pdf.templatePath}")
    private String pdfTemplatePath;

    @Value("${app.pdf.fontFamily}")
    private String fontFamily;

    @Value("${app.pdf.encoding}")
    private String encoding;

    @Value("${app.pdf.fontSize}")
    private int fontSize;

    public byte[] fillPdfForm(String pdfTemplatePath, Map<String, String> formData) {


        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             PdfReader reader = new PdfReader(new ClassPathResource(pdfTemplatePath).getInputStream());
             PdfWriter writer = new PdfWriter(baos);
             PdfDocument pdfDoc = new PdfDocument(reader, writer)) {

            PdfAcroForm form = PdfAcroForm.getAcroForm(pdfDoc, true);
            PdfFont font = PdfFontFactory.createFont(new ClassPathResource(fontFamily).getURL().getPath(), encoding, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);

            for (Map.Entry<String, String> entry : formData.entrySet()) {
                PdfFormField field = form.getField(entry.getKey());
                if (field != null) {
                    field.setValue(entry.getValue(), font, fontSize);
                }
            }

            form.flattenFields();
            pdfDoc.close();

            return baos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}