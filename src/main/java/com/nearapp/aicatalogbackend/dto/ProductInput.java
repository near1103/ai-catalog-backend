package com.nearapp.aicatalogbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record ProductInput(
    @NotBlank @Size(max = 300) String name,
    @Size(max = 20000) String description,
    @NotNull @Size(max = 100)
        Map<@NotBlank @Size(max = 100) String, @NotBlank @Size(max = 2000) String>
            characteristics) {}
