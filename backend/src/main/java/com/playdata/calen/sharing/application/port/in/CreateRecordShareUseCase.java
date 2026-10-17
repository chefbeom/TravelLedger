package com.playdata.calen.sharing.application.port.in;

import com.playdata.calen.sharing.domain.model.ShareCreationData.Receipt;
import java.util.List;

public interface CreateRecordShareUseCase {
    List<Receipt> create(Long userId, CreateRecordShareCommand command);
}
