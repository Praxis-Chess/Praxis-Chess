package com.praxis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.config.PraxisClock;
import com.praxis.domain.Card;
import com.praxis.domain.Game;
import com.praxis.domain.MoveError;
import com.praxis.domain.TrainingPlan;
import com.praxis.domain.enums.CardStatus;
import com.praxis.domain.enums.Severity;
import com.praxis.repository.CardRepository;
import com.praxis.repository.MoveErrorRepository;
import com.praxis.repository.TrainingPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The deck rule (DeckRule; POLISH_PLAN.md D3a–c, D8). */
class CardGeneratorServiceTest {

    private static final String USER = "lattice";

    private MoveErrorRepository errors;
    private CardRepository cards;
    private TrainingPlanRepository plans;
    private CardGeneratorService generator;

    @BeforeEach
    void setUp() {
        errors = mock(MoveErrorRepository.class);
        cards = mock(CardRepository.class);
        plans = mock(TrainingPlanRepository.class);
        generator = new CardGeneratorService(errors, cards, plans, new ObjectMapper(),
                PraxisClock.fixed(java.time.ZoneId.systemDefault(), java.time.Clock.systemDefaultZone()));
        when(cards.countActiveByUsername(USER)).thenReturn(0L);
        when(cards.findByUsernameAndFenPosition(anyString(), anyString())).thenReturn(Optional.empty());
        when(cards.countForGameAndPly(anyString(), any(), anyInt())).thenReturn(0L);
        when(plans.findTopByUsernameOrderByGeneratedAtDesc(USER)).thenReturn(Optional.empty());
    }

    private static Game game(int daysAgo) {
        return Game.builder().id(UUID.randomUUID())
                .playedAt(OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.UTC).minusDays(daysAgo)).build();
    }

    private static MoveError error(Game g, int ply, Severity s) {
        return MoveError.builder().id(UUID.randomUUID()).game(g).moveNumber(ply).severity(s)
                .fenPosition("fen-" + g.getId() + "-" + ply).betterMove("e2e4").build();
    }

    private List<Card> saved() {
        ArgumentCaptor<Card> c = ArgumentCaptor.forClass(Card.class);
        verify(cards, atLeast(0)).save(c.capture());
        return c.getAllValues();
    }

    @Test
    @DisplayName("Blunders and mistakes become cards; inaccuracies never do")
    void noInaccuracies() {
        Game g = game(1);
        when(errors.findDrillCandidatesByUsername(USER)).thenReturn(new ArrayList<>(List.of(
                error(g, 10, Severity.INACCURACY), error(g, 12, Severity.MISTAKE), error(g, 14, Severity.BLUNDER))));

        assertThat(generator.generateForUser(USER)).isEqualTo(2);
        assertThat(saved()).extracting(c -> c.getSourceError().getSeverity())
                .containsExactly(Severity.BLUNDER, Severity.MISTAKE);
    }

    @Test
    @DisplayName("D8: a new card is due the day it is made")
    void dueToday() {
        when(errors.findDrillCandidatesByUsername(USER)).thenReturn(new ArrayList<>(List.of(error(game(3), 8, Severity.BLUNDER))));
        generator.generateForUser(USER);
        Card c = saved().get(0);
        assertThat(c.getStatus()).isEqualTo(CardStatus.NEW);
        assertThat(c.getDueDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("D3b: when the cap bites, blunders first, then the plan's focus, then the most recent")
    void order() {
        Game old = game(30), recent = game(1), focusGame = game(20);
        MoveError oldBlunder = error(old, 5, Severity.BLUNDER);
        MoveError recentMistake = error(recent, 7, Severity.MISTAKE);
        MoveError focusMistake = error(focusGame, 9, Severity.MISTAKE);
        MoveError olderMistake = error(old, 11, Severity.MISTAKE);
        when(errors.findDrillCandidatesByUsername(USER)).thenReturn(new ArrayList<>(List.of(
                olderMistake, recentMistake, focusMistake, oldBlunder)));
        TrainingPlan plan = TrainingPlan.builder()
                .planJson("{\"sessions\":[{\"positions\":[{\"game_id\":\"" + focusGame.getId() + "\",\"ply\":9}]}]}")
                .build();
        when(plans.findTopByUsernameOrderByGeneratedAtDesc(USER)).thenReturn(Optional.of(plan));

        generator.generateForUser(USER);
        assertThat(saved()).extracting(Card::getSourceError)
                .containsExactly(oldBlunder, focusMistake, recentMistake, olderMistake);
    }

    @Test
    @DisplayName("The cap: only room up to the target is filled")
    void cap() {
        when(cards.countActiveByUsername(USER)).thenReturn(119L);
        Game g = game(1);
        when(errors.findDrillCandidatesByUsername(USER)).thenReturn(new ArrayList<>(List.of(
                error(g, 1, Severity.BLUNDER), error(g, 3, Severity.BLUNDER))));
        assertThat(generator.generateForUser(USER)).isEqualTo(1);
    }

    @Test
    @DisplayName("Idempotent: a game and ply that already has a card gets no second one")
    void idempotent() {
        Game g = game(1);
        MoveError e = error(g, 12, Severity.BLUNDER);
        when(errors.findDrillCandidatesByUsername(USER)).thenReturn(new ArrayList<>(List.of(e)));
        when(cards.countForGameAndPly(eq(USER), eq(g.getId()), eq(12))).thenReturn(1L);
        assertThat(generator.generateForUser(USER)).isZero();
        verify(cards, never()).save(any());
    }

    @Test
    @DisplayName("No player yet: nothing to do")
    void noPlayer() {
        assertThat(generator.generateForUser("")).isZero();
        verifyNoInteractions(errors);
    }
}
