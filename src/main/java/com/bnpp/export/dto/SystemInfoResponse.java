package com.bnpp.export.dto;

/**
 * Which database the module runs on, for the sidebar's Database switch, and the saved PostgreSQL connection
 * (never its password).
 *
 * @param mode                  h2 or postgres
 * @param label                 H2 or PostgreSQL
 * @param switchable            whether the switch can restart the module on the other database (false when it was
 *                              started with an explicit profile)
 * @param postgresPasswordSaved whether a PostgreSQL password is saved, so the dialog can leave it empty
 * @param switchError           why the last switch to PostgreSQL did not start, or null
 */
public record SystemInfoResponse(boolean mockEnabled, String mode, String label, boolean switchable,
                                 String postgresHost, int postgresPort, String postgresDatabase, String postgresUsername,
                                 boolean postgresPasswordSaved, String switchError) {
}
