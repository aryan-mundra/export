package com.bnpp.export.dto;

/**
 * Which database the module runs on, for the sidebar's Database switch.
 *
 * @param mode  h2 or postgres
 * @param label H2 or PostgreSQL
 */
public record SystemInfoResponse(boolean mockEnabled, String mode, String label) {
}
