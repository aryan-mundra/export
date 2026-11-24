package com.bnpp.export.dto;

/** The version holding a list's current data, and whether it was just created (false: the data had not changed). */
public record TakeVersionResponse(DataVersionResponse version, boolean created) {
}
