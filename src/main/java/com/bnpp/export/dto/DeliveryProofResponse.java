package com.bnpp.export.dto;

/**
 * Whether a delivered file's archived copy still matches the hash recorded when it was delivered.
 *
 * @param result MATCH, MISMATCH, or NOT_SENT when nothing was delivered yet
 */
public record DeliveryProofResponse(String result, String storedHash, String archiveHash, String archiveFileName, String message) {
}
