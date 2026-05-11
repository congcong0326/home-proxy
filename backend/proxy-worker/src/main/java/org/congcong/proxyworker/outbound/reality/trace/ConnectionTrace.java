package org.congcong.proxyworker.outbound.reality.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConnectionTrace {

    private final List<TraceEvent> events = new ArrayList<TraceEvent>();

    public void event(String label, String detail) {
        events.add(new TraceEvent(Instant.now().toString(), label, detail));
    }

    public List<TraceEvent> events() {
        return Collections.unmodifiableList(events);
    }

    public int count(String label) {
        int count = 0;
        for (TraceEvent event : events) {
            if (event.label().equals(label)) {
                count++;
            }
        }
        return count;
    }

    public String toPrettyString() {
        StringBuilder builder = new StringBuilder();
        for (TraceEvent event : events) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(event.at()).append(' ')
                    .append(event.label()).append(' ')
                    .append(event.detail());
        }
        return builder.toString();
    }

    public static final class TraceEvent {

        private final String at;
        private final String label;
        private final String detail;

        public TraceEvent(String at, String label, String detail) {
            this.at = at;
            this.label = label;
            this.detail = detail;
        }

        public String at() {
            return at;
        }

        public String label() {
            return label;
        }

        public String detail() {
            return detail;
        }
    }
}
