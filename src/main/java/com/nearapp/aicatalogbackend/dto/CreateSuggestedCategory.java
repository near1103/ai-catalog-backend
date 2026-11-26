package com.nearapp.aicatalogbackend.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record CreateSuggestedCategory(String proposalId, List<JsonNode> names) {}
