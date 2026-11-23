package com.bnpp.export.dto;

/** An approver's decision on a list version, with a comment (required to reject or request changes). */
public record ReviewDecisionRequest(String comment) {
}
