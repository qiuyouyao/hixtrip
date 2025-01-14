package com.hixtrip.sample.app.service;

import com.hixtrip.sample.app.api.OrderService;
import com.hixtrip.sample.client.OrderReq;
import com.hixtrip.sample.client.PayCallbackReq;
import com.hixtrip.sample.domain.commodity.CommodityDomainService;
import com.hixtrip.sample.domain.inventory.InventoryDomainService;
import com.hixtrip.sample.domain.order.OrderDomainService;
import com.hixtrip.sample.domain.order.model.Order;
import com.hixtrip.sample.domain.pay.PayDomainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    InventoryDomainService inventoryDomainService;
    @Autowired
    CommodityDomainService commodityDomainService;
    @Autowired
    OrderDomainService orderDomainService;
    @Autowired
    PayDomainService payDomainService;
    @Autowired
    RedisTemplate<String, Long> redisTemplate;

    public static final String INVENTORY_KEY = "HIXTRIP:INVENTORY";

    @Override
    public Order create(OrderReq orderReq, Long userId) {
        String skuId = orderReq.getSkuId();
        Long num = orderReq.getNum();
        // 假设redis中维护了可售库存
        HashOperations<String, Object, Object> hashOperations = redisTemplate.opsForHash();
        if (hashOperations.increment(INVENTORY_KEY, skuId, -num) < 0 ) {
            // 库存不足, 把扣除库存回滚回去
            hashOperations.increment(INVENTORY_KEY, skuId, num);
            throw new RuntimeException("库存不足");
        }
        Long inventory = inventoryDomainService.getInventory(skuId);
        if (inventoryDomainService.changeInventory(skuId, inventory - num, num, 0L)) {
            BigDecimal skuPrice = commodityDomainService.getSkuPrice(skuId);

            Order order = Order.builder()
                    .userId(userId)
                    .skuId(skuId)
                    .skuPrice(skuPrice)
                    .num(num).build();
            order.calcTotalAmount();
            Order newOrder = orderDomainService.createOrder(order);
            // 传入订单信息和回调地址调用第三方支付接口, 获取支付链接并更新到订单记录上
            newOrder.setPayUrl("https://xxx");

            return newOrder;
        }
        hashOperations.increment(INVENTORY_KEY, skuId, num);
        throw new RuntimeException("库存预占失败");
    }

    @Override
    public void handleCallback(PayCallbackReq payCallbackReq) {
        payDomainService.payRecord();
        // 根据orderNo查询订单信息, 这边用new Order()代替
        Order order = new Order();
        boolean paySuccess = Order.PayStatusEnum.PAY_SUCCESS.getCode().equals(payCallbackReq.getPayStatus());
        if (order.checkOrderHandleComplete()) {
            // 需要判断是否重复支付，判断依据暂定为支付流水号不一致
            if (paySuccess && order.duplicatePay(payCallbackReq.getSerialNumber())) {
                // 重复支付，需要主动发起退款(其它处理措施也可)，发起退款也需要做记录，避免重复退
                orderDomainService.orderDuplicatePay(order);
            }
            return;
        }
        Long inventory = inventoryDomainService.getInventory(order.getSkuId());
        if (paySuccess) {
            order.setPayAmount(payCallbackReq.getPayAmount());
            order.setPayTime(payCallbackReq.getPayTime());
            order.setThirdPartySerialNumber(payCallbackReq.getSerialNumber());
            orderDomainService.orderPaySuccess(order);
            inventoryDomainService.changeInventory(order.getSkuId(), inventory, -order.getNum(), order.getNum());
        } else {
            orderDomainService.orderPayFail(order);
            inventoryDomainService.changeInventory(order.getSkuId(), inventory + order.getNum(), -order.getNum(), 0L);
            // 支付失败后需要将库存归还到redis中
            redisTemplate.opsForHash().increment(INVENTORY_KEY, order.getSkuId(), order.getNum());
        }
    }

}
