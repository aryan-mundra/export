package com.bnpp.export.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Switch the module to another database. For PostgreSQL, the connection: an empty password keeps the saved one.
 *
 * @param mode h2 or postgres
 */
public record DatabaseSwitchRequest(@NotBlank String mode, String host, Integer port, String database, String username,
                                    String password) {
}
