package com.playdata.calen.travel.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.travel.repository.TravelMapShareLinkRepository;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TravelMapLinkRuntimeTest {
    @Test void secure192BitUrlSafeTokenRetriesExistingCandidateWithoutChangingTheWireFormat() {
        var links = mock(TravelMapShareLinkRepository.class); var runtime = new TravelMapLinkRuntime(links);
        when(links.existsByToken(anyString())).thenReturn(true, false);
        var value = runtime.uniqueToken();
        var tokens = ArgumentCaptor.forClass(String.class); verify(links, times(2)).existsByToken(tokens.capture());
        assertThat(value).isEqualTo(tokens.getAllValues().get(1)).matches("[A-Za-z0-9_-]{32}");
        assertThat(Base64.getUrlDecoder().decode(value)).hasSize(24);
        assertThat(tokens.getAllValues().get(0)).isNotEqualTo(value);
    }
    @Test void creationTimeStillUsesTheLocalClock() {
        var runtime = new TravelMapLinkRuntime(mock(TravelMapShareLinkRepository.class));
        var before = LocalDateTime.now(); var now = runtime.now(); var after = LocalDateTime.now();
        assertThat(now).isBetween(before, after);
    }
}
