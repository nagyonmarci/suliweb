package hu.fmdev.backend.util;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

public class HtmlSanitizerUtil {

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("a", "b", "i", "u", "strong", "em")
            .allowUrlProtocols("https")
            .allowAttributes("href").onElements("a")
            .requireRelNofollowOnLinks()
            .toFactory();

    public static String sanitize(String htmlContent) {
        return POLICY.sanitize(htmlContent);
    }
}
