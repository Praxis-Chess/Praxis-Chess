package com.praxis.evidence.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1's "was R1 already a threat?" test, when R1 is not the probe's own move.
 * The numbers are from real positions.
 */
@DisplayName("Reply was already a threat")
class ReplyThreatTest {

    /** 8.Qd5: Qxc1 worth 2.8 as a free move, next to Nxb3 worth 4.4. Version 1 said no. */
    @Test
    void aWinningCaptureIsAThreatEvenBesideABiggerOne() {
        assertThat(EvidenceGraphBuilder.replyIsThreat(2.8, 4.4, true)).isTrue();
        assertThat(EvidenceGraphBuilder.replyIsThreat(2.06, 5.49, true)).isTrue();
    }

    /** Phase 2's g3: it cleared one pawn only as a free tempo, when the real threat was Bxb7 at 3.1. */
    @Test
    void aQuietMoveFlatteredByATempoIsNot() {
        assertThat(EvidenceGraphBuilder.replyIsThreat(1.2, 3.1, false)).isFalse();
    }

    /** 16...Rcd8: Qd1 worth 1.6 beside Nb5 worth 2.8 — a threat of its own. */
    @Test
    void aQuietMoveWorthHalfTheBestThreatIs() {
        assertThat(EvidenceGraphBuilder.replyIsThreat(1.58, 2.79, false)).isTrue();
    }

    @Test
    void belowOnePawnNothingIsAThreat() {
        assertThat(EvidenceGraphBuilder.replyIsThreat(0.72, 0.9, true)).isFalse();
    }

    @Test
    void aSearchThatDidNotRunIsNotAThreat() {
        assertThat(EvidenceGraphBuilder.replyIsThreat(null, 2.0, true)).isFalse();
        assertThat(EvidenceGraphBuilder.replyIsThreat(2.0, null, true)).isFalse();
    }
}
