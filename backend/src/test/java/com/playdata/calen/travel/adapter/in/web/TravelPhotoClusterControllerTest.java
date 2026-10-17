package com.playdata.calen.travel.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.*;
import com.playdata.calen.travel.dto.TravelMyMapClusterRepresentativeRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

class TravelPhotoClusterControllerTest {
    final ManageTravelPhotoClustersUseCase clusters=mock(ManageTravelPhotoClustersUseCase.class);
    final TaskExecutor executor=mock(TaskExecutor.class);
    final TravelPhotoClusterController controller=new TravelPhotoClusterController(clusters,executor);
    final AppUserPrincipal user=new AppUserPrincipal(7L,"owner","Owner","",AppUserRole.USER,true);
    @Test void asyncReadUsesTheSameDedicatedExecutorAndCarriesPrincipalAndPageArguments() {
        var result=page(); when(clusters.page(7L,10L,2,36,12L)).thenReturn(result);
        var future=controller.get(user,10L,2,36,12L); assertThat(future).isNotDone(); verifyNoInteractions(clusters);
        var task=ArgumentCaptor.forClass(Runnable.class); verify(executor).execute(task.capture()); task.getValue().run();
        assertThat(future.join().id()).isEqualTo(10L); assertThat(future.join().size()).isEqualTo(36);
        verify(clusters).page(7L,10L,2,36,12L);
    }
    @Test void asyncFailureRemainsExceptionalInsteadOfReturningAnEmptySuccess() {
        when(clusters.page(7L,10L,0,12,null)).thenThrow(new IllegalStateException("read failure"));
        var future=controller.get(user,10L,0,12,null);
        var task=ArgumentCaptor.forClass(Runnable.class); verify(executor).execute(task.capture()); task.getValue().run();
        assertThatThrownBy(future::join).hasCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("read failure");
    }
    @Test void representativeWriteUsesTheUseCaseWithoutChangingTheResponseEnvelope() {
        when(clusters.changeRepresentative(7L,10L,12L)).thenReturn(page());
        var response=controller.changeRepresentative(user,10L,new TravelMyMapClusterRepresentativeRequest(12L));
        assertThat(response.id()).isEqualTo(10L); assertThat(response.representativeMediaId()).isEqualTo(12L);
        verify(clusters).changeRepresentative(7L,10L,12L); verifyNoInteractions(executor);
    }
    static Page page() { return new Page(new Detail(10L,12L,22L,null,null,0,0,null,false,null,List.of()),List.of(),2,36,0,false); }
}
