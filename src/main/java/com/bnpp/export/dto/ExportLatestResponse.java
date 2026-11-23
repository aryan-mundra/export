package com.bnpp.export.dto;

/**
 * The data version an export of a list's latest data gave.
 *
 * @param created true when the data had changed and a new version was made; false when the existing one was reused
 */
public record ExportLatestResponse(DataVersionResponse snapshot, boolean created) {
}
