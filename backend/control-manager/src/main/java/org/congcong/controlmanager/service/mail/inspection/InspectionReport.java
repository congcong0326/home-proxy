package org.congcong.controlmanager.service.mail.inspection;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

public record InspectionReport(String reportId, Instant from, Instant to, ZoneId zone,
                               List<InspectionSection> sections) { }
