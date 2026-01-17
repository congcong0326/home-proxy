package org.congcong.controlmanager.service.mail;

import org.congcong.controlmanager.dto.mail.MailBizTypeRequest;
import org.congcong.controlmanager.dto.mail.MailSendRequest;

/**
 * 邮件业务处理策略接口。
 */
public interface MailBizHandler {

    /**
     * 是否支持当前的 bizKey。
     */
    boolean supports(String bizKey);

    /**
     * 根据请求构建邮件发送请求。
     */
    MailSendRequest build(MailBizTypeRequest request);
}
