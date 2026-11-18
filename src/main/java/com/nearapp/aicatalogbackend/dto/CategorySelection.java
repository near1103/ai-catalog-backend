package com.nearapp.aicatalogbackend.dto;

import jakarta.validation.constraints.NotBlank;

public record CategorySelection(@NotBlank String categoryId) {}
