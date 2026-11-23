package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Switch the module to another database. The PostgreSQL connection comes from config/database.properties.
 *
 * @param mode h2 or postgres
 */
public record DatabaseSwitchRequest(@NotBlank String mode) {
}
