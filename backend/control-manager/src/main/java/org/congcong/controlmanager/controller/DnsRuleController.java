package org.congcong.controlmanager.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dns/rule")
@RequiredArgsConstructor
@Validated
public class DnsRuleController {
}
