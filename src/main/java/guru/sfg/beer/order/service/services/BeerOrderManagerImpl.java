package guru.sfg.beer.order.service.services;

import guru.sfg.beer.order.service.domain.BeerOrder;
import guru.sfg.beer.order.service.domain.BeerOrderEventEnum;
import guru.sfg.beer.order.service.domain.BeerOrderStatusEnum;
import guru.sfg.beer.order.service.repositories.BeerOrderRepository;
import guru.sfg.beer.order.service.sm.BeerOrderStateChangeInterceptor;
import guru.sfg.brewery.model.BeerOrderDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
@Service
public class BeerOrderManagerImpl implements BeerOrderManager {

    public static final String ORDER_ID_HEADER = "ORDER_ID_HEADER";
    private final StateMachineFactory<BeerOrderStatusEnum, BeerOrderEventEnum> stateMachineFactory;
    private final BeerOrderRepository beerOrderRepository;
    private final BeerOrderStateChangeInterceptor stateMachineInterceptor;
    private EntityManager entityManager;

    @Transactional
    @Override
    public BeerOrder newBeerOrder(BeerOrder beerOrder) {
        //make sure its a new order
        beerOrder.setId(null);
        beerOrder.setOrderStatus(BeerOrderStatusEnum.NEW);

        BeerOrder savedBeerOrder = beerOrderRepository.saveAndFlush(beerOrder);
        //sending validation event to the state machine
        sendBeerOrderEvent(savedBeerOrder, BeerOrderEventEnum.VALIDATE_ORDER);
        return savedBeerOrder;
    }

    @Transactional
    @Override
    public void processValidationResult(UUID orderId, Boolean isValid) {
        entityManager.flush(); //in case the new order was not saved yet (timing issues)

        Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(orderId);

        beerOrderOptional.ifPresentOrElse(beerOrder->{
            if (isValid){
                sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.VALIDATION_PASSED);

                awaitForStatus(orderId, BeerOrderStatusEnum.VALIDATED);

                //we need to bring a fresh instance of the beer order since after sending the
                //VALIDATION_PASSED event, the interceptor saves to DB and then the beerOrder
                //has an older version number than the one saved in DB and it will cause
                //an issue with Hibernate
                BeerOrder validateOrder = beerOrderRepository.findById(orderId).get();

                sendBeerOrderEvent(validateOrder, BeerOrderEventEnum.ALLOCAT_ORDER);
            } else {
                sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.VALIDATION_FAILED);
            }
        }, () -> log.error("Order not found id: " + orderId));


    }

    @Override
    public void beerOrderAllocationPassed(BeerOrderDto beerOrderDto) {
        Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(beerOrderDto.getId());
        sendBeerOrderEvent(beerOrderOptional.get(), BeerOrderEventEnum.ALLOCATION_SUCCESS);
        awaitForStatus(beerOrderDto.getId(), BeerOrderStatusEnum.VALIDATED);
        updateAllocatedQuantity(beerOrderDto);
    }

    @Override
    public void beerOrderAllocationPendingInventory(BeerOrderDto beerOrderDto) {
        Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(beerOrderDto.getId());
        sendBeerOrderEvent(beerOrderOptional.get(), BeerOrderEventEnum.ALLOCATION_NO_INVENTORY);
        awaitForStatus(beerOrderDto.getId(), BeerOrderStatusEnum.PENDING_INVENTORY);
        updateAllocatedQuantity(beerOrderDto);
    }

    private void updateAllocatedQuantity(BeerOrderDto beerOrderDto) {
        Optional<BeerOrder> allocatedBeerOrderOptional = beerOrderRepository.findById(beerOrderDto.getId());
        allocatedBeerOrderOptional.ifPresentOrElse(allocatedOrder->{
            allocatedOrder.getBeerOrderLines().forEach(beerOrderLine -> {
                beerOrderDto.getBeerOrderLines().forEach(beerOrderLineDto -> {
                    if (beerOrderLine.getId().equals(beerOrderLineDto.getId())){
                        beerOrderLineDto.setQuantityAllocated(beerOrderLine.getQuantityAllocated());
                    }
                });
            });
            beerOrderRepository.saveAndFlush(allocatedOrder);
        }, ()->log.error("order not found " + beerOrderDto.getId()));
    }

    @Override
    public void beerOrderAllocationFailed(BeerOrderDto beerOrderDto) {
        Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(beerOrderDto.getId());
        sendBeerOrderEvent(beerOrderOptional.get(), BeerOrderEventEnum.ALLOCATION_FAILED);
    }

    @Override
    public void beerOrderPickup(UUID id) {
        Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(id);
        beerOrderOptional.ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.BEERORDER_PICKED_UP);
        }, ()-> log.error("Order not found for id: " + id));
    }

    @Override
    public void cancelOrder(UUID orderId) {
        Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(orderId);
        beerOrderOptional.ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.CANCEL_ORDER);
        }, () -> log.error("Order not found for id: " + orderId));
    }

    //sending event to the state machine to decide how to progress with its statuses
    private void sendBeerOrderEvent(BeerOrder beerOrder, BeerOrderEventEnum eventEnum){
        StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> sm = build(beerOrder);

        Message msg = MessageBuilder.withPayload(eventEnum)
                .setHeader(ORDER_ID_HEADER, beerOrder.getId().toString())
                .build();

        sm.sendEvent(msg);
        //when we send that message (as event) to the state machine, it goes through the configuration logic of the state machine (configuration class)
        //and if that message/event causes a state change, then the interceptor is going to kick in

    }

    private StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> build(BeerOrder beerOrder){
        //spring is going to cache the state machine instance so we either get a new one here or cached one
        StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> sm = stateMachineFactory.getStateMachine(beerOrder.getId());

        sm.stop();

        sm.getStateMachineAccessor().doWithAllRegions(sma->{
            sma.addStateMachineInterceptor(stateMachineInterceptor);
            sma.resetStateMachine(new DefaultStateMachineContext<>(beerOrder.getOrderStatus(), null, null, null));
        });

        sm.start();

        return sm;
    }

    /**
     * since there was an issue of saving a new order to DB by state machine and while continuing the code the
     * order was not found yet in DB (timing issue), we create this method to wait until order is saved in DB
     * @param beerOrderId
     * @param statusEnum
     */
    private void awaitForStatus(UUID beerOrderId, BeerOrderStatusEnum statusEnum){
        AtomicBoolean found = new AtomicBoolean();
        AtomicInteger loopCount = new AtomicInteger();

        while (!found.get()){
            if (loopCount.incrementAndGet() > 10){
                found.set(true);
                log.debug("loop retries exceeded");
            }

            beerOrderRepository.findById(beerOrderId).ifPresentOrElse(beerOrder -> {
                if (beerOrder.getOrderStatus().equals(statusEnum)){
                    found.set(true);
                    log.debug("Order found");
                } else {
                    log.debug("Order status not equal. expected " + statusEnum.name() + " found: " + beerOrder.getOrderStatus().name());
                }
            }, () -> {
                log.debug("order id not found");
            });

            if (!found.get()){
                log.debug("sleeping for retry");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    //do nothing
                }
            }
        }
    }
}
