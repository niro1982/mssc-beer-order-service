package guru.sfg.beer.order.service.services.listeners;

import guru.sfg.beer.order.service.config.JmsConfig;
import guru.sfg.beer.order.service.services.BeerOrderManager;
import guru.sfg.brewery.model.BeerOrderDto;
import guru.sfg.brewery.model.events.AllocationOrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class AllocationResultListener {

    private final BeerOrderManager beerOrderManager;

    @JmsListener(destination = JmsConfig.ALLOCATE_ORDER_RESPONSE_QUEUE)
    public void listen(AllocationOrderResponse orderResponse){
        boolean isError = orderResponse.getAllocationError();
        boolean isPendingInventory = orderResponse.getPendingInventory();
        BeerOrderDto beerOrderDto = orderResponse.getBeerOrderDto();

        if (isError){
            beerOrderManager.beerOrderAllocationFailed(orderResponse.getBeerOrderDto());
        } else if (isPendingInventory){
            beerOrderManager.beerOrderAllocationPendingInventory(orderResponse.getBeerOrderDto());
        } else {
            beerOrderManager.beerOrderAllocationPassed(orderResponse.getBeerOrderDto());
        }
    }
}
