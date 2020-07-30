package guru.sfg.beer.order.service.services.testcomponents;

import guru.sfg.beer.order.service.config.JmsConfig;
import guru.sfg.brewery.model.events.AllocateOrderRequest;
import guru.sfg.brewery.model.events.AllocationOrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class BeerOrderAllocationListener {

    private final JmsTemplate jmsTemplate;

    @JmsListener(destination = JmsConfig.ALLOCATE_ORDER_QUEUE)
    public void listen(Message msg){
        boolean isPendingInventory = false;
        boolean isAllocationError = false;
        boolean sendResponse = true;

        AllocateOrderRequest request = (AllocateOrderRequest) msg.getPayload();
        if (request.getBeerOrderDto().getCustomerRef() != null &&
                request.getBeerOrderDto().getCustomerRef().equals("fail-allocation")){
                isAllocationError = true;
        } else if (request.getBeerOrderDto().getCustomerRef() != null &&
                request.getBeerOrderDto().getCustomerRef().equals("fail-allocation-pending-inventory")) {
               isPendingInventory = true;
        } else if (request.getBeerOrderDto().getCustomerRef() != null &&
                request.getBeerOrderDto().getCustomerRef().equals("dont-allocate")) {
            sendResponse = false;
        } else {
            request.getBeerOrderDto().getBeerOrderLines().forEach(beerOrderLineDto -> {
                beerOrderLineDto.setQuantityAllocated(request.getBeerOrderDto().getOrderQuantity());
            });
        }

        if (sendResponse) {
            jmsTemplate.convertAndSend(JmsConfig.ALLOCATE_ORDER_RESPONSE_QUEUE, AllocationOrderResponse.builder()
                    .pendingInventory(isPendingInventory)
                    .allocationError(isAllocationError)
                    .beerOrderDto(request.getBeerOrderDto())
                    .build());
        }
    }
}
