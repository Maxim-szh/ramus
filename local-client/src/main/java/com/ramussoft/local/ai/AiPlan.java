package com.ramussoft.local.ai;

import java.util.List;

public record AiPlan(String summary, List<AiOperation> operations) {
    public record AiOperation(String tool, String qualifier, String name, String source, String target,
                              String icom, String style, String color) { }
}
