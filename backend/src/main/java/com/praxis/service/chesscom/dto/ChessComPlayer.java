package com.praxis.service.chesscom.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChessComPlayer(
    String username,
    Integer rating,
    String result,
    @JsonProperty("@id") String id,
    Double accuracy
) {}
