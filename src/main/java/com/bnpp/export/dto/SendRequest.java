package com.bnpp.export.dto;

import java.util.List;

/** Who receives a data version; the review token from the review proves the send matches what was reviewed. */
public record SendRequest(List<Long> subscriberIds, String reviewToken) {
}
