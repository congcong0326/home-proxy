package org.congcong.controlmanager.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.congcong.controlmanager.dto.mail.MailBizTypeRequest;
import org.congcong.controlmanager.dto.mail.MailSendRequest;
import org.congcong.controlmanager.entity.scheduler.ScheduledTask;
import org.congcong.controlmanager.service.MailSendService;
import org.congcong.controlmanager.service.mail.MailBizHandler;
import org.congcong.controlmanager.service.mail.MailBizHandlerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
@Slf4j
public class MailScheduledTaskHandler implements ScheduledTaskHandler {

    public static final String TASK_TYPE = "mail_biz";

    private final MailBizHandlerFactory mailBizHandlerFactory;
    private final MailSendService mailSendService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String taskType) {
        return TASK_TYPE.equalsIgnoreCase(taskType);
    }

    @Override
    public Runnable buildTask(ScheduledTask task) {
        MailScheduledTaskConfig config = parseConfig(task);
        String bizKey = StringUtils.hasText(config.getBizKey()) ? config.getBizKey() : task.getBizKey();
        if (!StringUtils.hasText(bizKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mail 任务缺少 bizKey");
        }
        MailBizHandler handler = mailBizHandlerFactory.getHandler(bizKey);
        return () -> {
            MailBizTypeRequest req = new MailBizTypeRequest();
            req.setBizKey(bizKey);
            MailSendRequest sendRequest = handler.build(req);
            if (StringUtils.hasText(config.getRequestIdPrefix())) {
                sendRequest.setRequestId(config.getRequestIdPrefix() + System.currentTimeMillis());
            }
            mailSendService.send(sendRequest);
            log.info("定时任务发送邮件完成 taskKey={} bizKey={}", task.getTaskKey(), bizKey);
        };
    }

    private MailScheduledTaskConfig parseConfig(ScheduledTask task) {
        if (!StringUtils.hasText(task.getConfigJson())) {
            MailScheduledTaskConfig config = new MailScheduledTaskConfig();
            config.setBizKey(task.getBizKey());
            return config;
        }
        try {
            MailScheduledTaskConfig config = objectMapper.readValue(task.getConfigJson(), MailScheduledTaskConfig.class);
            if (!StringUtils.hasText(config.getBizKey())) {
                config.setBizKey(task.getBizKey());
            }
            return config;
        } catch (Exception e) {
            log.warn("解析 mail 任务配置失败，使用默认 bizKey={}，config={}", task.getBizKey(), task.getConfigJson(), e);
            MailScheduledTaskConfig fallback = new MailScheduledTaskConfig();
            fallback.setBizKey(task.getBizKey());
            return fallback;
        }
    }
}
