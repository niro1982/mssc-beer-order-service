package guru.sfg.brewery.model.events;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Builder
@Data
@RequiredArgsConstructor
public class ValidateOrderResponse implements Serializable {


    private static final long serialVersionUID = 7000275021313276443L;

    private UUID orderId;
    private Boolean isValid;
}
