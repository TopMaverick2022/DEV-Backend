package com.developerev.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Slf4j
@Service
@SuppressWarnings("null")
public class EmailService {

    private final JavaMailSender otpMailSender;
    private final JavaMailSender notificationMailSender;

    private final String otpFromEmail;
    private final String notificationFromEmail;

    public EmailService(
            @Value("${spring.mail.host:smtp.gmail.com}") String host,
            @Value("${spring.mail.port:587}") int port,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") String smtpAuth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") String starttlsEnable,
            @Value("${app.mail.otp.username:developerev.otp@gmail.com}") String otpUsername,
            @Value("${app.mail.otp.password}") String otpPassword,
            @Value("${app.mail.notification.username:developerev.notifications@gmail.com}") String notificationUsername,
            @Value("${app.mail.notification.password}") String notificationPassword) {

        this.otpFromEmail = otpUsername;
        this.notificationFromEmail = notificationUsername;

        this.otpMailSender = createMailSender(host, port, smtpAuth, starttlsEnable, otpUsername, otpPassword);
        this.notificationMailSender = createMailSender(host, port, smtpAuth, starttlsEnable, notificationUsername, notificationPassword);
    }

    private JavaMailSender createMailSender(String host, int port, String smtpAuth, String starttlsEnable, String username, String password) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", smtpAuth);
        props.put("mail.smtp.starttls.enable", starttlsEnable);
        props.put("mail.smtp.ssl.trust", host);

        return mailSender;
    }

    public void sendOtpEmail(String to, String subject, String text) {
        try {
            MimeMessage message = otpMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(otpFromEmail, "DeveloperEV");
            helper.setTo(to);
            helper.setReplyTo(otpFromEmail, "DeveloperEV Support");
            helper.setSubject(subject);
            helper.setText(text, false); // plain text for OTP
            otpMailSender.send(message);
            log.info("[EMAIL][OTP] Sent to: {}", to);
        } catch (Exception e) {
            log.error("[EMAIL][OTP] Failed to send to: {} | reason: {}", to, e.getMessage(), e);
        }
    }

    public void sendNotificationEmail(String to, String subject, String text) {
        try {
            MimeMessage message = notificationMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(notificationFromEmail, "DeveloperEV");
            helper.setTo(to);
            helper.setReplyTo(notificationFromEmail, "DeveloperEV Team");
            helper.setSubject(subject);
            // Send both plain text and HTML for better deliverability
            helper.setText(text, toHtml(text));
            notificationMailSender.send(message);
            log.info("[EMAIL][NOTIFICATION] Sent to: {}", to);
        } catch (Exception e) {
            log.error("[EMAIL][NOTIFICATION] Failed to send to: {} | reason: {}", to, e.getMessage(), e);
        }
    }

    /**
     * Wraps plain text in a minimal branded HTML shell.
     * Significantly improves spam score vs raw text/plain.
     */
    private String toHtml(String text) {
        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<!DOCTYPE html><html><body style='font-family:Arial,sans-serif;color:#333;max-width:600px;margin:auto;padding:24px;'>" +
               "<div style='border-bottom:2px solid #6c63ff;padding-bottom:12px;margin-bottom:20px;'>" +
               "<h2 style='color:#6c63ff;margin:0;'>DeveloperEV</h2></div>" +
               "<p>" + escaped + "</p>" +
               "<hr style='border:none;border-top:1px solid #eee;margin:24px 0;'>" +
               "<p style='font-size:12px;color:#999;'>You received this email because you have an account at DeveloperEV. " +
               "If you did not request this, please ignore it.</p>" +
               "</body></html>";
    }
}
