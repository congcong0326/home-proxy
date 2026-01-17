package org.congcong.controlmanager.service.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 基于策略模式的邮件业务处理工厂。
 */
@Component
@RequiredArgsConstructor
public class MailBizHandlerFactory {

    private final List<MailBizHandler> handlers;

    public MailBizHandler getHandler(String bizKey) {
        return handlers.stream()
                .filter(h -> h.supports(bizKey))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unsupported bizKey: " + bizKey));
    }
}
