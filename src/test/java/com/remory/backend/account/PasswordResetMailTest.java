package com.remory.backend.account;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PasswordResetMailTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final PasswordResetEmailTemplate template = new PasswordResetEmailTemplate();

    private PasswordResetMail configured(boolean enabled, String from, String host) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("mailSender", sender);
        return new PasswordResetMail(beans.getBeanProvider(JavaMailSender.class),
                new MockEnvironment().withProperty("spring.mail.host", host), enabled, from, template);
    }

    private PasswordResetMail ready() {
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        return configured(true, "noreply@example.com", "smtp.example.com");
    }

    @Test
    void allFourLanguagesHaveMatchingPlainAndHtmlAlternativesAndBrandedSender() throws Exception {
        var mail = ready();
        for (String locale : new String[]{"ko", "en", "ja", "zh-CN"}) mail.send("person@example.com", "000123", locale);
        var messages = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(4)).send(messages.capture());
        for (MimeMessage message : messages.getAllValues()) {
            message.saveChanges();
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("person@example.com");
            assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("Remory");
            assertThat(message.getSubject()).isNotBlank();
            var root = (Multipart) message.getContent();
            assertThat(message.isMimeType("multipart/related")).isTrue();
            var alternatives = (Multipart) root.getBodyPart(0).getContent();
            assertThat(alternatives.getCount()).isEqualTo(2);
            assertThat(alternatives.getBodyPart(0).isMimeType("text/plain")).isTrue();
            assertThat(alternatives.getBodyPart(1).isMimeType("text/html")).isTrue();
            String plain = (String) alternatives.getBodyPart(0).getContent();
            String html = (String) alternatives.getBodyPart(1).getContent();
            assertThat(plain).contains("\n\n000123\n\n").contains("10").doesNotContain("http", "cid:");
            assertThat(html).contains(">000123</p>", "cid:remory-mongi", "remory", "10").doesNotContain("{{", "#token=", "<script");
            assertThat(html).doesNotContain("person@example.com");
            var raw = new ByteArrayOutputStream();
            message.writeTo(raw);
            assertThat(raw.size()).isLessThan(100_000);
        }
    }

    @Test
    void mascotIsARealInlinePngWithoutExternalDownloads() throws Exception {
        var mail = ready();
        mail.send("person@example.com", "000123", "ko");
        var sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        sent.getValue().saveChanges();
        var root = (Multipart) sent.getValue().getContent();
        assertThat(root.getCount()).isEqualTo(2);
        var image = root.getBodyPart(1);
        assertThat(image.getDisposition()).isEqualTo(Part.INLINE);
        assertThat(image.getHeader("Content-ID")).containsExactly("<remory-mongi>");
        assertThat(image.isMimeType("image/png")).isTrue();
        try (var stream = image.getInputStream()) {
            assertThat(stream.readNBytes(8)).containsExactly((byte)137, (byte)80, (byte)78, (byte)71, (byte)13, (byte)10, (byte)26, (byte)10);
        }
    }

    @Test
    void plainAndHtmlUseLocalizedCopyWithKoreanFallback() {
        assertThat(template.render("000123", "en").html()).contains("lang=\"en\"", "Reset your password");
        assertThat(template.render("000123", "ja").html()).contains("lang=\"ja\"", "パスワードの再設定");
        assertThat(template.render("000123", "zh-CN").html()).contains("lang=\"zh-CN\"", "重置密码");
        assertThat(template.render("000123", null).plainText()).contains("비밀번호", "10분");
        assertThat(template.render("000123", "unknown").html()).contains("lang=\"ko\"");
    }

    @Test
    void malformedOrHtmlLikeCodeIsRejectedBeforeMailPreparation() {
        var mail = configured(true, "sender@example.com", "smtp.example.com");
        assertThatThrownBy(() -> mail.send("person@example.com", "<img>", "ko")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mail.send("person@example.com", "12345", "ko")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sender);
    }

    @Test
    void missingMailConfigurationIsUnavailable() {
        assertThat(configured(false, "sender@example.com", "smtp.example.com").available()).isFalse();
        assertThat(configured(true, "", "smtp.example.com").available()).isFalse();
        assertThat(configured(true, "sender@example.com", "").available()).isFalse();
        verifyNoInteractions(sender);
    }
}
