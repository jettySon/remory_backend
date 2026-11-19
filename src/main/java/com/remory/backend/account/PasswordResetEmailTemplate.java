package com.remory.backend.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class PasswordResetEmailTemplate {
    private final String template;

    public PasswordResetEmailTemplate() {
        try {
            template = new ClassPathResource("mail/password-reset.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load password reset email template", failure);
        }
    }

    public Content render(String code, String locale) {
        if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Verification code must be six digits");
        Copy copy = copy(locale);
        String html = template;
        var values = Map.ofEntries(
                Map.entry("lang", copy.lang()), Map.entry("wordBreak", copy.lang().equals("ko") ? "keep-all" : "normal"),
                Map.entry("subject", copy.subject()),
                Map.entry("title", copy.title()), Map.entry("intro", copy.intro()),
                Map.entry("codeLabel", copy.codeLabel()), Map.entry("code", code),
                Map.entry("expiration", copy.expiration()), Map.entry("returnHint", copy.returnHint()),
                Map.entry("noticeTitle", copy.noticeTitle()), Map.entry("notice", copy.notice()),
                Map.entry("footer", copy.footer()), Map.entry("mascotAlt", copy.mascotAlt()),
                Map.entry("preheader", copy.preheader()));
        for (var value : values.entrySet()) {
            html = html.replace("{{" + value.getKey() + "}}", HtmlUtils.htmlEscape(value.getValue(), "UTF-8"));
        }
        // Mail clients select the HTML or plain version of the same message.
        String plain = copy.intro() + "\n\n" + code + "\n\n" + copy.expiration()
                + "\n\n" + copy.returnHint() + "\n\n" + copy.noticeTitle() + "\n" + copy.notice()
                + "\n\nRemory — Little by little.\n" + copy.footer();
        return new Content(copy.subject(), plain, html);
    }

    private static Copy copy(String locale) {
        long minutes = PasswordResetPolicy.CODE_LIFETIME.toMinutes();
        return switch (locale == null ? "ko" : locale) {
            case "en" -> new Copy("en", "Your Remory verification code", "Reset your password",
                    "Enter the code below in Remory to choose a new password.", "Your verification code",
                    "Valid for %d minutes".formatted(minutes), "Return to Remory and enter this code to continue.",
                    "If you didn’t request this", "You can ignore this email. Your password is unchanged.",
                    "This email was sent automatically by Remory.", "Mongi the cloud bird",
                    "Use your one-time code to reset your Remory password.");
            case "ja" -> new Copy("ja", "Remory の認証コード", "パスワードの再設定",
                    "新しいパスワードを設定するには、以下のコードをRemoryに入力してください。", "認証コード",
                    "%d分間有効です".formatted(minutes), "Remoryの画面に戻り、コードを入力して続けてください。",
                    "お心当たりがない場合", "このメールを無視してください。パスワードは変更されません。",
                    "このメールはRemoryから自動送信されています。", "雲の小鳥モンイ",
                    "認証コードを使ってRemoryのパスワードを再設定してください。");
            case "zh-CN" -> new Copy("zh-CN", "Remory 验证码", "重置密码",
                    "请在Remory中输入以下验证码，设置新密码。", "您的验证码",
                    "%d分钟内有效".formatted(minutes), "请返回Remory页面，输入验证码后继续。",
                    "如果您未发起此请求", "请忽略这封邮件。您的密码不会更改。",
                    "这封邮件由Remory自动发送。", "云朵小鸟Mongi",
                    "使用一次性验证码重置您的Remory密码。");
            default -> new Copy("ko", "리모리 비밀번호 재설정 인증코드", "비밀번호 재설정",
                    "아래 인증코드를 리모리 화면에 입력해 주세요.", "인증코드",
                    "%d분 동안 유효해요".formatted(minutes), "인증 후 새 비밀번호를 설정할 수 있어요.",
                    "직접 요청하지 않았다면", "이 메일은 무시해 주세요. 비밀번호는 변경되지 않아요.",
                    "리모리에서 자동으로 발송한 메일이에요.", "구름새 몽이",
                    "인증코드로 리모리 비밀번호를 재설정해 주세요.");
        };
    }

    public record Content(String subject, String plainText, String html) {}
    private record Copy(String lang, String subject, String title, String intro, String codeLabel,
            String expiration, String returnHint, String noticeTitle, String notice,
            String footer, String mascotAlt, String preheader) {}
}
