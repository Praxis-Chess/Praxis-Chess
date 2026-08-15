package com.praxis.dto;

import com.praxis.domain.enums.AttemptRating;

import java.util.UUID;

public record AttemptRequest(
        UUID cardId,
        String movePlayed,   // UCI, or null if answer revealed
        boolean correct,
        AttemptRating rating,
        Long responseMs
) {}
