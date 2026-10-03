package com.praxis.ai;

/**
 * A completion's text.
 *
 * @param truncated the provider stopped at the token limit, so the text may be cut off
 */
public record Completion(String text, boolean truncated) {}
