package com.remory.backend.account;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetMail {
    private final JavaMailSender sender;
    private final PasswordResetEmailTemplate template;
    private final String from;
    private final boolean available;

    public PasswordResetMail(ObjectProvider<JavaMailSender> sender, Environment environment,
            @Value("${remory.password-reset.enabled:false}") boolean enabled,
            @Value("${remory.password-reset.from:}") String from,
            PasswordResetEmailTemplate template) {
        this.sender = sender.getIfAvailable();
        this.template = template;
        this.from = from;
        this.available = enabled && this.sender != null && !from.isBlank()
                && !environment.getProperty("spring.mail.host", "").isBlank();
    }

    public boolean available() {
        return available;
    }

    public void send(String email, String code, String locale) {
        if (!available) throw new IllegalStateException("Password reset mail is not configured");
        var content = template.render(code, locale);
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_RELATED, StandardCharsets.UTF_8.name());
            helper.setFrom(from, "Remory");
            helper.setTo(email);
            helper.setSubject(content.subject());
            helper.setText(content.plainText(), content.html());
            // Add inline images AFTER the text so mail readers can resolve their content IDs.
            // A packaged PNG avoids external image requests and does not depend on SVG support.
            helper.addInline("remory-mongi", new ClassPathResource("mail/mongi.png"), "image/png");
        } catch (MessagingException | UnsupportedEncodingException failure) {
            throw new MailPreparationException("Cannot prepare password reset email", failure);
        }
        sender.send(message);
    }
}
