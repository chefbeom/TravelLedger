package com.playdata.calen.sharing.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JpaShareParticipantsAdapterTest {
    private final AppUserRepository accounts = mock(AppUserRepository.class);
    private final JpaShareParticipantsAdapter adapter = new JpaShareParticipantsAdapter(mock(AppUserService.class), accounts,
            mock(TravelShareGroupRepository.class), mock(TravelShareGroupMemberRepository.class), mock(jakarta.persistence.EntityManager.class));

    @Test void locksEachParticipantOnceInStableIdOrder() {
        for (long id = 1; id <= 3; id++) when(accounts.findActiveForPreferenceUpdate(id)).thenReturn(Optional.of(new AppUser()));
        adapter.lockActiveActors(3L, List.of(2L, 1L, 2L));
        var order = inOrder(accounts);
        order.verify(accounts).findActiveForPreferenceUpdate(1L);
        order.verify(accounts).findActiveForPreferenceUpdate(2L);
        order.verify(accounts).findActiveForPreferenceUpdate(3L);
        order.verifyNoMoreInteractions();
    }

    @Test void anInactiveParticipantAbortsBeforeTheRestAreLocked() {
        when(accounts.findActiveForPreferenceUpdate(1L)).thenReturn(Optional.of(new AppUser()));
        when(accounts.findActiveForPreferenceUpdate(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter.lockActiveActors(3L, List.of(2L, 1L))).isInstanceOf(NotFoundException.class);
        verify(accounts, never()).findActiveForPreferenceUpdate(3L);
    }

    @Test void nullInputsAreRejectedBeforeAccessingTheDatabase() {
        assertThatThrownBy(() -> adapter.lockActiveActors(null, List.of(1L))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> adapter.lockActiveActors(1L, null)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(accounts);
    }
}
