package com.bnpp.export.dto;

/** The switch was accepted: the module restarts on {@code mode} now. */
public record DatabaseSwitchResponse(boolean restarting, String mode, String message) {
}
