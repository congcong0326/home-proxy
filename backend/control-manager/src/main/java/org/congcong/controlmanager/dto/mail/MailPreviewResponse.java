package org.congcong.controlmanager.dto.mail;

public record MailPreviewResponse(String bizKey, String subject, String contentType,
                                  String content, String plainTextContent) {
    public static MailPreviewResponse from(MailSendRequest request) {
        return new MailPreviewResponse(request.getBizKey(), request.getSubject(), request.getContentType(),
                request.getContent(), request.getPlainTextContent());
    }
}
