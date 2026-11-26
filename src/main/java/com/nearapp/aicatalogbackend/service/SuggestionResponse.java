package com.nearapp.aicatalogbackend.service;

import com.nearapp.aicatalogbackend.model.AiSuggestion;

public record SuggestionResponse(AiSuggestion suggestion, String modelVersion,
      Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
